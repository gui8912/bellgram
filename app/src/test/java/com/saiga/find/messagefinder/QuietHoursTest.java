package com.saiga.find.messagefinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link QuietHours} 的单元测试。
 *
 * <p>夜间静音是“跨零点”逻辑，最容易写错的三个点都在这里覆盖：
 * 起止点（含头不含尾）、跨零点、未启用。
 */
public class QuietHoursTest {

    @Test
    public void isActive_normalRangeInsideWindow() {
        assertTrue(QuietHours.isActive(1, 6, 3));
    }

    @Test
    public void isActive_normalRangeBoundariesAreHalfOpen() {
        assertTrue(QuietHours.isActive(1, 6, 1));   // 起始点含
        assertFalse(QuietHours.isActive(1, 6, 6));  // 结束点不含
    }

    @Test
    public void isActive_crossMidnightLateEvening() {
        // 22:00 - 07:00：23 点应静音
        assertTrue(QuietHours.isActive(22, 7, 23));
    }

    @Test
    public void isActive_crossMidnightEarlyMorning() {
        assertTrue(QuietHours.isActive(22, 7, 0));
        assertTrue(QuietHours.isActive(22, 7, 6));
        assertFalse(QuietHours.isActive(22, 7, 7));  // 07:00 起恢复响铃
    }

    @Test
    public void isActive_crossMidnightDaytimeIsNotQuiet() {
        assertFalse(QuietHours.isActive(22, 7, 12));
        assertFalse(QuietHours.isActive(22, 7, 21));
    }

    @Test
    public void isActive_disabledWhenNegative() {
        assertFalse(QuietHours.isActive(QuietHours.DISABLED_START, QuietHours.DISABLED_END, 23));
        assertFalse(QuietHours.isActive(-1, 7, 23));
    }

    @Test
    public void isActive_sameStartEndMeansDisabled() {
        // 起止相同不能理解成“全天静音”，否则用户会以为闹铃坏了
        assertFalse(QuietHours.isActive(22, 22, 22));
        assertFalse(QuietHours.isActive(0, 0, 0));
    }

    @Test
    public void normalizeHour_wrapsOutOfRangeValues() {
        assertEquals(0, QuietHours.normalizeHour(24));
        assertEquals(23, QuietHours.normalizeHour(-1));
        assertEquals(5, QuietHours.normalizeHour(29));
    }

    @Test
    public void describe_formatsTwoDigitRange() {
        assertEquals("22:00–07:00", QuietHours.describe(22, 7));
        assertEquals("01:00–06:00", QuietHours.describe(1, 6));
    }

    @Test
    public void describe_returnsEmptyWhenDisabled() {
        assertTrue(QuietHours.describe(-1, -1).isEmpty());
        assertTrue(QuietHours.describe(22, 22).isEmpty());
    }
}
