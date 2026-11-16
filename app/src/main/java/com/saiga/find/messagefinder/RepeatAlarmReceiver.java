package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：精确闹钟到点后的入口。
// 这里只做「重新发一条高优先级通知」，绝不启动前台服务——因为广播接收器处于后台，
// targetSdk 31+ 从后台 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。
// 通知会带全屏 Intent，锁屏/息屏时由系统拉起 AlertActivity；Activity 处于用户可见状态，
// 此时再由它启动前台服务播放铃声才符合系统豁免规则。
//
// 【修改 v4.6.6】唯一的例外是 ACTION_ROUND_FALLBACK：它由 setAlarmClock 触发，
// 属于 Android 12+ 后台启动限制里的「精确闹钟」豁免，可以直接启动前台服务。
// 旧实现只拉起告警页，亮屏且别的 App 在前台时后台启动 Activity 会被静默拦截，
// 结果整轮只有一条静音通知、没有铃声；现在先起播放服务，再尽力拉告警页。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build; // 新增 v4.6.6
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
        // 【新增 v4.6.6】兜底改为「先直接启动播放服务，再尽力拉起告警页」。
        // 依据：Android 12+ 的后台启动限制里，「应用调用精确闹钟完成用户请求的动作」
        // 是官方豁免项；本兜底正是由 setAlarmClock 触发的，因此可以直接
        // startForegroundService，不必等 Activity 可见。
        // 旧实现只 startActivity：亮屏且别的 App 在前台时，后台启动 Activity 会被
        // 系统静默拦截（startActivity 不抛异常），结果是整轮只有一条静音通知、没有铃声。
        boolean serviceStarted = false;
        try {
            Intent service = new Intent(app, SMSForegroundService.class);
            service.setAction(SMSForegroundService.ACTION_START_ALERT);
            NotificationHelper.putAlertExtras(service, session.getFrom(), session.getKeyword(),
                    session.getPerson(), session.getBody());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(service);
            } else {
                app.startService(service);
            }
            serviceStarted = true;
            SmsTrace.add(app, "兜底启动播放服务",
                    "精确闹钟豁免，直接启动本轮响铃，不依赖告警页是否可见");
        } catch (Exception e) {
            Log.e(TAG, "round fallback start service failed", e);
            SmsTrace.skip(app, "兜底启动播放失败",
                    e + "；请检查通知权限与电池优化设置");
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
            if (!serviceStarted) {
                // 服务也没起来时才补通知音，避免双重响铃
                try {
                    NotificationHelper.notifyDirectly(app, session.getFrom(), session.getKeyword(),
                            session.getPerson(), session.getBody());
                } catch (Exception ignored) {
                    Log.w(TAG, "fallback notification sound also failed");
                }
            }
        }
    }
}
