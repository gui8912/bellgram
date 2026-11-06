package com.saiga.find.messagefinder;

// modified: v4.3.0 新增 —— 闹钟音量档位的纯 Java 规则单测（不依赖 Android 设备）。
import org.junit.Test; // modified

import static org.junit.Assert.assertEquals; // modified
import static org.junit.Assert.assertFalse; // modified
import static org.junit.Assert.assertTrue; // modified

public class VolumePolicyTest { // modified

    @Test // modified
    public void sanitize_fallsBackOnUnknownLevel() { // modified
        assertEquals(80, VolumePolicy.sanitize(80)); // modified
        assertEquals(0, VolumePolicy.sanitize(0)); // modified
        assertEquals(100, VolumePolicy.sanitize(100)); // modified
        assertEquals(VolumePolicy.DEFAULT_LEVEL, VolumePolicy.sanitize(37)); // modified
        assertEquals(VolumePolicy.DEFAULT_LEVEL, VolumePolicy.sanitize(-5)); // modified
    } // modified

    @Test // modified
    public void followSystem_neverWritesSystemVolume() { // modified
        assertFalse(VolumePolicy.shouldChangeSystemVolume(0)); // modified
        assertEquals(0, VolumePolicy.targetStreamIndex(15, 0)); // modified
        assertTrue(VolumePolicy.shouldChangeSystemVolume(60)); // modified
        assertTrue(VolumePolicy.shouldChangeSystemVolume(100)); // modified
    } // modified

    @Test // modified
    public void targetIndex_scalesByMaxVolume() { // modified
        assertEquals(6, VolumePolicy.targetStreamIndex(15, 40)); // modified
        assertEquals(9, VolumePolicy.targetStreamIndex(15, 60)); // modified
        assertEquals(12, VolumePolicy.targetStreamIndex(15, 80)); // modified
        assertEquals(15, VolumePolicy.targetStreamIndex(15, 100)); // modified
        assertEquals(7, VolumePolicy.targetStreamIndex(7, 100)); // modified
    } // modified

    @Test // modified
    public void targetIndex_neverMutesDeviceOrExceedsMax() { // modified
        assertEquals(1, VolumePolicy.targetStreamIndex(1, 40)); // modified: 至少留 1 格
        assertEquals(0, VolumePolicy.targetStreamIndex(0, 80)); // modified: 设备无音量档位
        assertEquals(3, VolumePolicy.targetStreamIndex(3, 100)); // modified: 不会超过 max
        assertEquals(12, VolumePolicy.targetStreamIndex(15, 37)); // modified: 非法档位回退 80%
    } // modified

    @Test // modified
    public void crescendo_startsQuietly() { // modified
        assertEquals(0.25f, VolumePolicy.initialPlayerVolume(true), 0.0001f); // modified
        assertEquals(1.0f, VolumePolicy.initialPlayerVolume(false), 0.0001f); // modified
    } // modified
} // modified
