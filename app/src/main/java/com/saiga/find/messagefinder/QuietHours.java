package com.saiga.find.messagefinder;

// [新增文件] 夜间静音时段（免打扰窗口）判定。
// 参照：短信铃铛的“全局静音时段（22:00 到 07:00，可开关），开启后这段时间只震动不响铃”。
// 为什么单独抽成一个纯 Java 类：
//  1) 时段判定涉及“跨零点”这种最容易写错的边界（22:00-07:00 不是 start<=h<=end），
//     放在 Service 里只能靠真机等半夜才能验证；
//  2) 抽出来之后可以用 JVM 单元测试把 0 点、起止点、全天、关闭等边界一次性覆盖。

/**
 * 夜间静音时段判定工具。
 *
 * <p>语义：在 [{@code startHour}, {@code endHour}) 区间内视为静音时段。
 * 当 {@code startHour > endHour} 时表示**跨零点**（例如 22 → 7 表示 22:00 到次日 07:00）。
 * {@code startHour == endHour} 视为“未启用”，任何时刻都不静音（避免“全天静音”这种致命配置）。
 */
public final class QuietHours {

    /** 表示“未启用静音时段”。 */
    public static final int DISABLED_START = -1;
    /** 表示“未启用静音时段”。 */
    public static final int DISABLED_END = -1;

    private QuietHours() {
        // 工具类不允许实例化
    }

    /**
     * 判断当前小时是否落在静音时段内。
     *
     * @param startHour 起始小时（含），0-23；与 endHour 同为 -1 表示未启用
     * @param endHour   结束小时（不含），0-23
     * @param hour      待判断的小时，0-23
     * @return true 表示此刻应静音（只震动不响铃）
     */
    public static boolean isActive(int startHour, int endHour, int hour) { // modified: 新增
        if (startHour < 0 || endHour < 0) {
            return false; // 未启用
        }
        if (startHour == endHour) {
            return false; // 起止相同视为未启用，避免“全天静音”
        }
        int safeHour = normalizeHour(hour);
        if (startHour < endHour) {
            // 同一天内：如 01:00 - 06:00
            return safeHour >= startHour && safeHour < endHour;
        }
        // 跨零点：如 22:00 - 07:00，落在 [22,24) 或 [0,7)
        return safeHour >= startHour || safeHour < endHour;
    }

    /**
     * 把任意小时值收敛到 0-23，防止脏数据导致判定异常。
     *
     * @param hour 原始小时
     * @return 0-23 之间的值
     */
    public static int normalizeHour(int hour) { // modified: 新增
        int value = hour % 24;
        return value < 0 ? value + 24 : value;
    }

    /**
     * 生成用于界面展示的时段文案，例如 {@code "22:00–07:00"}。
     *
     * @param startHour 起始小时
     * @param endHour   结束小时
     * @return 展示用文案；未启用时返回空串
     */
    public static String describe(int startHour, int endHour) { // modified: 新增
        if (startHour < 0 || endHour < 0 || startHour == endHour) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%02d:00–%02d:00", startHour, endHour);
    }
}
