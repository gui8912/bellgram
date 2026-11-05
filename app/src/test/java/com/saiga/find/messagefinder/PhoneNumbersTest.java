package com.saiga.find.messagefinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link PhoneNumbers} 的单元测试。
 *
 * <p>重点覆盖中国大陆号码场景：短信发送方号码可能带 +86 / 0086 前缀，
 * 也可能带空格分隔（"138 0013 8000"），而用户从通讯录选出的号码格式又各不相同。
 * 原实现一律截取末 10 位，会把 11 位手机号截坏。
 */
public class PhoneNumbersTest {

    @Test
    public void canonical_keepsElevenDigitMobileIntact() {
        assertEquals("13800138000", PhoneNumbers.canonical("13800138000"));
    }

    @Test
    public void canonical_stripsCountryCode86() {
        assertEquals("13800138000", PhoneNumbers.canonical("+8613800138000"));
        assertEquals("13800138000", PhoneNumbers.canonical("008613800138000"));
        assertEquals("13800138000", PhoneNumbers.canonical("8613800138000"));
    }

    @Test
    public void canonical_stripsSeparators() {
        assertEquals("13800138000", PhoneNumbers.canonical("138 0013 8000"));
        assertEquals("13800138000", PhoneNumbers.canonical("138-0013-8000"));
        assertEquals("13800138000", PhoneNumbers.canonical("(138)0013-8000"));
    }

    @Test
    public void canonical_keepsShortServiceNumber() {
        assertEquals("10086", PhoneNumbers.canonical("10086"));
        assertEquals("95588", PhoneNumbers.canonical("95588"));
    }

    @Test
    public void canonical_emptyInput() {
        assertEquals("", PhoneNumbers.canonical(null));
        assertEquals("", PhoneNumbers.canonical(""));
        assertEquals("", PhoneNumbers.canonical("   "));
    }

    @Test
    public void isValidInput_acceptsRealisticNumbers() {
        assertTrue(PhoneNumbers.isValidInput("13800138000"));
        assertTrue(PhoneNumbers.isValidInput("+8613800138000"));
        assertTrue(PhoneNumbers.isValidInput("10086"));
        assertTrue(PhoneNumbers.isValidInput("138 0013 8000"));
        // modified: 通讯录/名片里常见的全角括号与点号分隔，应视为格式字符而非非法字符
        assertTrue(PhoneNumbers.isValidInput("（138）0013-8000"));
        assertTrue(PhoneNumbers.isValidInput("138.0013.8000"));
    }

    @Test
    public void isValidInput_rejectsBadInput() {
        assertFalse(PhoneNumbers.isValidInput(null));
        assertFalse(PhoneNumbers.isValidInput(""));
        assertFalse(PhoneNumbers.isValidInput("123"));           // 太短
        assertFalse(PhoneNumbers.isValidInput("1234567890123456")); // 太长（16 位）
        assertFalse(PhoneNumbers.isValidInput("1380013800a"));
        assertFalse(PhoneNumbers.isValidInput("+"));
        // modified: 以下 4 条对应【独立复审】发现的缺陷——stripSeparators 会静默丢弃
        // 非数字字符，旧实现因此把这些输入判为"合法"并原样存进配置，实际匹配的是别的号码。
        assertFalse(PhoneNumbers.isValidInput("138 0013 8000 转 1234")); // 汉字分机号
        assertFalse(PhoneNumbers.isValidInput("13800138000,1234"));      // 通讯录导出的逗号分机
        assertFalse(PhoneNumbers.isValidInput("1+3800138000"));          // '+' 不在首位
        assertFalse(PhoneNumbers.isValidInput("++8613800138000"));       // 多个 '+'
    }

    @Test
    public void matches_emptyConfiguredMeansAllSenders() {
        assertTrue(PhoneNumbers.matches("", "13800138000"));
        assertTrue(PhoneNumbers.matches(null, "10086"));
    }

    @Test
    public void matches_sameNumberWithDifferentFormatting() {
        assertTrue(PhoneNumbers.matches("13800138000", "+8613800138000"));
        assertTrue(PhoneNumbers.matches("+86 138 0013 8000", "13800138000"));
        assertTrue(PhoneNumbers.matches("10086", "10086"));
    }

    @Test
    public void matches_doesNotMatchDifferentNumber() {
        assertFalse(PhoneNumbers.matches("13800138000", "13900139000"));
    }

    @Test
    public void matches_doesNotMatchOnPartialSubstring() {
        // 原实现用 contains：配置 "1234" 会误命中 "1000012349999"
        assertFalse(PhoneNumbers.matches("1234", "1000012349999"));
    }

    @Test
    public void matches_supportsSuffixWhenOneSideHasExtraPrefix() {
        // 座机带区号与不带区号的场景：010-88886666 vs 88886666
        assertTrue(PhoneNumbers.matches("88886666", "01088886666"));
    }

    @Test
    public void matches_emptySenderNeverMatchesConfiguredNumber() {
        assertFalse(PhoneNumbers.matches("13800138000", ""));
        assertFalse(PhoneNumbers.matches("13800138000", null));
    }
}
