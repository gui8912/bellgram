# -*- coding: utf-8 -*-
"""v4.2.0 批次 2：配置与数据层修复。所有替换带存在性断言。"""
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


# ============================================================ AppConfig
n = "AppConfig.java"
s = read(n)

# 1) 通用安全读取器
s = rep(n, s,
'''    public static AppConfig get(Context context) { // modified: 用 ApplicationContext 取代原来的 static Activity 引用
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new AppConfig(
                PreferenceManager.getDefaultSharedPreferences(appContext),
                appContext.getSharedPreferences(FEATURE_PREF_NAME, Context.MODE_PRIVATE));
    }
''',
'''    public static AppConfig get(Context context) { // modified: 用 ApplicationContext 取代原来的 static Activity 引用
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
        try {
            return configPrefs.getString(key, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    private boolean safeGetBoolean(String key, boolean defaultValue) { // modified: 新增
        return safeGetBoolean(configPrefs, key, defaultValue);
    }

    private static boolean safeGetBoolean(SharedPreferences prefs, String key,
                                          boolean defaultValue) { // modified: 新增
        try {
            return prefs.getBoolean(key, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }
''', "通用安全读取器")

# 2) getContact / getContactName
s = rep(n, s, '''        String value = configPrefs.getString(KEY_CONTACT, null);''',
        '''        String value = safeGetString(KEY_CONTACT, null); // modified: 类型不匹配兜底''', "getContact")
s = rep(n, s, '''        String value = configPrefs.getString(KEY_CONTACT_NAME, null);''',
        '''        String value = safeGetString(KEY_CONTACT_NAME, null); // modified: 类型不匹配兜底''', "getContactName")

# 3) getKeywords 旧数据迁移
s = rep(n, s,
'''        Set<String> stored;
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
    }''',
'''        Set<String> stored;
        try {
            stored = configPrefs.getStringSet(KEY_KEYWORDS, null);
        } catch (ClassCastException e) {
            // modified: 【v4.2 独立复审】原实现 catch 后直接 stored = null，
            // 把「旧版本把 message 存成逗号分隔 String」当成「用户从未配置」，
            // 返回空集合后 SMSReceiver 直接丢弃每一条短信：关键词静默全丢、
            // 界面还显示「未配置」，用户没有任何线索。这里做一次性迁移。
            stored = migrateLegacyKeywords();
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
        try {
            String legacy = configPrefs.getString(KEY_KEYWORDS, null);
            if (legacy == null || legacy.trim().isEmpty()) {
                return null;
            }
            List<String> parsed = KeywordMatcher.parseKeywords(legacy);
            if (parsed.isEmpty()) {
                return null;
            }
            Set<String> migrated = new LinkedHashSet<>(parsed);
            // modified: 用 commit 同步落盘，避免下次读仍触发迁移；传新 Set 避免污染缓存
            configPrefs.edit().putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(migrated)).commit();
            return migrated;
        } catch (Exception e) {
            // 键存的既不是 Set 也不是 String（例如 Integer）：确实无法恢复
            return null;
        }
    }''', "getKeywords 旧数据迁移")

# 4) boolean 读取点
s = rep(n, s, '''        return featurePrefs.getBoolean(KEY_AUTO_SUGGESTION_DISABLED, true);''',
        '''        return safeGetBoolean(featurePrefs, KEY_AUTO_SUGGESTION_DISABLED, true); // modified: 类型兜底''', "自动联想开关")
s = rep(n, s, '''        return configPrefs.getBoolean(KEY_RING_ENABLED, true);''',
        '''        return safeGetBoolean(KEY_RING_ENABLED, true); // modified: 类型兜底''', "响铃开关")
s = rep(n, s, '''        return configPrefs.getBoolean(KEY_VIBRATE_ENABLED, true);''',
        '''        return safeGetBoolean(KEY_VIBRATE_ENABLED, true); // modified: 类型兜底''', "震动开关")
s = rep(n, s, '''        return configPrefs.getBoolean(KEY_MATCH_ALL, false);''',
        '''        return safeGetBoolean(KEY_MATCH_ALL, false); // modified: 类型兜底''', "全部命中开关")

# 5) 响铃时长钳制
s = rep(n, s,
'''    public int getRingDurationSec() { // modified: 新增
        return readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC);
    }''',
'''    public int getRingDurationSec() { // modified: 新增
        // modified: 【v4.2】另两个 int 读取器都做了范围钳制，唯独这里没有。
        // 脏配置写入 999999 会让前台服务与 WakeLock 挂近 12 天；负值会立即结束本轮。
        // 与 arrays.xml 的合法值域 {0,30,60,120} 对齐，上限取 120。
        int value = readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC);
        return Math.max(0, Math.min(value, 120));
    }''', "响铃时长钳制")

# 6) 静音时段：读取兜底 + 越界拒绝 + 显式时区
s = rep(n, s,
'''        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);''',
'''        // modified: 【v4.2】显式声明「按设备本地时区、与语言无关」，避免隐式依赖
        // 默认 Locale 造成跨区域行为差异；DST 跳变的影响见审计报告风险章节。
        int hour = java.util.Calendar.getInstance(
                java.util.TimeZone.getDefault(), java.util.Locale.ROOT)
                .get(java.util.Calendar.HOUR_OF_DAY);''', "静音判定显式时区")

s = rep(n, s,
'''        String raw = configPrefs.getString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS);''',
'''        String raw = safeGetString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS); // modified: 类型兜底''', "静音配置读取")

s = rep(n, s,
'''            int hour = Integer.parseInt(parts[start ? 0 : 1].trim());
            return QuietHours.normalizeHour(hour);''',
'''            int hour = Integer.parseInt(parts[start ? 0 : 1].trim());
            // modified: 【v4.2】原实现把 99 / 25 这类越界值 normalizeHour 成 3 / 1，
            // 等于把一份脏配置静默改写成「每天凌晨 3 点不响铃」，与类注释承诺的
            // 「无法解析的值按未启用处理，避免永远不响铃」相反。越界一律返回 -1。
            return hour >= 0 && hour <= 23 ? hour : -1;''', "静音小时越界拒绝")
write(n, s)

# ============================================================ PhoneNumbers
n = "PhoneNumbers.java"
s = read(n)
s = rep(n, s,
'''                    || ch == '(' || ch == ')' || ch == '\\uFF08' || ch == '\\uFF09'
                    || ch == '*' || ch == '#'
                    || ch == ' ' || ch == '\\u00A0' || ch == '\\u3000';''',
'''                    || ch == '(' || ch == ')' || ch == '\\uFF08' || ch == '\\uFF09'
                    // modified: 【v4.2】移除 '*' / '#'。它们是 USSD 补充符，
                    // stripSeparators 会直接丢弃，夹在数字中间时会把两段数字拼接起来
                    // 改写成另一个号码 —— 与 v5 修掉的「校验通过但配置被静默改写」
                    // 是同一个缺陷，只是残留了两个字符。
                    || ch == ' ' || ch == '\\u00A0' || ch == '\\u3000';''', "移除 * # 白名单")

s = rep(n, s,
'''            // 其余字符（空格、-、()、*、#）一律丢弃''',
'''            // 其余字符（空格、-、()、*、#、字母、汉字）一律丢弃''', "stripSeparators 注释")

s = rep(n, s,
'''        if (digits.length() > CN_MOBILE_LENGTH) {
            // 仍然超长（例如带了分机号/短号前缀），取末 11 位
            digits = digits.substring(digits.length() - CN_MOBILE_LENGTH);
        }
        return digits;''',
'''        // modified: 【v4.2 独立复审】原实现无条件「超过 11 位就取末 11 位」。
        // v5 把 isValidInput 上限放宽到 E.164 的 15 位后，两个不同的 13 位号码
        // （1234567890123 / 9934567890123）都会被截成同一个末 11 位而误判为相同号码，
        // 造成误报。现在保留完整数字串；国家码/区号前缀差异交给 matches() 的后缀匹配处理。
        return digits;''', "取消无条件截断")

s = rep(n, s,
'''        return configuredNumber.length() >= MIN_SUFFIX_LENGTH
                && senderNumber.length() >= MIN_SUFFIX_LENGTH
                && (senderNumber.endsWith(configuredNumber) || configuredNumber.endsWith(senderNumber));''',
'''        // modified: 【v4.2】原实现是「双向后缀 + 两侧都 >= 7 位」：
        //   ① 双向后缀让「配置 11 位、发送方 9 位且是其后缀」也命中，误报面过宽；
        //   ② 7 位门槛把 10086 / 95588 这类最常监听的服务号挡在门外，
        //      而短信侧常返回 +8610086，于是短号规则静默失效。
        // 改为：只允许「较长一侧 = 较短一侧 + 至多 4 位前缀」这一种合法情形。
        String longer = configuredNumber.length() >= senderNumber.length()
                ? configuredNumber : senderNumber;
        String shorter = configuredNumber.length() >= senderNumber.length()
                ? senderNumber : configuredNumber;
        int prefixLength = longer.length() - shorter.length();
        return shorter.length() >= MIN_INPUT_LENGTH
                && prefixLength <= 4
                && longer.endsWith(shorter);''', "后缀匹配收紧并支持短号")
write(n, s)

# ============================================================ KeywordMatcher
n = "KeywordMatcher.java"
s = read(n)
s = rep(n, s,
'''import java.util.Locale;''',
'''import java.util.Locale;
import java.util.regex.Pattern; // modified: 【v4.2】预编译分隔符，避免每次 split 都 compile''', "Pattern import")

s = rep(n, s,
'''    /** 分隔符：逗号、分号、空白（归一化后全角已变半角，因此这里只需处理半角）。 */
    private static final String SEPARATOR_REGEX = "[,;\\\\s]+";''',
'''    /** 分隔符：逗号、分号、空白（归一化后全角与 CJK 标点已变半角，这里只需处理半角）。 */
    private static final Pattern SEPARATOR_PATTERN = // modified: 由 String 改为预编译 Pattern
            Pattern.compile("[,;\\\\s]+");''', "预编译分隔符")

s = rep(n, s,
'''        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\u3000' || ch == '\\u00A0') {
                // 全角空格 / 不间断空格 -> 普通空格
                builder.append(' ');
            } else if (ch >= '\\uFF01' && ch <= '\\uFF5E') {''',
'''        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            // modified: 【v4.2】中文用户高频输入「报警、求助」，顿号 U+3001 /
            // 句号 U+3002 原本被原样保留，整串变成一个永不命中的关键词；
            // 从微信/网页复制粘贴带进来的零宽空格 U+200B、BOM U+FEFF 同理。
            // 类注释本就宣称要统一 Unicode 空白，这里把规则补全。
            if (ch == '\\u3001' || ch == '\\u3002' || ch == '\\uFF0E') {
                builder.append(',');       // CJK 标点 -> 分隔符
            } else if (ch == '\\u200B' || ch == '\\uFEFF' || ch == '\\u2060'
                    || ch == '\\u200E' || ch == '\\u200F') {
                builder.append(' ');       // 零宽字符 / BOM -> 分隔符
            } else if (ch == '\\u3000' || ch == '\\u00A0'
                    || Character.isSpaceChar(ch) || Character.isWhitespace(ch)) {
                // 全角空格 / 不间断空格 / 各类 Unicode 排版空格 -> 普通空格
                builder.append(' ');
            } else if (ch >= '\\uFF01' && ch <= '\\uFF5E') {''', "归一化补 CJK 标点与零宽字符")

s = rep(n, s,
'''        String[] parts = normalized.split(SEPARATOR_REGEX);''',
'''        String[] parts = SEPARATOR_PATTERN.split(normalized); // modified: 复用预编译 Pattern''', "split 用预编译")

s = rep(n, s,
'''            if (keyword.length() > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
            }''',
'''            // modified: 【v4.2】原实现按 UTF-16 char 截断，emoji / 增补平面汉字
            // 正好跨在边界上时会留下孤立代理项，写进 SharedPreferences 的 XML 后
            // 显示成乱码，部分 OEM 的 XML 编码器甚至会抛异常。改按 code point 计。
            if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) {
                keyword = keyword.substring(0, keyword.offsetByCodePoints(0, MAX_KEYWORD_LENGTH));
            }''', "码点安全截断")
write(n, s)

# ============================================================ HitLog
n = "HitLog.java"
s = read(n)
s = rep(n, s,
'''            prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).apply();''',
'''            // modified: 【v4.2】apply() 只更新内存、把磁盘写入排进异步队列；
            // AOSP 只在 Activity/Service stop 时等待落盘，BroadcastReceiver 返回后
            // 进程随时可被回收。这里是唯一落在短信广播链路上的写操作，
            // 改 commit() 同步落盘（20 条短文本，开销可忽略）。
            prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).commit();''', "命中日志同步落盘")

s = rep(n, s,
'''        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String stored = prefs.getString(KEY_HIT_LOG, "");
        return stored == null ? "" : stored;
    }''',
'''        // modified: 【v4.2】原实现只有 add() 加锁，clear()/dump() 裸读写；
        // 「点清空」与「来短信」并发时，add() 可能把清空前的旧记录写回，清空失效。
        synchronized (LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String stored = prefs.getString(KEY_HIT_LOG, "");
            return stored == null ? "" : stored;
        }
    }''', "dump 加锁")

s = rep(n, s,
'''        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(KEY_HIT_LOG).apply();
    }''',
'''        synchronized (LOCK) { // modified: 【v4.2】与 add()/dump() 共用同一把锁
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().remove(KEY_HIT_LOG).commit(); // modified: 同步落盘
        }
    }''', "clear 加锁")

s = rep(n, s,
'''    private static String format(String sender, String keyword, String body) {
        String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
        String safeSender = TextUtils.isEmpty(sender) ? "未知号码" : sender.trim();
        String safeKeyword = TextUtils.isEmpty(keyword) ? "-" : keyword.trim();
        return time + FIELD_SEPARATOR + safeSender + FIELD_SEPARATOR + "[" + safeKeyword + "] "
                + snippet(body);
    }

    /** 单行化并截断正文，避免记录里混入换行符破坏存储格式。 */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body.replace('\\n', ' ').replace('\\r', ' ').replace('\\t', ' ').trim();
        if (singleLine.length() <= SNIPPET_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }''',
'''    private static String format(String sender, String keyword, String body) {
        String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
        // modified: 【v4.2】原实现只对 body 做单行化，sender / keyword 仅 trim()。
        // 畸形 SP 号或历史关键词里含换行时，一条记录会被 stored.split("\\n")
        // 拆成多行，20 条环形缓冲被迅速挤空，设置页里字段也错位。
        String safeSender = singleLine(sender, 24);
        String safeKeyword = singleLine(keyword, 30);
        if (safeSender.isEmpty()) {
            safeSender = "未知号码";
        }
        if (safeKeyword.isEmpty()) {
            safeKeyword = "-";
        }
        return time + FIELD_SEPARATOR + safeSender + FIELD_SEPARATOR + "[" + safeKeyword + "] "
                + singleLine(body, SNIPPET_MAX_LENGTH);
    }

    /** 单行化 + 码点安全截断。所有写入记录的字段都必须经过这里。 */
    private static String singleLine(String value, int maxLength) { // modified: v4.2 重写
        if (value == null) {
            return "";
        }
        String text = value.replace('\\n', ' ').replace('\\r', ' ').replace('\\t', ' ').trim();
        if (text.codePointCount(0, text.length()) <= maxLength) {
            return text;
        }
        // modified: 按 code point 截，避免切断 emoji 的 surrogate pair 写入非法 UTF-16
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…";
    }''', "记录字段单行化与码点截断")
write(n, s)

print("批次 2 完成，共 %d 处" % len(changes))
for name, tag in changes:
    print("  %-30s %s" % (name, tag))
