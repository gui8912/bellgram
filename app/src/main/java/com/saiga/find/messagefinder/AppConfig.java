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
    /** modified: 【v4.2】引导完成的持久化标记，不依赖 PackageManager 组件禁用是否成功。 */
    private static final String KEY_ONBOARDING_DONE = "onboarding_done_v4"; // modified

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

    // ---- modified: v4.3 新增键，字面值必须与 app_preferences.xml 的 @string/*_key 一致 ----
    private static final String KEY_THEME_MODE = "theme_mode"; // modified
    private static final String KEY_ALARM_VOLUME = "alarm_volume_level"; // modified
    private static final String KEY_CRESCENDO_ENABLED = "crescendo_enabled"; // modified
    private static final String KEY_SNOOZE_MINUTES = "snooze_minutes"; // modified
    private static final String KEY_VOLUME_KEY_ACTION = "volume_key_action"; // modified
    /** 默认稍后提醒 5 分钟，与旧版写死的兜底值一致，升级后行为不变。 */
    private static final int DEFAULT_SNOOZE_MINUTES = 5; // modified
    public static final String ACTION_NONE = "none"; // modified
    public static final String ACTION_SNOOZE = "snooze"; // modified
    public static final String ACTION_STOP = "stop"; // modified
    // modified: v4.3 借鉴 SmsForwarder：提醒总开关 + 排除关键词
    private static final String KEY_ALERT_ENABLED = "alert_enabled"; // modified
    private static final String KEY_EXCLUDE_KEYWORDS = "exclude_keywords"; // modified

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

    // modified: 【v4.2 独立复审】readInt() 的注释已经写明「键被写成别的类型时
    // getString 会抛 ClassCastException，导致收到短信后再也不提醒」，但防护只覆盖了
    // 3 个 int 键；getContact / getContactName / isRingEnabled / isVibrateEnabled /
    // isMatchAllEnabled / parseQuietHour / 自动联想开关全是裸调用，同样在短信链路上。
    // 这里把同一防护抽成通用读取器，覆盖所有 getString / getBoolean。
    private String safeGetString(String key, String defaultValue) { // modified: 新增
        try { // modified
            return configPrefs.getString(key, defaultValue); // modified
        } catch (ClassCastException e) { // modified
            return defaultValue; // modified
        }
    }

    private boolean safeGetBoolean(String key, boolean defaultValue) { // modified: 新增
        return safeGetBoolean(configPrefs, key, defaultValue); // modified
    }

    private static boolean safeGetBoolean(SharedPreferences prefs, String key, // modified
                                          boolean defaultValue) { // modified: 新增
        try { // modified
            return prefs.getBoolean(key, defaultValue); // modified
        } catch (ClassCastException e) { // modified
            return defaultValue; // modified
        }
    }

    /**
     * @return 用户配置的号码，未配置时返回空串（永不为 null，调用方无需判空）
     */
    public String getContact() { // modified: null -> 空串，消除调用侧 NPE
        String value = safeGetString(KEY_CONTACT, null); // modified: 类型不匹配兜底
        return value == null ? "" : value.trim();
    }

    /**
     * @return 用户配置的联系人姓名，未配置时返回空串
     */
    public String getContactName() {
        String value = safeGetString(KEY_CONTACT_NAME, null); // modified: 类型不匹配兜底
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
            // modified: 【v4.2 独立复审】原实现 catch 后直接 stored = null，
            // 把「旧版本把 message 存成逗号分隔 String」当成「用户从未配置」，
            // 返回空集合后 SMSReceiver 直接丢弃每一条短信：关键词静默全丢、
            // 界面还显示「未配置」，用户没有任何线索。这里做一次性迁移。
            stored = migrateLegacyKeywords(); // modified
        }
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(stored));
    }

    /**
     * 把旧版以字符串保存的关键词迁移成 Set。
     *
     * @return 迁移后的集合；无法迁移返回 null（由调用方按「未配置」处理）
     */
    private Set<String> migrateLegacyKeywords() { // modified: v4.2 新增
        try { // modified
            String legacy = configPrefs.getString(KEY_KEYWORDS, null); // modified
            if (legacy == null || legacy.trim().isEmpty()) { // modified
                return null; // modified
            }
            List<String> parsed = KeywordMatcher.parseKeywords(legacy); // modified
            if (parsed.isEmpty()) { // modified
                return null; // modified
            }
            Set<String> migrated = new LinkedHashSet<>(parsed); // modified
            // modified: 用 commit 同步落盘，避免下次读仍触发迁移；传新 Set 避免污染缓存
            configPrefs.edit().putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(migrated)).commit(); // modified
            return migrated; // modified
        } catch (Exception e) { // modified
            // 键存的既不是 Set 也不是 String（例如 Integer）：确实无法恢复
            return null; // modified
        }
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
        return safeGetBoolean(featurePrefs, KEY_AUTO_SUGGESTION_DISABLED, true); // modified: 类型兜底
    }

    /**
     * 设置自动联想开关。
     *
     * @param disabled true 表示关闭自动联想（改用系统通讯录选择器）
     */
    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }

    /** @return 用户是否已经走完首次引导 */
    public boolean isOnboardingDone() { // modified: v4.2 新增
        return safeGetBoolean(featurePrefs, KEY_ONBOARDING_DONE, false); // modified
    }

    /**
     * 标记引导已完成。
     *
     * <p>modified: 【v4.2 独立复审】原实现只靠 setComponentEnabledSetting(DISABLED)
     * 判定引导是否结束，而该调用在受管设备 / 工作资料 / 部分 ROM 上会失败且被静默吞掉。
     * 一旦失败，MainActivity 每次冷启动都会再次拉起引导页，用户卡在「完成 -> 又回引导页」。
     * 这里补一个独立的持久化标记作为主判定，组件禁用降级为加固手段。
     */
    public void setOnboardingDone(boolean done) { // modified: v4.2 新增
        featurePrefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).commit(); // modified
    }

    // ==================== 二次开发新增：提醒方式与重复策略 ====================

    /**
     * @return 是否响铃（默认 true）
     */
    public boolean isRingEnabled() { // modified: 新增
        return safeGetBoolean(KEY_RING_ENABLED, true); // modified: 类型兜底
    }

    /**
     * @return 是否震动（默认 true）
     */
    public boolean isVibrateEnabled() { // modified: 新增
        return safeGetBoolean(KEY_VIBRATE_ENABLED, true); // modified: 类型兜底
    }

    /**
     * @return 单次响铃时长（秒）；0 表示一直响到用户手动停止
     */
    public int getRingDurationSec() { // modified: 新增
        // modified: 【v4.2】另两个 int 读取器都做了范围钳制，唯独这里没有。
        // 脏配置写入 999999 会让前台服务与 WakeLock 挂近 12 天；负值会立即结束本轮。
        // 与 arrays.xml 的合法值域 {0,30,60,120} 对齐，上限取 120。
        int value = readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC); // modified
        return Math.max(0, Math.min(value, 120)); // modified
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

    /** modified: v4.3 主题模式；脏值 / 未知值一律按「跟随系统」处理。 */
    public String getThemeMode() { // modified
        String value = safeGetString(KEY_THEME_MODE, ThemeHelper.MODE_SYSTEM); // modified
        if (ThemeHelper.MODE_LIGHT.equals(value) // modified
                || ThemeHelper.MODE_DARK.equals(value)) { // modified
            return value; // modified
        } // modified
        return ThemeHelper.MODE_SYSTEM; // modified
    } // modified

    /** modified: v4.3 闹钟音量档位（0=跟随系统，其余为百分比）；白名单校验交给 VolumePolicy。 */
    public int getAlarmVolumeLevel() { // modified
        return VolumePolicy.sanitize( // modified
                readInt(KEY_ALARM_VOLUME, VolumePolicy.DEFAULT_LEVEL)); // modified
    } // modified

    /** modified: v4.3 渐强音量开关；默认关，保持原有「一响就到位」的行为。 */
    public boolean isCrescendoEnabled() { // modified
        return safeGetBoolean(KEY_CRESCENDO_ENABLED, false); // modified
    } // modified

    /** modified: v4.3 稍后提醒间隔（分钟），至少 1 分钟，防止 0 让闹钟立刻重响。 */
    public int getSnoozeMinutes() { // modified
        int value = readInt(KEY_SNOOZE_MINUTES, DEFAULT_SNOOZE_MINUTES); // modified
        return Math.max(1, Math.min(value, 30)); // modified
    } // modified

    /** modified: v4.3 全屏告警页音量键行为：none / snooze / stop。 */
    public String getVolumeKeyAction() { // modified
        String value = safeGetString(KEY_VOLUME_KEY_ACTION, ACTION_NONE); // modified
        if (ACTION_SNOOZE.equals(value) || ACTION_STOP.equals(value)) { // modified
            return value; // modified
        } // modified
        return ACTION_NONE; // modified
    } // modified

    /** modified: 借鉴 SmsForwarder「转发功能总开关」；默认开启，关闭后完全不触发提醒。 */
    public boolean isAlertEnabled() { // modified
        return safeGetBoolean(KEY_ALERT_ENABLED, true); // modified
    } // modified

    /** modified: 借鉴 SmsForwarder 过滤规则；排除词复用关键词的归一化解析。 */
    public Set<String> getExcludeKeywords() { // modified
        String raw = safeGetString(KEY_EXCLUDE_KEYWORDS, ""); // modified
        if (raw == null || raw.trim().isEmpty()) { // modified
            return Collections.emptySet(); // modified
        } // modified
        return Collections.unmodifiableSet( // modified
                new LinkedHashSet<>(KeywordMatcher.parseKeywords(raw))); // modified
    } // modified

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
        return safeGetBoolean(KEY_MATCH_ALL, false); // modified: 类型兜底
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
        // modified: 【v4.2】显式声明「按设备本地时区、与语言无关」，避免隐式依赖
        // 默认 Locale 造成跨区域行为差异；DST 跳变的影响见审计报告风险章节。
        int hour = java.util.Calendar.getInstance( // modified
                java.util.TimeZone.getDefault(), java.util.Locale.ROOT) // modified
                .get(java.util.Calendar.HOUR_OF_DAY); // modified
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
        String raw = safeGetString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS); // modified: 类型兜底
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
            // modified: 【v4.2】原实现把 99 / 25 这类越界值 normalizeHour 成 3 / 1，
            // 等于把一份脏配置静默改写成「每天凌晨 3 点不响铃」，与类注释承诺的
            // 「无法解析的值按未启用处理，避免永远不响铃」相反。越界一律返回 -1。
            return hour >= 0 && hour <= 23 ? hour : -1; // modified
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
