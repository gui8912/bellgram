package com.saiga.find.messagefinder;

// [新增文件] 关键词解析与匹配的唯一入口。
// 设计参考：pppscn/SmsForwarder（https://github.com/pppscn/SmsForwarder）的“规则引擎”分层思路——
// 把“文本归一化 / 规则解析 / 规则匹配”从广播接收器中抽成独立、可单测的纯 Java 类，
// 只借用其分层思想与归一化做法，未复制其代码；本项目的匹配语义在原“任意关键词包含”的基础上，
// 新增“全部关键词包含”模式（对齐短信铃铛的规则设置），默认行为与原版完全一致。
// 之所以抽出来：原实现把拆分与匹配逻辑直接写在 SMSReceiver / MainActivity 里，
// 存在中文关键词拆分为空串、全角标点不兼容、无法单元测试三个问题。

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern; // modified: 【v4.2】预编译分隔符，避免每次 split 都 compile

/**
 * 关键词归一化与匹配工具。
 *
 * <p>归一化规则（关键字与短信正文走同一套规则，保证两侧可比）：
 * <ol>
 *   <li>全角 ASCII（！-～，U+FF01-U+FF5E）转半角，全角数字同样处理；</li>
 *   <li>全角空格 U+3000、不间断空格 U+00A0 统一成普通空格；</li>
 *   <li>去掉首尾空白；</li>
 *   <li>统一转小写（{@link Locale#ROOT}，避免土耳其语等区域的 i/ı 问题）。</li>
 * </ol>
 *
 * <p>该类不依赖任何 Android API，可直接用 JVM 单元测试覆盖（见 KeywordMatcherTest）。
 */
public final class KeywordMatcher {

    /** 单条关键词最大长度，超出截断，避免用户粘贴长文本把匹配变成“几乎全命中”。 */
    public static final int MAX_KEYWORD_LENGTH = 30;
    /** 关键词数量上限，防止误粘贴整段文本导致匹配开销与存储膨胀。 */
    public static final int MAX_KEYWORDS = 50;

    /** 分隔符：逗号、分号、空白（归一化后全角与 CJK 标点已变半角，这里只需处理半角）。 */
    private static final Pattern SEPARATOR_PATTERN = // modified: 由 String 改为预编译 Pattern
            Pattern.compile("[,;\\s]+"); // modified

    private KeywordMatcher() {
        // 工具类不允许实例化
    }

    /**
     * 文本归一化：全角转半角、统一空格、去首尾空白、转小写。
     *
     * @param raw 原始文本，允许为 null
     * @return 归一化后的文本，永不为 null（入参为 null 时返回空串）
     */
    public static String normalize(String raw) { // modified: 新增统一归一化入口
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            // modified: 【v4.2】中文用户高频输入「报警、求助」，顿号 U+3001 /
            // 句号 U+3002 原本被原样保留，整串变成一个永不命中的关键词；
            // 从微信/网页复制粘贴带进来的零宽空格 U+200B、BOM U+FEFF 同理。
            // 类注释本就宣称要统一 Unicode 空白，这里把规则补全。
            if (ch == '\u3001' || ch == '\u3002' || ch == '\uFF0E') { // modified
                builder.append(',');       // CJK 标点 -> 分隔符 // modified
            } else if (ch == '\u200B' || ch == '\uFEFF' || ch == '\u2060' // modified
                    || ch == '\u200E' || ch == '\u200F') { // modified
                builder.append(' ');       // 零宽字符 / BOM -> 分隔符 // modified
            } else if (ch == '\u3000' || ch == '\u00A0' // modified
                    || Character.isSpaceChar(ch) || Character.isWhitespace(ch)) { // modified
                // 全角空格 / 不间断空格 / 各类 Unicode 排版空格 -> 普通空格
                builder.append(' ');
            } else if (ch >= '\uFF01' && ch <= '\uFF5E') {
                // 全角 ASCII -> 半角 ASCII（含全角逗号、全角数字、全角字母）
                builder.append((char) (ch - 0xFEE0));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析用户输入的关键词串。支持半角/全角逗号、分号、空格分隔，自动去重、去空、截断。
     *
     * <p>原实现 {@code messageStr.split("\\s*,\\s*|\\s+")} 的缺陷：
     * 中文用户习惯输入全角逗号“，”，split 不认，整串会变成一个永远匹配不到的关键词。
     *
     * @param raw 用户输入，允许为 null
     * @return 去重后的关键词列表（保持用户输入顺序），永不为 null
     */
    public static List<String> parseKeywords(String raw) { // modified: 全角分隔符兼容 + 去重 + 上限保护
        List<String> result = new ArrayList<>();
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return result;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String[] parts = SEPARATOR_PATTERN.split(normalized); // modified: 复用预编译 Pattern
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String keyword = part.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            // modified: 【v4.2】原实现按 UTF-16 char 截断，emoji / 增补平面汉字
            // 正好跨在边界上时会留下孤立代理项，写进 SharedPreferences 的 XML 后
            // 显示成乱码，部分 OEM 的 XML 编码器甚至会抛异常。改按 code point 计。
            if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) { // modified
                keyword = keyword.substring(0, keyword.offsetByCodePoints(0, MAX_KEYWORD_LENGTH)); // modified
            }
            unique.add(keyword);
            if (unique.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        result.addAll(unique);
        return result;
    }

    /**
     * 在短信正文中查找第一个命中的关键词（“任意命中”语义）。
     *
     * @param keywords 已归一化的关键词集合（{@link #parseKeywords} 的产物，或历史版本遗留的小写集合）
     * @param body     短信正文，允许为 null
     * @return 命中的关键词（原样返回集合中的元素，便于在通知与日志里展示）；未命中返回 null
     */
    public static String findMatch(Collection<String> keywords, String body) {
        return findMatch(keywords, body, false); // modified: 保留原签名，默认“任意命中”
    }

    /**
     * modified: v4.3 借鉴 SmsForwarder 的过滤规则。排除词用「任意命中即排除」，
     * 复用 findMatch 的归一化逻辑，避免正反两套匹配规则不一致。
     *
     * @return 命中的排除词；未命中返回 null
     */
    public static String findExclusion(Collection<String> exclusions, String body) { // modified
        return findMatch(exclusions, body, false); // modified
    } // modified

    /**
     * 在短信正文中查找命中的关键词，支持“任意命中 / 全部命中”两种模式。
     *
     * <p>为什么要支持“全部命中”：参照短信铃铛的规则设置，只写“到账”会把所有广告都命中，
     * 而“到账 + 银行名”能把误报压到几乎为零。这是“提醒太吵”与“提醒太松”之间最实用的旋钮。
     *
     * @param keywords 已归一化的关键词集合
     * @param body     短信正文，允许为 null
     * @param matchAll true 表示要求**全部**关键词都出现；false 表示任意一个出现即可
     * @return 命中的关键词（“全部命中”时返回集合中的第一个，仅用于展示）；未命中返回 null
     */
    public static String findMatch(Collection<String> keywords, String body,
                                   boolean matchAll) { // modified: 新增，支持“全部命中”
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        String normalizedBody = normalize(body);
        if (normalizedBody.isEmpty()) {
            return null;
        }
        String firstHit = null;
        int effectiveKeywords = 0;
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            // 兼容历史数据：老版本手工写入的关键词可能未归一化（含大写/全角）
            String normalizedKeyword = normalize(keyword);
            if (normalizedKeyword.isEmpty()) {
                continue;
            }
            effectiveKeywords++;
            boolean hit = normalizedBody.contains(normalizedKeyword);
            if (hit) {
                if (firstHit == null) {
                    firstHit = keyword;
                }
                if (!matchAll) {
                    return keyword;
                }
            } else if (matchAll) {
                // 全部命中模式：只要缺一个就可以立刻判定不命中
                return null;
            }
        }
        if (matchAll && effectiveKeywords > 0 && firstHit != null) {
            return firstHit;
        }
        return null;
    }
}
