package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：重复提醒改由「用户可见闹钟」驱动。
//
// 为什么不再用前台服务里的 Handler.postDelayed 等 5 分钟：
//  1) targetSdk 36 下后台启动前台服务受严格限制，服务随时可能被回收，Handler 随之消失，
//     「5 分钟后再响」就永远不响了；
//  2) Android 14+ 的 shortService 前台服务只有约 3 分钟时限，长驻等待会触发 onTimeout / ANR；
//  3) Doze 低电耗模式下 Handler 会被推迟，系统闹钟才是最可靠的计时手段。
//
// 实现依据：Android 官方《Schedule alarms》—— setAlarmClock() 属于「用户可见闹钟」，
// 不受 Android 12+ SCHEDULE_EXACT_ALARM 权限限制，且允许在低电耗模式下按时唤醒；
// 系统会在状态栏显示闹钟图标，语义与本应用「像闹钟一样提醒」完全一致。
// 因此本工程遵循最小权限原则，不声明 SCHEDULE_EXACT_ALARM。

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 重复提醒闹钟的唯一调度入口。 */
public final class AlertScheduler {

    private static final String TAG = "AlertScheduler";
    public static final String ACTION_REPEAT = "com.saiga.find.messagefinder.action.REPEAT_ALERT"; // modified: 新增
    public static final String EXTRA_ROUND = "alert_round"; // modified: 新增

    private static final int REQ_REPEAT = 41;
    private static final int REQ_SHOW = 42;

    private AlertScheduler() {
        // 工具类不允许实例化
    }

    /**
     * 安排下一轮提醒。
     *
     * @param context      任意 Context
     * @param delayMinutes 间隔（分钟），最小按 1 分钟处理
     * @param nextRound    下一轮的轮次，从 1 开始
     */
    public static void scheduleNext(Context context, int delayMinutes, int nextRound) { // modified: 新增
        if (context == null) {
            return;
        }
        // modified: 【独立复审修正】原实现直接 context.getApplicationContext()，
        // 返回值理论上可能为 null（测试桩 / 极端 Context 包装），
        // 而后续 getSystemService 在 try 之外，NPE 不会被降级逻辑接住。
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager unavailable");
            return;
        }
        final long triggerAtMillis = System.currentTimeMillis()
                + Math.max(1, delayMinutes) * 60_000L;
        final int safeRound = Math.max(1, nextRound);
        try {
            PendingIntent operation = buildRepeatIntent(app, safeRound);

            // AlarmClockInfo 的 showIntent：用户点状态栏闹钟图标时打开主界面。
            // targetSdk 34+ 要求发往非导出组件的 Intent 必须是显式的，这里显式指定 MainActivity。
            Intent show = new Intent(app, MainActivity.class);
            show.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showIntent = PendingIntent.getActivity(app, REQ_SHOW, show,
                    pendingFlags()); // modified: FLAG_IMMUTABLE 合规

            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation);
            Log.d(TAG, "next alert scheduled, round=" + safeRound
                    + ", in " + Math.max(1, delayMinutes) + " min");
        } catch (Exception e) {
            // 个别定制 ROM 会对 setAlarmClock 抛 SecurityException / NullPointerException。
            // 降级为不精确闹钟：宁可晚几分钟，也不要彻底不响。
            Log.e(TAG, "setAlarmClock failed, degrade to inexact alarm", e);
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis,
                        buildRepeatIntent(app, safeRound));
            } catch (Exception fallbackError) {
                Log.e(TAG, "inexact alarm also failed", fallbackError);
            }
        }
    }

    /** 取消尚未触发的下一轮提醒。用户点「停止」、收到新短信时都要调用。 */
    public static void cancel(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                // PendingIntent 等价性忽略 extras，因此 round 传 1 也能取消任意轮次的闹钟
                alarmManager.cancel(buildRepeatIntent(app, 1));
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel repeat alarm failed", e);
        }
    }

    private static PendingIntent buildRepeatIntent(Context app, int round) { // modified: 新增
        Intent intent = new Intent(app, RepeatAlarmReceiver.class);
        intent.setAction(ACTION_REPEAT);
        intent.putExtra(EXTRA_ROUND, round);
        return PendingIntent.getBroadcast(app, REQ_REPEAT, intent, pendingFlags());
    }

    /** Android 12+ 必须显式声明 PendingIntent 可变性。 */
    private static int pendingFlags() { // modified: 新增
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
