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
            if (!AlertScheduler.ACTION_REPEAT.equals(intent.getAction())) {
                return;
            }
            int round = Math.max(1, intent.getIntExtra(AlertScheduler.EXTRA_ROUND, 1));
            AlertDispatcher.onRepeatAlarm(context, round);
        } catch (Exception e) {
            // 广播接收器里的异常会直接弹「应用已停止运行」
            Log.e(TAG, "repeat alarm handling failed", e);
        }
    }
}
