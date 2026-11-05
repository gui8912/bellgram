# -*- coding: utf-8 -*-
"""v4.2.0 批次 1（续）：前台服务幂等 / 音量守护 / 异步错误收尾 / 停止后撤通知。"""
import io, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "saiga", "find", "messagefinder")
changes = []


def read(p):
    with io.open(os.path.join(JAVA, p), encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with io.open(os.path.join(JAVA, p), "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def rep(name, s, old, new, tag, count=1):
    n = s.count(old)
    assert n == count, "%s :: %s 期望 %d 处，实际 %d 处" % (name, tag, count, n)
    changes.append((name, tag))
    return s.replace(old, new, count)


# ============================================================ 新增 AlarmVolumeGuard
guard = '''package com.saiga.find.messagefinder;

// [新增文件 / modified] v4.2：闹钟音量的跨进程守护。
//
// 背景（独立复审发现）：服务播放前会把系统 STREAM_ALARM 音量临时提到最大，
// 原实现只把「原音量」存在服务的实例字段里，靠 onDestroy() 恢复。
// 但进程被低内存回收 / 用户强杀 / 关机时 onDestroy() 根本不执行，
// 而 setStreamVolume() 改的是**持久化**的系统设置 —— 用户手机的闹钟音量
// 会被永久钉在最大，且应用侧再无恢复入口。这是对用户设备的持久性副作用。
//
// 做法：提升前把原音量同步落盘；恢复成功后立刻清除标记；
// 应用下次任意组件启动时先检查残留标记并恢复一次。
// 未复制第三方代码。

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

final class AlarmVolumeGuard {

    private static final String TAG = "AlarmVolumeGuard"; // modified: 新增
    private static final String PREF_NAME = "alarm_volume_guard_v4"; // modified: 新增
    private static final String KEY_SAVED = "saved_alarm_volume"; // modified: 新增
    private static final int NO_VALUE = -1; // modified: 新增

    private AlarmVolumeGuard() { // modified: 新增
        // 工具类不允许实例化
    }

    private static SharedPreferences prefs(Context context) { // modified: 新增
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 提升音量前调用：同步落盘，保证进程被杀后标记仍在。 */
    static void remember(Context context, int savedVolume) { // modified: 新增
        try {
            prefs(context).edit().putInt(KEY_SAVED, Math.max(0, savedVolume)).commit();
        } catch (Exception e) {
            Log.w(TAG, "remember alarm volume failed", e);
        }
    }

    /** 恢复成功后调用，清除标记。 */
    static void clear(Context context) { // modified: 新增
        try {
            prefs(context).edit().remove(KEY_SAVED).commit();
        } catch (Exception e) {
            Log.w(TAG, "clear alarm volume mark failed", e);
        }
    }

    /**
     * 若上一次播放没走完正常收尾（进程被杀），立刻把系统闹钟音量还原。
     * 在 MainActivity.onCreate 与 SMSForegroundService.onCreate 都调用，双保险。
     */
    static void restoreIfStale(Context context) { // modified: 新增
        SharedPreferences p;
        int saved;
        try {
            p = prefs(context);
            saved = p.getInt(KEY_SAVED, NO_VALUE);
        } catch (Exception e) {
            return;
        }
        if (saved == NO_VALUE) {
            return;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0);
                Log.i(TAG, "restored alarm volume left by killed process: " + saved);
            }
        } catch (Exception e) {
            Log.w(TAG, "restore stale alarm volume failed", e);
        } finally {
            // modified: 无论恢复成功与否都清标记，避免每次启动都写一次系统设置
            try {
                p.edit().remove(KEY_SAVED).commit();
            } catch (Exception ignored) {
                // 已尽力恢复，忽略清除失败
            }
        }
    }
}
'''
write("AlarmVolumeGuard.java", guard)
changes.append(("AlarmVolumeGuard.java", "新增音量守护类"))

# ============================================================ 新增 BootReceiver
boot = '''package com.saiga.find.messagefinder;

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
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        // modified: MY_PACKAGE_REPLACED 同样会清掉本应用的闹钟（覆盖安装 / 应用更新）
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
            AlertSession session = AlertSession.get(app);
            if (!session.isActive()) {
                Log.d(TAG, "no active alert session, nothing to restore");
                return;
            }
            // modified: 没有持久化「下一次触发时刻」，无法精确补算剩余时间；
            // 直接补发下一轮通知（内含闹铃音 + 全屏 Intent），再由调度器挂好后续闹钟。
            // onRepeatAlarm 内部会用实时配置与总轮次复核，超限则自动结束会话。
            int nextRound = session.getRound() + 1;
            AlertScheduler.cancel(app);
            AlertDispatcher.onRepeatAlarm(app, nextRound);
            Log.i(TAG, "restored pending alert after " + action + ", round=" + nextRound);
        } catch (Exception e) {
            // 广播接收器抛异常会弹「应用已停止运行」
            Log.e(TAG, "restore alert session failed", e);
        }
    }
}
'''
write("BootReceiver.java", boot)
changes.append(("BootReceiver.java", "新增开机恢复接收器"))

# ============================================================ SMSForegroundService
n = "SMSForegroundService.java"
s = read(n)

s = rep(n, s,
'''    private static final int FALLBACK_SNOOZE_MINUTES = 5;
''',
'''    private static final int FALLBACK_SNOOZE_MINUTES = 5;

    /** modified: 【v4.2】服务存活标志，供 AlertDispatcher 判断是否需要发 STOP action。 */
    private static volatile boolean sRunning = false;
    /** modified: 【v4.2】本轮是否正在播放，用于拒绝同轮重复 START_ALERT。 */
    public static boolean isRunning() {
        return sRunning;
    }
''', "服务存活标志")

s = rep(n, s,
'''        alertHandler = new Handler(Looper.getMainLooper());
        NotificationHelper.ensureChannels(this);
    }''',
'''        alertHandler = new Handler(Looper.getMainLooper());
        NotificationHelper.ensureChannels(this);
        sRunning = true; // modified: 【v4.2】登记存活
        // modified: 上次进程被杀时可能把系统闹钟音量永久改成了最大，先还原
        AlarmVolumeGuard.restoreIfStale(this);
    }''', "onCreate 登记与音量恢复")

s = rep(n, s,
'''    private void handleStartAlert(Intent intent) {
        AlertSession session = AlertSession.get(this);''',
'''    private void handleStartAlert(Intent intent) {
        // modified: 【v4.2 独立复审】同一轮的 START_ALERT 重复到达会取消本轮定时器、
        // reset MediaPlayer、从头播放（用户反复点通知 / FSI 重复投递时本轮永远响不完）。
        // 这里做本轮幂等，配合 AlertActivity.onNewIntent 的轮次去重形成双保险。
        if (alertActive) {
            Log.d(TAG, "alert round already playing, ignore duplicate START");
            return;
        }
        AlertSession session = AlertSession.get(this);''', "播放幂等")

s = rep(n, s,
'''                    if (!volumeBoosted) {
                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                        volumeBoosted = true;
                    }''',
'''                    if (!volumeBoosted) {
                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                        volumeBoosted = true;
                        // modified: 【v4.2】同步落盘，进程被杀后仍可恢复（onDestroy 不一定执行）
                        AlarmVolumeGuard.remember(this, savedAlarmVolume);
                    }''', "音量提升落盘")

s = rep(n, s,
'''                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "media player error, what=" + what + ", extra=" + extra);
                    return true;
                }''',
'''                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "media player error, what=" + what + ", extra=" + extra);
                    // modified: 【v4.2 独立复审】返回 true 表示「应用自行处理」，
                    // 原实现除打日志外什么都没做：MediaPlayer 进入 Error 状态、
                    // setLooping 失效，本轮彻底静音，服务却继续占前台直到超时。
                    // 现在显式收尾并补一声通知音，至少让用户知道有事发生。
                    ringWindowOpen = false;
                    stopRingAndVibration();
                    releasePlayer();
                    restoreAlarmVolume();
                    fallbackToNotificationSound();
                    return true;
                }''', "onError 收尾降级")

s = rep(n, s,
'''        } catch (Exception e) {
            Log.e(TAG, "prepare alarm playback failed", e);
            releasePlayer();
            return false;
        }''',
'''        } catch (Exception e) {
            Log.e(TAG, "prepare alarm playback failed", e);
            releasePlayer();
            // modified: 【v4.2】音量可能已经提升成功，失败分支必须立刻归还，
            // 否则「一直响到手动停止」配置下系统闹钟音量会被钉在最大。
            restoreAlarmVolume();
            return false;
        }''', "播放失败恢复音量")

s = rep(n, s,
'''        } finally {
            volumeBoosted = false;
            savedAlarmVolume = -1;
        }
    }''',
'''        } finally {
            volumeBoosted = false;
            savedAlarmVolume = -1;
            AlarmVolumeGuard.clear(this); // modified: 【v4.2】正常收尾，清除跨进程残留标记
        }
    }''', "恢复音量清标记")

s = rep(n, s,
'''        releasePlayer();
        releaseWakeLock();
        stopSelfSafely(false);
    }

    /** 铃声播放失败时''',
'''        releasePlayer();
        releaseWakeLock();
        // modified: 【v4.2 独立复审】原实现用 DETACH，官方语义是「服务销毁后通知仍保留」。
        // AlertDispatcher.stop() 先 cancel 通知再请求停止，服务随后 DETACH 会把
        // 一条仍带「停止 / 稍后提醒」按钮的通知重新留在通知栏，用户点了停止却没停干净。
        // 本地停止属于「用户明确结束」，应当 REMOVE；endRound 的等待通知仍用 DETACH。
        stopSelfSafely(true);
    }

    /** 铃声播放失败时''', "本地停止撤通知")

s = rep(n, s,
'''        releaseWakeLock();
        Log.d(TAG, "alert service destroyed");
    }''',
'''        releaseWakeLock();
        sRunning = false; // modified: 【v4.2】注销存活标志
        Log.d(TAG, "alert service destroyed");
    }''', "onDestroy 注销标志")
write(n, s)

# ============================================================ AndroidManifest
mp = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
with io.open(mp, encoding="utf-8") as f:
    m = f.read()

old = '''    <!-- modified: 全屏告警。Android 14+ 默认可能未授予，必须检测 + 引导用户手动开启 -->
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />'''
new = '''    <!-- modified: 全屏告警。Android 14+ 默认可能未授予，必须检测 + 引导用户手动开启 -->
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <!-- modified: 【v4.2】系统重启会清空 AlarmManager 闹钟，需要开机广播重新注册，
         否则「一直提醒到处理」的会话在重启后永久静默失效。normal 级权限，安装即授予。 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />'''
assert m.count(old) == 1
m = m.replace(old, new)
changes.append(("AndroidManifest.xml", "开机权限"))

old = '''            android:showOnLockScreen="true"
            android:showWhenLocked="true"'''
new = '''            android:showWhenLocked="true"'''
assert m.count(old) == 1
m = m.replace(old, new)
changes.append(("AndroidManifest.xml", "移除废弃属性"))

old = '''        <receiver
            android:name=".SMSReceiver"'''
new = '''        <!-- modified: 【v4.2】重启 / 覆盖安装后恢复未处理完的提醒会话。
             exported=true 是因为这两个都是系统广播（发送方不是本应用）。 -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".SMSReceiver"'''
assert m.count(old) == 1
m = m.replace(old, new)
changes.append(("AndroidManifest.xml", "注册 BootReceiver"))

with io.open(mp, "w", encoding="utf-8", newline="\n") as f:
    f.write(m)

print("批次 1（续）完成，共 %d 处" % len(changes))
for name, tag in changes:
    print("  %-34s %s" % (name, tag))
