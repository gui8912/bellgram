package com.saiga.find.messagefinder;

// [新增文件] 电话号码归一化与比对。
// 背景：原实现 spliceNumber() 把号码硬编码为“10 位”，并直接 substring(末尾 10 位)，
// 这对中国大陆 11 位手机号是致命的——"+8613800138000" 会被截成 "3800138000"，
// 与短信发送方号码永远不相等，导致“限定号码”功能在国内完全失效。
// 这里改为：去分隔符 -> 去国际区号(0086/86) -> 统一取末 11 位（中国手机号长度），比对时再兼容后缀匹配。
// 纯 Java 实现，无 Android 依赖，可用单元测试覆盖（见 PhoneNumbersTest）。

/**
 * 电话号码处理工具。
 */
public final class PhoneNumbers {

    // modified: 【v4.2】移除 MIN_SUFFIX_LENGTH（原 7 位门槛把 10086 / 95588 挡在门外）。
    // 现在由 matches() 用「较短侧 >= MIN_INPUT_LENGTH 且长度差 <= 4」统一约束。
    /** 单号码最大长度（E.164 上限 15 位）。 */
    private static final int MAX_INPUT_LENGTH = 15;
    /** 单号码最小长度（预留 5 位短号，如 10086）。 */
    private static final int MIN_INPUT_LENGTH = 5;
    /** 中国大陆手机号长度。 */
    private static final int CN_MOBILE_LENGTH = 11;

    private PhoneNumbers() {
        // 工具类不允许实例化
    }

    /**
     * 去掉空格、横线、括号等分隔符，只保留数字和首位可能存在的 "+"。
     * 同时把全角数字（０-９）转成半角数字。
     *
     * @param raw 原始号码，允许为 null
     * @return 仅含数字与可能的首位 "+"，永不为 null
     */
    public static String stripSeparators(String raw) { // modified: 新增，替代原 spliceNumber 的前半段逻辑
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                builder.append(ch);
            } else if (ch >= '\uFF10' && ch <= '\uFF19') {
                // 全角数字 -> 半角数字
                builder.append((char) (ch - 0xFEE0));
            } else if (ch == '+' && builder.length() == 0) {
                builder.append(ch);
            }
            // 其余字符（空格、-、()、*、#、字母、汉字）一律丢弃
        }
        return builder.toString();
    }

    /**
     * 号码里允许出现的“格式字符”：数字、全角数字、加号、连字符、点、半角/全角括号、
     * 空格（含不换行空格与全角空格），以及 * / #（服务号、USSD 补充符）。
     *
     * <p>不在白名单内的一律视为非法输入（字母、汉字、逗号、分机号等）。
     */
    private static boolean hasOnlyFormatChars(String raw) { // modified: 新增
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            boolean allowed = (ch >= '0' && ch <= '9')
                    || (ch >= '\uFF10' && ch <= '\uFF19')          // 全角数字
                    || ch == '+' || ch == '-' || ch == '.'
                    || ch == '(' || ch == ')' || ch == '\uFF08' || ch == '\uFF09'
                    // modified: 【v4.2】移除 '*' / '#'。它们是 USSD 补充符，
                    // stripSeparators 会直接丢弃，夹在数字中间时会把两段数字拼接起来
                    // 改写成另一个号码 —— 与 v5 修掉的「校验通过但配置被静默改写」
                    // 是同一个缺陷，只是残留了两个字符。
                    || ch == ' ' || ch == '\u00A0' || ch == '\u3000';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验用户输入的号码是否合法（空串代表“不限号码”，由调用方单独判断）。
     *
     * @param raw 用户输入
     * @return 合法返回 true；空串返回 false（调用方需先判断是否允许为空）
     */
    public static boolean isValidInput(String raw) { // modified: 由“最多 10 位”改为国际通用的 5-15 位
        // modified: 【独立复审修正】先做字符白名单校验，不能只看 stripSeparators 之后的长度。
        // 原因：stripSeparators 会静默丢弃所有非数字字符，于是
        //   "1380013800a"           -> 被当成 "1380013800" 判为合法；
        //   "138 0013 8000 转 1234" -> 被当成 15 位号码判为合法，canonical 再取末 11 位
        //                              就变成了另一个号码。
        // 而 MainActivity.saveConfig() 保存的是**用户原始输入串**，所以这属于
        // “校验通过、但配置被静默改写”：用户以为配了 A 号码，实际匹配的是 B 号码，
        // 短信永远不响且查不出原因。校验器的职责是拒绝，不是替用户改。
        if (raw == null || !hasOnlyFormatChars(raw)) {
            return false;
        }
        // modified: '+' 只允许至多一个，且必须在所有数字之前。
        // 否则 stripSeparators 会因为“不在首位就不保留”而把它丢掉，同样属于静默改写。
        boolean seenDigit = false;
        int plusCount = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '+') {
                plusCount++;
                if (seenDigit) {
                    return false;
                }
            } else if (ch >= '0' && ch <= '9') {
                seenDigit = true;
            } else if (ch >= '\uFF10' && ch <= '\uFF19') {
                seenDigit = true;
            }
        }
        if (plusCount > 1) {
            return false;
        }

        String stripped = stripSeparators(raw);
        if (stripped.isEmpty()) {
            return false;
        }
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (digits.isEmpty()) {
            return false;
        }
        // modified: 走到这里 digits 必然是纯数字（字符白名单已保证），只需校验长度
        return digits.length() >= MIN_INPUT_LENGTH && digits.length() <= MAX_INPUT_LENGTH;
    }

    /**
     * 规范化号码，用于“配置的号码”与“短信发送方号码”之间的比对。
     *
     * <p>处理内容：去分隔符、去 0086/+86 前缀、超长时取末 11 位（中国大陆手机号长度）。
     *
     * @param raw 原始号码，允许为 null
     * @return 规范化后的号码；无法解析时返回空串
     */
    public static String canonical(String raw) { // modified: 修正原实现“只留末 10 位”导致国内号码被截断的问题
        String stripped = stripSeparators(raw);
        if (stripped.isEmpty()) {
            return "";
        }
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (digits.startsWith("0086") && digits.length() > 4) {
            digits = digits.substring(4);
        } else if (digits.startsWith("86") && digits.length() > CN_MOBILE_LENGTH) {
            digits = digits.substring(2);
        }
        // modified: 【v4.2 独立复审】原实现无条件「超过 11 位就取末 11 位」。
        // v5 把 isValidInput 上限放宽到 E.164 的 15 位后，两个不同的 13 位号码
        // （1234567890123 / 9934567890123）都会被截成同一个末 11 位而误判为相同号码，
        // 造成误报。现在保留完整数字串；国家码/区号前缀差异交给 matches() 的后缀匹配处理。
        return digits;
    }

    /**
     * 判断短信发送方是否匹配用户配置的号码。
     *
     * <p>原实现用 {@code sender.contains(number)}：配置 "1234" 会命中 "1000012349999"，
     * 属于明显的误报来源。这里改为“完全相等，或长度足够时的后缀相等”。
     *
     * @param configured 用户配置的号码；为空表示不限号码，直接返回 true
     * @param sender     短信发送方号码
     * @return 匹配返回 true
     */
    public static boolean matches(String configured, String sender) { // modified: contains -> 精确/后缀匹配
        String configuredNumber = canonical(configured);
        if (configuredNumber.isEmpty()) {
            return true;
        }
        String senderNumber = canonical(sender);
        if (senderNumber.isEmpty()) {
            return false;
        }
        if (configuredNumber.equals(senderNumber)) {
            return true;
        }
        // modified: 【v4.2】原实现是「双向后缀 + 两侧都 >= 7 位」：
        //   ① 双向后缀让「配置 11 位、发送方 9 位且是其后缀」也命中，误报面过宽；
        //   ② 7 位门槛把 10086 / 95588 这类最常监听的服务号挡在门外，
        //      而短信侧常返回 +8610086，于是短号规则静默失效。
        // 改为：只允许「较长一侧 = 较短一侧 + 至多 4 位前缀」这一种合法情形。
        String longer = configuredNumber.length() >= senderNumber.length() // modified
                ? configuredNumber : senderNumber; // modified
        String shorter = configuredNumber.length() >= senderNumber.length() // modified
                ? senderNumber : configuredNumber; // modified
        int prefixLength = longer.length() - shorter.length(); // modified
        return shorter.length() >= MIN_INPUT_LENGTH // modified
                && prefixLength <= 4 // modified
                && longer.endsWith(shorter); // modified
    }
}
