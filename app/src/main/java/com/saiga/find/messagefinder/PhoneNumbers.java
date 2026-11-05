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

    /** 参与“后缀匹配”的最短有效长度，低于该长度只允许完全相等，避免 "123" 这类短号误命中。 */
    private static final int MIN_SUFFIX_LENGTH = 7;
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
            // 其余字符（空格、-、()、*、#）一律丢弃
        }
        return builder.toString();
    }

    /**
     * 校验用户输入的号码是否合法（允许空串，空串代表“不限号码”，由调用方单独判断）。
     *
     * @param raw 用户输入
     * @return 合法返回 true；空串返回 false（调用方需先判断是否允许为空）
     */
    public static boolean isValidInput(String raw) { // modified: 由“最多 10 位”改为国际通用的 5-15 位
        String stripped = stripSeparators(raw);
        if (stripped.isEmpty()) {
            return false;
        }
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (digits.isEmpty()) {
            return false;
        }
        for (int i = 0; i < digits.length(); i++) {
            char ch = digits.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
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
        if (digits.length() > CN_MOBILE_LENGTH) {
            // 仍然超长（例如带了分机号/短号前缀），取末 11 位
            digits = digits.substring(digits.length() - CN_MOBILE_LENGTH);
        }
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
        return configuredNumber.length() >= MIN_SUFFIX_LENGTH
                && senderNumber.length() >= MIN_SUFFIX_LENGTH
                && (senderNumber.endsWith(configuredNumber) || configuredNumber.endsWith(senderNumber));
    }
}
