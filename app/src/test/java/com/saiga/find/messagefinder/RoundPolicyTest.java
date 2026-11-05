package com.saiga.find.messagefinder;

// [新增测试 / modified] v4 架构重构：轮次规则从 Service 抽出后可直接在 JVM 上验证。
// 重点覆盖「不重复」「一直响」「不限次数」「最后一轮」「非法参数」这几类边界。

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoundPolicyTest {

    @Test
    public void totalRounds_defaultIsFirstPlusThreeRepeats() {
        assertEquals(4, RoundPolicy.totalRounds(60, 5, 3));
    }

    @Test
    public void totalRounds_noRepeatMeansOneRound() {
        assertEquals(1, RoundPolicy.totalRounds(60, 0, 3));
    }

    @Test
    public void totalRounds_alwaysRingMeansOneRound() {
        assertEquals(1, RoundPolicy.totalRounds(0, 5, 3));
    }

    @Test
    public void totalRounds_unlimitedReturnsZero() {
        assertEquals(0, RoundPolicy.totalRounds(60, 5, 0));
    }

    @Test
    public void totalRounds_negativeMaxRepeatTreatedAsUnlimited() {
        assertEquals(0, RoundPolicy.totalRounds(60, 5, -5));
    }

    @Test
    public void hasNextRound_trueBeforeLastRound() {
        assertTrue(RoundPolicy.hasNextRound(1, 4, 60, 5));
        assertTrue(RoundPolicy.hasNextRound(3, 4, 60, 5));
    }

    @Test
    public void hasNextRound_falseOnLastRound() {
        assertFalse(RoundPolicy.hasNextRound(4, 4, 60, 5));
    }

    @Test
    public void hasNextRound_unlimitedAlwaysTrue() {
        assertTrue(RoundPolicy.hasNextRound(99, 0, 60, 5));
    }

    @Test
    public void hasNextRound_falseWhenRepeatDisabled() {
        assertFalse(RoundPolicy.hasNextRound(1, 4, 60, 0));
        assertFalse(RoundPolicy.hasNextRound(1, 4, 0, 5));
    }

    @Test
    public void hasNextRound_toleratesZeroCurrentRound() {
        assertTrue(RoundPolicy.hasNextRound(0, 2, 60, 5));
    }

    @Test
    public void safeDelayMinutes_usesConfiguredValue() {
        assertEquals(10, RoundPolicy.safeDelayMinutes(10, 5));
    }

    @Test
    public void safeDelayMinutes_zeroOrNegativeUseFallback() {
        assertEquals(5, RoundPolicy.safeDelayMinutes(0, 5));
        assertEquals(5, RoundPolicy.safeDelayMinutes(-3, 5));
    }

    @Test
    public void safeDelayMinutes_neverReturnsZero() {
        assertEquals(1, RoundPolicy.safeDelayMinutes(0, 0));
        assertEquals(1, RoundPolicy.safeDelayMinutes(-1, -1));
    }
}
