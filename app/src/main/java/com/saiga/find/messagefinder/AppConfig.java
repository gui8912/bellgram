package com.saiga.find.messagefinder;

// [新增文件] 配置读写集中入口。
// 解决问题：
//  1) 键名 "contact"/"message"/"contact_name" 原先散落在 MainActivity / SMSReceiver / SettingsActivity 三处，
//     改一处漏两处，属于典型的“字符串契约”隐患；
//  2) 原代码统一用 (HashSet<String>) prefs.getStringSet(...) 强转，
//     Android 各版本/OEM 返回的实现类不保证是 HashSet，存在 ClassCastException 崩溃风险；
//  3) 原代码把 SharedPreferences 存成 static 字段，持有 Activity 引用，存在内存泄漏与生命周期错配风险。

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用配置（联系人号码 + 关键词集合 + 联系人姓名）与自动联想开关的统一读写门面。
 *
 * <p>注意：为兼容已安装用户的旧数据，这里继续使用 {@link PreferenceManager#getDefaultSharedPreferences}
 * 与原有键名，不迁移、不重命名存储位置。
 *
 * <p>v4 合规改造：由已废弃的 {@code android.preference.PreferenceManager} 换到
 * {@code androidx.preference.PreferenceManager}。两者默认文件名都是
 * {@code <包名>_preferences}，因此老用户的号码 / 关键词 / 全部提醒策略都不会丢。
 */
public final class AppConfig {

    // ---- 短信配置键（沿用旧键名，保证升级后配置不丢）----
    private static final String KEY_CONTACT = "contact";
    private static final String KEY_KEYWORDS = "message";
    private static final String KEY_CONTACT_NAME = "contact_name";

    // ---- 自动联想开关（旧实现使用名为 "Settings" 的独立 preference 文件）----
    private static final String FEATURE_PREF_NAME = "Settings";
    private static final String KEY_AUTO_SUGGESTION_DISABLED = "autoSuggDisabled";

    // ---- 二次开发新增：提醒方式与重复策略（键名与 app_preferences.xml 严格一致）----
    /** 是否响铃。 */
    private static final String KEY_RING_ENABLED = "ring_enabled";
    /** 是否震动。 */
    private static final String KEY_VIBRATE_ENABLED = "vibrate_enabled";
    /** 单次响铃时长（秒），"0" 表示一直响到用户手动停止。 */
    private static final String KEY_RING_DURATION = "ring_duration";
    /** 重复提醒间隔（分钟），"0" 表示不重复。 */
    private static final String KEY_REPEAT_INTERVAL = "repeat_interval";
    /** 最多重复次数，"0" 表示不限（直到用户处理）。 */
    private static final String KEY_MAX_REPEAT = "max_repeat";

    /** 默认响铃时长：60 秒（一次闹铃级别的提醒，足够叫醒但不会无限扰民）。 */
    private static final int DEFAULT_RING_DURATION_SEC = 60;
    /** 默认重复间隔：5 分钟（与系统闹钟的“贪睡”一致）。 */
    private static final int DEFAULT_REPEAT_INTERVAL_MIN = 5;
    /** 默认最多重复 3 次。 */
    private static final int DEFAULT_MAX_REPEAT = 3;

    // ---- 二次开发新增（借鉴短信铃铛）：匹配模式与夜间静音 ----
    /** 是否要求“全部关键词都出现”。false = 任意一个出现即命中（与原版语义一致）。 */
    private static final String KEY_MATCH_ALL = "match_all";
    /** 夜间静音时段，取值为 "off" 或 "起始小时-结束小时"，例如 "22-7"。 */
    private static final String KEY_QUIET_HOURS = "quiet_hours";
    /** 夜间静音默认关闭（不改动用户既有体验）。 */
    private static final String DEFAULT_QUIET_HOURS = "off";

    private final SharedPreferences configPrefs;
    private final SharedPreferences featurePrefs;

    private AppConfig(SharedPreferences configPrefs, SharedPreferences featurePrefs) {
        this.configPrefs = configPrefs;
        this.featurePrefs = featurePrefs;
    }

    /**
     * 获取配置门面实例。使用 ApplicationContext，避免持有 Activity 造成泄漏。
     *
     * @param context 任意 Context
     * @return 配置门面
     */
    public static AppConfig get(Context context) { // modified: 用 ApplicationContext 取代原来的 static Activity 引用
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new AppConfig(
                PreferenceManager.getDefaultSharedPreferences(appContext),
                appContext.getSharedPreferences(FEATURE_PREF_NAME, Context.MODE_PRIVATE));
    }

    /**
     * @return 用户配置的号码，未配置时返回空串（永不为 null，调用方无需判空）
     */
    public String getContact() { // modified: null -> 空串，消除调用侧 NPE
        String value = configPrefs.getString(KEY_CONTACT, null);
        return value == null ? "" : value.trim();
    }

    /**
     * @return 用户配置的联系人姓名，未配置时返回空串
     */
    public String getContactName() {
        String value = configPrefs.getString(KEY_CONTACT_NAME, null);
        return value == null ? "" : value.trim();
    }

    /**
     * @return 关键词集合的只读副本，未配置时返回空集合（永不为 null）
     */
    public Set<String> getKeywords() { // modified: 不再强转 HashSet，改用 Set 接口 + 不可变副本
        Set<String> stored;
        try {
            stored = configPrefs.getStringSet(KEY_KEYWORDS, null);
        } catch (ClassCastException e) {
            // 极端情况：键被其它版本写成了非字符串集合类型，按“未配置”处理，避免崩溃
            stored = null;
        }
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(stored));
    }

    /**
     * @return 是否已经配置过关键词（关键词为空即视为未配置，与原有“未配置就不监听”的语义一致）
     */
    public boolean isConfigured() {
        return !getKeywords().isEmpty();
    }

    /**
     * 保存短信配置。
     *
     * @param contact  号码，空串表示不限号码
     * @param keywords 关键词列表，不可为空
     */
    public void save(String contact, List<String> keywords) { // modified: 统一写入入口，空集合不再写入 null
        SharedPreferences.Editor editor = configPrefs.edit();
        String trimmedContact = contact == null ? "" : contact.trim();
        if (trimmedContact.isEmpty()) {
            editor.remove(KEY_CONTACT);
        } else {
            editor.putString(KEY_CONTACT, trimmedContact);
        }
        if (keywords == null || keywords.isEmpty()) {
            editor.remove(KEY_KEYWORDS);
        } else {
            // 必须传入新的 Set：SharedPreferences 不会复制入参，直接传会导致后续 set 同时被修改
            editor.putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(keywords));
        }
        editor.apply();
    }

    /**
     * 保存联系人姓名（用于通知与设置页展示）。
     *
     * @param contactName 联系人姓名，可为空
     */
    public void saveContactName(String contactName) {
        SharedPreferences.Editor editor = configPrefs.edit();
        if (contactName == null || contactName.trim().isEmpty()) {
            editor.remove(KEY_CONTACT_NAME);
        } else {
            editor.putString(KEY_CONTACT_NAME, contactName.trim());
        }
        editor.apply();
    }

    /**
     * 清空短信配置（保留自动联想开关与命中日志）。
     */
    public void clear() { // modified: 与“清除”按钮语义对齐，避免误清其它设置
        configPrefs.edit()
                .remove(KEY_CONTACT)
                .remove(KEY_KEYWORDS)
                .remove(KEY_CONTACT_NAME)
                .apply();
    }

    /**
     * @return 自动联想是否被用户关闭（默认 true，与旧版本默认行为一致）
     */
    public boolean isAutoSuggestionDisabled() {
        return featurePrefs.getBoolean(KEY_AUTO_SUGGESTION_DISABLED, true);
    }

    /**
     * 设置自动联想开关。
     *
     * @param disabled true 表示关闭自动联想（改用系统通讯录选择器）
     */
    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }

    // ==================== 二次开发新增：提醒方式与重复策略 ====================

    /**
     * @return 是否响铃（默认 true）
     */
    public boolean isRingEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_RING_ENABLED, true);
    }

    /**
     * @return 是否震动（默认 true）
     */
    public boolean isVibrateEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_VIBRATE_ENABLED, true);
    }

    /**
     * @return 单次响铃时长（秒）；0 表示一直响到用户手动停止
     */
    public int getRingDurationSec() { // modified: 新增
        return readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC);
    }

    /**
     * @return 重复提醒间隔（分钟）；0 表示不重复
     */
    public int getRepeatIntervalMin() { // modified: 新增
        int value = readInt(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL_MIN);
        return Math.max(0, Math.min(value, 60));
    }

    /**
     * @return 最多重复次数；0 表示不限（一直重复到用户处理）
     */
    public int getMaxRepeatCount() { // modified: 新增
        int value = readInt(KEY_MAX_REPEAT, DEFAULT_MAX_REPEAT);
        return Math.max(0, Math.min(value, 30));
    }

    /**
     * 计算一次提醒会话计划的总轮次。
     *
     * <p>v4 架构重构：这段逻辑原先写在 SMSForegroundService 里；由于重复轮次现在由
     * 精确闹钟驱动、服务每轮都会退出，轮次必须在「收到短信」时就确定并写进会话状态，
     * 因此上移到配置门面（纯计算，可单元测试）。
     *
     * @return 总轮次；0 表示不限次数，直到用户点「停止」
     */
    public int getTotalRounds() { // modified: 新增
        // modified: 规则抽到 RoundPolicy，供四个组件共用且可单元测试
        return RoundPolicy.totalRounds(getRingDurationSec(), getRepeatIntervalMin(),
                getMaxRepeatCount());
    }

    /**
     * 读取以字符串形式存储的整型偏好（ListPreference 存的是 String）。
     *
     * @param key          键名
     * @param defaultValue 解析失败时的兜底值
     * @return 整型值
     */
    private int readInt(String key, int defaultValue) {
        // modified: 【独立复审修正】原实现把 getString 放在 try 之外，
        // 一旦该键被写成 int/boolean（旧版本迁移、手工改 prefs、其它分支 putInt），
        // getString 自身就会抛 ClassCastException——而它是「收到短信」链路上的调用，
        // 异常会一路冒到广播接收器，表现为「配置里改过设置之后再也不提醒」。
        // 因此把类型不匹配的兜底提到最外层，任何异常都退化为默认值。
        String raw;
        try {
            raw = configPrefs.getString(key, null);
        } catch (ClassCastException e) {
            // 该键存的是非字符串类型，按整型再读一次；仍失败则用默认值
            try {
                return configPrefs.getInt(key, defaultValue);
            } catch (ClassCastException intError) {
                return defaultValue;
            }
        }
        if (raw == null || raw.trim().isEmpty()) {
            // 也可能是被写成了整型（例如从旧版本迁移过来），尝试整型读取
            try {
                return configPrefs.getInt(key, defaultValue);
            } catch (ClassCastException e) {
                return defaultValue;
            }
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 二次开发新增：匹配模式与夜间静音（借鉴短信铃铛） ====================

    /**
     * @return 是否要求“全部关键词都出现”才触发（默认 false，与原版“任意命中”一致）
     */
    public boolean isMatchAllEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_MATCH_ALL, false);
    }

    /**
     * @return 夜间静音起始小时；-1 表示未启用
     */
    public int getQuietStartHour() { // modified: 新增
        return parseQuietHour(true);
    }

    /**
     * @return 夜间静音结束小时；-1 表示未启用
     */
    public int getQuietEndHour() { // modified: 新增
        return parseQuietHour(false);
    }

    /**
     * @return 当前时刻是否处于夜间静音时段（此时只震动、不响铃）
     */
    public boolean isInQuietHours() { // modified: 新增
        int start = getQuietStartHour();
        int end = getQuietEndHour();
        if (start < 0 || end < 0) {
            return false;
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return QuietHours.isActive(start, end, hour);
    }

    /**
     * 解析夜间静音时段的配置值。
     *
     * <p>取值形如 "off" 或 "22-7"（起始小时-结束小时）。任何无法解析的值都按“未启用”处理，
     * 避免脏配置把用户手机变成“永远不响铃”。
     *
     * @param start true 取起始小时，false 取结束小时
     * @return 小时值 0-23；未启用或解析失败返回 -1
     */
    private int parseQuietHour(boolean start) { // modified: 新增
        String raw = configPrefs.getString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS);
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (value.isEmpty() || "off".equalsIgnoreCase(value)) {
            return -1;
        }
        String[] parts = value.split("-");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[start ? 0 : 1].trim());
            return QuietHours.normalizeHour(hour);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
