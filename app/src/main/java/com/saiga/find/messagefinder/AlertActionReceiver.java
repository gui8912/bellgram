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

    // modified: 稍后提醒时长统一由 AppConfig.getSnoozeMinutes() 托管

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
                // modified: 与告警页 / 服务保持一致，使用独立的贪睡时长设置
                int minutes = AppConfig.get(context).getSnoozeMinutes(); // modified
                AlertDispatcher.snooze(context, minutes);
            } else {
                Log.w(TAG, "unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle notification action failed", e);
        }
    }
}
