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
import android.os.Build; // 新增 v4.6.5
import android.os.UserManager; // 新增 v4.6.5
import android.util.Log;

public class BootReceiver extends BroadcastReceiver { // modified: 新增

    private static final String TAG = "BootReceiver"; // modified: 新增

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null) { // modified
            return;
        }
        String action = intent.getAction(); // modified
        // 【新增 v4.6.5】direct boot：首次解锁前会先收到 LOCKED_BOOT_COMPLETED。
        // 此时凭据加密存储（本应用的 SharedPreferences）不可用，所以这条分支只做
        // 「不读配置」的事——拉起 GuardService（只用资源与通知），其余留到解锁后。
        final boolean lockedBoot = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action); // 新增 v4.6.5
        // modified: MY_PACKAGE_REPLACED 同样会清掉本应用的闹钟（覆盖安装 / 应用更新）
        final boolean bootOrUpdate = Intent.ACTION_BOOT_COMPLETED.equals(action) // modified
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) // modified
                || lockedBoot; // 新增 v4.6.5
        // modified: v4.5.0 补「时间/时区被修改」。setAlarmClock 用的是 RTC 绝对时刻，
        // 用户手动改系统时间会让排队中的那一轮提前或推迟（时区变更只影响显示，不影响绝对时刻）。
        // 参考 NeoAlarm / RecurringReminders 的做法：这两类广播到来时只「重新挂好下一轮」，
        // 不像开机那样立刻补响一轮 —— 用户改时钟并不是在要求提醒。
        final boolean clockChanged = Intent.ACTION_TIME_CHANGED.equals(action) // modified
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action); // modified
        if (!bootOrUpdate && !clockChanged) { // modified
            return;
        }
        try { // modified
            Context app = context.getApplicationContext() != null // modified
                    ? context.getApplicationContext() : context; // modified
            // 【新增 v4.6.5】先拉起守护、再注册兜底任务——这两步不读凭据加密存储，
            // 在 LOCKED_BOOT_COMPLETED（首次解锁前）也能执行。
            GuardService.start(app); // 新增 v4.6.5
            BootFallbackScheduler.ensureScheduled(app); // 新增 v4.6.5
            if (lockedBoot || !isUserUnlocked(app)) { // 新增 v4.6.5
                Log.i(TAG, "locked boot: guard started, skip credential storage"); // 新增 v4.6.5
                return; // 新增 v4.6.5
            } // 新增 v4.6.5
            // modified: 重启 / 覆盖安装后用户可能还没打开 App，这里补一次音量还原
            AlarmVolumeGuard.restoreIfStale(app); // modified
            // 【新增 v4.6.3】无论后面有没有待恢复的会话，都记录「开机广播到达」。
            // 以前这条路径在会话不活跃时一个字都不写，导致「重启后收不到短信」
            // 无法判断是系统没投递开机广播，还是投递了但短信广播被拦。
            SmsTrace.add(app, "开机广播已到达", "action=" + action); // 新增 v4.6.3
            // 【新增 v4.6.5】标记「本次开机已收到 BOOT_COMPLETED」，供首页自启异常提示使用。
            // 只在真正的 BOOT_COMPLETED 写；MY_PACKAGE_REPLACED 不是开机，不能算。
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) { // 新增 v4.6.5
                SmsTrace.recordBootCompleted(app); // 新增 v4.6.5
            } // 新增 v4.6.5
            AlertSession session = AlertSession.get(app); // modified
            if (!session.isActive()) { // modified
                Log.d(TAG, "no active alert session, nothing to restore"); // modified
                return;
            }
            if (clockChanged) { // modified: v4.5.0 改时钟只重挂闹钟，不立即补响
                int interval = AppConfig.get(app).getRepeatIntervalMin(); // modified
                AlertScheduler.cancel(app); // modified
                AlertScheduler.scheduleNext(app, Math.max(1, interval), session.getRound() + 1); // modified
                Log.i(TAG, "re-armed alert after " + action + ", in " + Math.max(1, interval) + " min"); // modified
                return; // modified
            } // modified
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

    /**
     * 【新增 v4.6.5】判断当前用户是否已解锁（凭据加密存储是否可用）。
     *
     * <p>API 24 以下没有 direct boot，恒为 true。任何异常都按「已解锁」处理：
     * 宁可多做一次配置操作，也不能因此挡住开机恢复。
     */
    private static boolean isUserUnlocked(Context context) { // 新增 v4.6.5
        try { // 新增 v4.6.5
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // 新增 v4.6.5
                UserManager manager = (UserManager) context.getSystemService(Context.USER_SERVICE); // 新增 v4.6.5
                return manager == null || manager.isUserUnlocked(); // 新增 v4.6.5
            } // 新增 v4.6.5
            return true; // 新增 v4.6.5
        } catch (Exception e) { // 新增 v4.6.5
            Log.w(TAG, "isUserUnlocked failed, assume unlocked", e); // 新增 v4.6.5
            return true; // 新增 v4.6.5
        } // 新增 v4.6.5
    } // 新增 v4.6.5
}
