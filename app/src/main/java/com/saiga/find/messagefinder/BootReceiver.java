package com.saiga.find.messagefinder;

// [新增文件 / modified] v4.2：设备重启 / 应用更新后恢复未处理完的提醒会话。
//
// 独立复审发现的确定性缺口：重复提醒完全依赖 AlarmManager.setAlarmClock()，
// 而系统重启会清空所有闹钟。会话状态（active/round/内容）虽然落了盘，
// 但没有任何组件在开机后被唤醒去重新注册，也没有记录「下一次该什么时候响」。
// 结果是：用户设了「一直提醒到处理」，中途重启手机后提醒彻底静默失效，
// 且 active=true 会一直残留到下一条新短信才被覆盖。
//
// 做法参考 Android 官方《Schedule alarms / 重启后重新注册闹钟》与系统闹钟
// (DeskClock) 的 BOOT_COMPLETED 处理思路：开机后读持久化状态并重建闹钟。
// 只借鉴做法，未复制第三方代码。
//
// 注意：这里**只发通知**（沿用现有主链路），不启动前台服务 ——
// targetSdk 31+ 对后台启动 FGS 有限制；通知的全屏 Intent 会按既有路径拉起告警页。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver { // modified: 新增

    private static final String TAG = "BootReceiver"; // modified: 新增

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null) { // modified
            return;
        }
        String action = intent.getAction(); // modified
        // modified: MY_PACKAGE_REPLACED 同样会清掉本应用的闹钟（覆盖安装 / 应用更新）
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) // modified
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { // modified
            return;
        }
        try { // modified
            Context app = context.getApplicationContext() != null // modified
                    ? context.getApplicationContext() : context; // modified
            AlertSession session = AlertSession.get(app); // modified
            if (!session.isActive()) { // modified
                Log.d(TAG, "no active alert session, nothing to restore"); // modified
                return;
            }
            // modified: 没有持久化「下一次触发时刻」，无法精确补算剩余时间；
            // 直接补发下一轮通知（内含闹铃音 + 全屏 Intent），再由调度器挂好后续闹钟。
            // onRepeatAlarm 内部会用实时配置与总轮次复核，超限则自动结束会话。
            int nextRound = session.getRound() + 1; // modified
            AlertScheduler.cancel(app); // modified
            AlertDispatcher.onRepeatAlarm(app, nextRound); // modified
            Log.i(TAG, "restored pending alert after " + action + ", round=" + nextRound); // modified
        } catch (Exception e) { // modified
            // 广播接收器抛异常会弹「应用已停止运行」
            Log.e(TAG, "restore alert session failed", e); // modified
        }
    }
}
