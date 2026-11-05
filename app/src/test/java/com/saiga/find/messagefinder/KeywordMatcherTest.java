package com.saiga.find.messagefinder;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link KeywordMatcher} 的单元测试。
 *
 * <p>运行方式：{@code gradlew test} 或 Android Studio 里右键本文件 Run。
 * 该测试不依赖 Android 设备/模拟器。
 */
public class KeywordMatcherTest {

    @Test
    public void parseKeywords_splitsOnCommaAndSpace() {
        List<String> keywords = KeywordMatcher.parseKeywords("报警, 求助 help");
        assertEquals(Arrays.asList("报警", "求助", "help"), keywords);
    }

    @Test
    public void parseKeywords_supportsFullWidthComma() {
        // 中文输入法下最常见的全角逗号，原实现无法识别
        List<String> keywords = KeywordMatcher.parseKeywords("报警，求助");
        assertEquals(Arrays.asList("报警", "求助"), keywords);
    }

    @Test
    public void parseKeywords_supportsFullWidthSpaceAndSemicolon() {
        List<String> keywords = KeywordMatcher.parseKeywords("报警　求助；help");
        assertEquals(Arrays.asList("报警", "求助", "help"), keywords);
    }

    @Test
    public void parseKeywords_dedupAndLowercase() {
        List<String> keywords = KeywordMatcher.parseKeywords("ALERT alert Alert");
        assertEquals(1, keywords.size());
        assertEquals("alert", keywords.get(0));
    }

    @Test
    public void parseKeywords_emptyInput() {
        assertTrue(KeywordMatcher.parseKeywords(null).isEmpty());
        assertTrue(KeywordMatcher.parseKeywords("").isEmpty());
        assertTrue(KeywordMatcher.parseKeywords("  ,  , ").isEmpty());
    }

    @Test
    public void parseKeywords_truncatesTooLongKeyword() {
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < KeywordMatcher.MAX_KEYWORD_LENGTH + 20; i++) {
            longWord.append('a');
        }
        List<String> keywords = KeywordMatcher.parseKeywords(longWord.toString());
        assertEquals(1, keywords.size());
        assertEquals(KeywordMatcher.MAX_KEYWORD_LENGTH, keywords.get(0).length());
    }

    @Test
    public void findMatch_matchesChineseKeyword() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警", "求助"));
        assertEquals("报警", KeywordMatcher.findMatch(keywords, "家里进贼了，赶紧报警！"));
    }

    @Test
    public void findMatch_isCaseInsensitive() {
        Set<String> keywords = new HashSet<>(Arrays.asList("alert"));
        assertEquals("alert", KeywordMatcher.findMatch(keywords, "URGENT ALERT from server"));
    }

    @Test
    public void findMatch_normalizesFullWidthBody() {
        // 短信正文可能是全角字母（部分端口短信会这样发）
        Set<String> keywords = new HashSet<>(Arrays.asList("alert"));
        assertEquals("alert", KeywordMatcher.findMatch(keywords, "ＡＬＥＲＴ"));
    }

    @Test
    public void findMatch_returnsNullWhenNoKeywordHit() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警"));
        assertNull(KeywordMatcher.findMatch(keywords, "今天天气不错"));
    }

    @Test
    public void findMatch_handlesNullAndEmptyInputs() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警"));
        assertNull(KeywordMatcher.findMatch(null, "报警"));
        assertNull(KeywordMatcher.findMatch(new HashSet<String>(), "报警"));
        assertNull(KeywordMatcher.findMatch(keywords, null));
        assertNull(KeywordMatcher.findMatch(keywords, ""));
    }

    @Test
    public void findMatch_toleratesLegacyUnnormalizedKeyword() {
        // 老版本可能把大写关键词原样存进 SharedPreferences
        Set<String> legacy = new HashSet<>(Arrays.asList("ALERT"));
        assertEquals("ALERT", KeywordMatcher.findMatch(legacy, "an alert happened"));
    }
    // ---- 二次开发新增：全部命中模式（借鉴短信铃铛的规则设置） ----

    @Test
    public void findMatch_matchAllRequiresEveryKeyword() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "工商银行"));
        // 缺一个 -> 不命中
        assertNull(KeywordMatcher.findMatch(keywords, "您尾号 1234 账户到账 8000 元", true));
        // 全都有 -> 命中
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "工商银行：您尾号 1234 账户到账 8000 元", true));
    }

    @Test
    public void findMatch_matchAnyStillWorksWhenMatchAllDisabled() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "工商银行"));
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "您尾号 1234 账户到账 8000 元", false));
    }

    @Test
    public void findMatch_matchAllIgnoresBlankKeywords() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "  ", ""));
        assertEquals("到账", KeywordMatcher.findMatch(keywords, "到账 8000 元", true));
    }

    @Test
    public void findMatch_matchAllNormalizesFullWidthBody() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "余额"));
        // 全角数字与全角空格都应被归一化后仍能匹配
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "到账８０００元　余额１２３４元", true));
    }
}
