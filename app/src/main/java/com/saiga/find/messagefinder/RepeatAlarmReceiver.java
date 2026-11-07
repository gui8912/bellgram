package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：精确闹钟到点后的入口。
// 这里只做「重新发一条高优先级通知」，绝不启动前台服务——因为广播接收器处于后台，
// targetSdk 31+ 从后台 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。
// 通知会带全屏 Intent，锁屏/息屏时由系统拉起 AlertActivity；Activity 处于用户可见状态，
// 此时再由它启动前台服务播放铃声才符合系统豁免规则。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RepeatAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "RepeatAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null) {
            return;
        }
        try {
            String action = intent.getAction();
            if (AlertScheduler.ACTION_REPEAT.equals(action)) {
                int round = Math.max(1, intent.getIntExtra(AlertScheduler.EXTRA_ROUND, 1));
                AlertDispatcher.onRepeatAlarm(context, round);
            } else if (AlertScheduler.ACTION_ROUND_FALLBACK.equals(action)) { // 新增 v4.5.9
                handleRoundFallback(context, intent); // 新增 v4.5.9
            }
        } catch (Exception e) {
            // 广播接收器里的异常会直接弹「应用已停止运行」
            Log.e(TAG, "repeat alarm handling failed", e);
        }
    }

    /**
     * 【新增 v4.5.9】本轮响铃兜底。
     *
     * <p>全屏 Intent 只在锁屏/息屏时由系统拉起告警页；亮屏且用户正在交互时会被降级成
     * 悬挂通知，Android 14+ 未授予「全屏通知」特殊权限时也一样。告警页没起来 →
     * {@code SMSForegroundService} 不启动 → 60 秒持续响铃缺失，只剩通知渠道那一声。
     *
     * <p>由 AlarmManager 的 setAlarmClock 发起，属于系统发起的启动，不受
     * 「后台启动 Activity」限制；Activity 可见后再启动前台服务，仍符合 Android 12+ 豁免。
     * 只在「会话仍有效 + 轮次未变 + 服务确实没在跑」时拉页，全屏 Intent 已成功时直接跳过。
     */
    private void handleRoundFallback(Context context, Intent intent) { // 新增 v4.5.9
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        int round = Math.max(1, intent.getIntExtra(AlertScheduler.EXTRA_ROUND, 1));
        AlertSession session = AlertSession.get(app);
        if (!session.isActive() || session.getRound() != round) {
            Log.d(TAG, "round fallback skipped: session inactive or round changed");
            return;
        }
        if (SMSForegroundService.isRunning()) {
            Log.d(TAG, "round fallback skipped: alert service already playing");
            SmsTrace.add(app, "本轮兜底跳过", "全屏页已拉起，播放服务正在运行");
            return;
        }
        try {
            Intent alert = new Intent(app, AlertActivity.class);
            alert.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            NotificationHelper.putAlertExtras(alert, session.getFrom(), session.getKeyword(),
                    session.getPerson(), session.getBody());
            app.startActivity(alert);
            SmsTrace.add(app, "兜底拉起告警页",
                    "全屏 Intent 未生效，改由系统闹钟启动本轮持续响铃");
            Log.d(TAG, "round fallback started alert activity, round=" + round);
        } catch (Exception e) {
            Log.e(TAG, "round fallback start activity failed", e);
            SmsTrace.skip(app, "兜底拉起失败",
                    e + "；请开启「特殊应用权限 → 全屏通知」");
            // 不比现状更差：至少再补一声通知音，不让本轮彻底静默
            try {
                NotificationHelper.notifyDirectly(app, session.getFrom(), session.getKeyword(),
                        session.getPerson(), session.getBody());
            } catch (Exception ignored) {
                Log.w(TAG, "fallback notification sound also failed");
            }
        }
    }
}
