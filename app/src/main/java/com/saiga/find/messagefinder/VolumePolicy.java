package com.saiga.find.messagefinder;

// modified: v4.3.0 新增 —— 闹钟音量档位的纯 Java 规则，抽出来便于单元测试。
// 参考系统时钟的做法：音量是「闹钟流最大值的百分比」，0 表示跟随系统、完全不改系统设置。
// 注意：AudioManager.setStreamVolume 改的是持久化系统设置，因此只有确实写入时才允许
// AlarmVolumeGuard.remember()，否则会把用户原本的音量误回写一次。
public final class VolumePolicy { // modified

    /** 0 = 跟随系统（不调用 setStreamVolume）。 */
    public static final int FOLLOW_SYSTEM = 0; // modified

    /** 默认 80%：比原先强制 100% 柔和，仍明显强于普通短信提示音。 */
    public static final int DEFAULT_LEVEL = 80; // modified

    private static final int[] ALLOWED_LEVELS = {0, 40, 60, 80, 100}; // modified

    private VolumePolicy() { // modified
        // 工具类不允许实例化 // modified
    } // modified

    /** 白名单校验：脏配置、解析失败、未知档位一律回退默认值。 */
    public static int sanitize(int raw) { // modified
        for (int level : ALLOWED_LEVELS) { // modified
            if (level == raw) { // modified
                return level; // modified
            } // modified
        } // modified
        return DEFAULT_LEVEL; // modified
    } // modified

    /** 是否需要动系统闹钟音量。跟随系统时绝不提升，也就不需要恢复。 */
    public static boolean shouldChangeSystemVolume(int level) { // modified
        return sanitize(level) > FOLLOW_SYSTEM; // modified
    } // modified

    /** 百分比换算成 STREAM_ALARM 的索引，至少保留 1 格，避免把用户设备设成静音。 */
    public static int targetStreamIndex(int maxVolume, int level) { // modified
        int safe = sanitize(level); // modified
        if (maxVolume <= 0 || safe <= FOLLOW_SYSTEM) { // modified
            return 0; // modified
        } // modified
        long index = Math.round(maxVolume * (safe / 100.0d)); // modified
        return (int) Math.max(1, Math.min(index, maxVolume)); // modified
    } // modified

    /** 渐强音量（系统时钟 "Gradually increase volume"）的起始播放音量。 */
    public static float initialPlayerVolume(boolean crescendo) { // modified
        return crescendo ? 0.25f : 1.0f; // modified
    } // modified
} // modified
