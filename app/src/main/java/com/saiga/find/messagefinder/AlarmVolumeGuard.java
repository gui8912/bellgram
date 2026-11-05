package com.saiga.find.messagefinder;

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

final class AlarmVolumeGuard { // modified

    private static final String TAG = "AlarmVolumeGuard"; // modified: 新增
    private static final String PREF_NAME = "alarm_volume_guard_v4"; // modified: 新增
    private static final String KEY_SAVED = "saved_alarm_volume"; // modified: 新增
    private static final int NO_VALUE = -1; // modified: 新增

    private AlarmVolumeGuard() { // modified: 新增
        // 工具类不允许实例化
    }

    private static SharedPreferences prefs(Context context) { // modified: 新增
        Context app = context.getApplicationContext() != null // modified
                ? context.getApplicationContext() : context; // modified
        return app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE); // modified
    }

    /** 提升音量前调用：同步落盘，保证进程被杀后标记仍在。 */
    static void remember(Context context, int savedVolume) { // modified: 新增
        try { // modified
            prefs(context).edit().putInt(KEY_SAVED, Math.max(0, savedVolume)).commit(); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "remember alarm volume failed", e); // modified
        }
    }

    /** 恢复成功后调用，清除标记。 */
    static void clear(Context context) { // modified: 新增
        try { // modified
            prefs(context).edit().remove(KEY_SAVED).commit(); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "clear alarm volume mark failed", e); // modified
        }
    }

    /**
     * 若上一次播放没走完正常收尾（进程被杀），立刻把系统闹钟音量还原。
     * 在 MainActivity.onCreate 与 SMSForegroundService.onCreate 都调用，双保险。
     */
    static void restoreIfStale(Context context) { // modified: 新增
        SharedPreferences p; // modified
        int saved; // modified
        try { // modified
            p = prefs(context); // modified
            saved = p.getInt(KEY_SAVED, NO_VALUE); // modified
        } catch (Exception e) { // modified
            return;
        }
        if (saved == NO_VALUE) { // modified
            return;
        }
        try { // modified
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); // modified
            if (am != null) { // modified
                am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0); // modified
                Log.i(TAG, "restored alarm volume left by killed process: " + saved); // modified
            }
        } catch (Exception e) { // modified
            Log.w(TAG, "restore stale alarm volume failed", e); // modified
        } finally { // modified
            // modified: 无论恢复成功与否都清标记，避免每次启动都写一次系统设置
            try { // modified
                p.edit().remove(KEY_SAVED).commit(); // modified
            } catch (Exception ignored) { // modified
                // 已尽力恢复，忽略清除失败
            }
        }
    }
}
