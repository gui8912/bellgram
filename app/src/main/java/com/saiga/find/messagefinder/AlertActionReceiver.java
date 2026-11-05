package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 合规改造：通知上的「停止 / 稍后提醒」改为广播入口。
// 原实现用 PendingIntent.getService 直接启动服务；targetSdk 31+ 从后台（通知按钮属于后台）
// 调用 startService / startForegroundService 同样受后台启动限制，可能静默失败。
// 广播接收器不受该限制，由它统一改状态、取消闹钟，再尝试通知正在前台的服务停下来。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlertActionReceiver extends BroadcastReceiver {

    private static final String TAG = "AlertActionReceiver";
    public static final String ACTION_STOP = "com.saiga.find.messagefinder.action.STOP_ALERT"; // modified: 新增
    public static final String ACTION_SNOOZE = "com.saiga.find.messagefinder.action.SNOOZE_ALERT"; // modified: 新增

    /** 用户把重复间隔设为 0（不重复）时，点「稍后提醒」的兜底间隔。 */
    private static final int FALLBACK_SNOOZE_MINUTES = 5;

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        try {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                AlertDispatcher.stop(context);
            } else if (ACTION_SNOOZE.equals(action)) {
                int minutes = RoundPolicy.safeDelayMinutes(
                        AppConfig.get(context).getRepeatIntervalMin(),
                        FALLBACK_SNOOZE_MINUTES); // modified: 规则集中
                AlertDispatcher.snooze(context, minutes);
            } else {
                Log.w(TAG, "unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle notification action failed", e);
        }
    }
}
