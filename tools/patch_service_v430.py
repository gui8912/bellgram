#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.3.0 patches for SMSForegroundService: selectable alarm volume, gradual
volume ramp, and configurable snooze length. Every anchor is asserted."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "app", "src", "main", "java",
                    "com", "saiga", "find", "messagefinder",
                    "SMSForegroundService.java")

CRESCENDO = """    /**
     * modified: v4.3 渐强音量。参考系统时钟 "Gradually increase volume"，
     * 只调 MediaPlayer 自身音量，不反复写 STREAM_ALARM（那是持久化系统设置）。
     * 5 秒 10 步；ringWindowOpen 关闭或 MediaPlayer 被释放后自动停止。
     */
    private void startCrescendo() { // modified
        final int steps = 10; // modified
        final int[] step = {0}; // modified
        crescendoRunnable = new Runnable() { // modified
            @Override // modified
            public void run() { // modified
                if (!alertActive || !ringWindowOpen || mediaPlayer == null) { // modified
                    return; // modified
                } // modified
                step[0]++; // modified
                float volume = Math.min(1.0f, // modified
                        0.25f + 0.75f * (step[0] / (float) steps)) // modified
                        * targetPlayerVolume; // modified
                try { // modified
                    mediaPlayer.setVolume(volume, volume); // modified
                } catch (Exception e) { // modified
                    Log.w(TAG, "crescendo step failed", e); // modified
                    return; // modified
                } // modified
                if (step[0] < steps && alertHandler != null) { // modified
                    alertHandler.postDelayed(this, 500L); // modified
                } // modified
            } // modified
        }; // modified
        if (alertHandler != null) { // modified
            alertHandler.postDelayed(crescendoRunnable, 500L); // modified
        } // modified
    } // modified

    private void startVibration() {"""

PATCHES = [
    (
        "    private static final long NO_TIMEOUT_WAKE_LOCK_MS = 30 * 60 * 1000L;\n"
        "    private static final int FALLBACK_SNOOZE_MINUTES = 5;",
        "    private static final long NO_TIMEOUT_WAKE_LOCK_MS = 30 * 60 * 1000L;\n"
        "    // modified: 稍后提醒时长改由 AppConfig.getSnoozeMinutes() 托管，可在设置页调整",
    ),
    (
        "    private int ringDurationSec = 60;\n"
        "    private int repeatIntervalMin = 5;\n"
        "    private int currentRound = 1;",
        "    private int ringDurationSec = 60;\n"
        "    private int repeatIntervalMin = 5;\n"
        "    private int currentRound = 1;\n"
        "    // modified: v4.3 音量档位 / 渐强音量\n"
        "    private int alarmVolumeLevel = VolumePolicy.DEFAULT_LEVEL; // modified\n"
        "    private boolean crescendoEnabled = false; // modified\n"
        "    private float targetPlayerVolume = 1.0f; // modified\n"
        "    private Runnable crescendoRunnable; // modified",
    ),
    (
        "            int minutes = RoundPolicy.safeDelayMinutes(repeatIntervalMin,\n"
        "                    FALLBACK_SNOOZE_MINUTES); // modified: 规则集中\n"
        "            AlertDispatcher.snoozeFromService(this, minutes); // modified: 避免自重启",
        "            // modified: 稍后提醒时长不再跟随重复间隔，也不再写死 5 分钟\n"
        "            int minutes = AppConfig.get(this).getSnoozeMinutes(); // modified\n"
        "            AlertDispatcher.snoozeFromService(this, minutes); // modified: 避免自重启",
    ),
    (
        "        ringDurationSec = config.getRingDurationSec();\n"
        "        repeatIntervalMin = config.getRepeatIntervalMin();\n"
        "        Log.d(TAG, \"policy: ring=\" + ringEnabled + (quietHoursActive ? \"(quiet hours)\" : \"\")\n"
        "                + \", vibrate=\" + vibrateEnabled\n"
        "                + \", duration=\" + ringDurationSec + \"s, interval=\" + repeatIntervalMin + \"min\"\n"
        "                + \", round=\" + currentRound);",
        "        ringDurationSec = config.getRingDurationSec();\n"
        "        repeatIntervalMin = config.getRepeatIntervalMin();\n"
        "        alarmVolumeLevel = config.getAlarmVolumeLevel(); // modified\n"
        "        crescendoEnabled = config.isCrescendoEnabled(); // modified\n"
        "        Log.d(TAG, \"policy: ring=\" + ringEnabled + (quietHoursActive ? \"(quiet hours)\" : \"\")\n"
        "                + \", vibrate=\" + vibrateEnabled\n"
        "                + \", duration=\" + ringDurationSec + \"s, interval=\" + repeatIntervalMin + \"min\"\n"
        "                + \", volume=\" + alarmVolumeLevel + \"%\" // modified\n"
        "                + \", crescendo=\" + crescendoEnabled // modified\n"
        "                + \", round=\" + currentRound);",
    ),
    (
        "        if (audioManager != null) {\n"
        "            try {\n"
        "                if (!audioManager.isVolumeFixed()) {\n"
        "                    if (!volumeBoosted) {\n"
        "                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);\n"
        "                        volumeBoosted = true;\n"
        "                        // modified: 【v4.2】同步落盘，进程被杀后仍可恢复（onDestroy 不一定执行）\n"
        "                        AlarmVolumeGuard.remember(this, savedAlarmVolume); // modified\n"
        "                    }\n"
        "                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM,\n"
        "                            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);\n"
        "                }\n"
        "            } catch (Exception e) {\n"
        "                Log.w(TAG, \"boost alarm volume failed\", e);\n"
        "            }\n"
        "        }",
        "        if (audioManager != null) {\n"
        "            try {\n"
        "                // modified: v4.3 只有「非跟随系统」档位才写系统设置，且不再强制最大值\n"
        "                if (!audioManager.isVolumeFixed()\n"
        "                        && VolumePolicy.shouldChangeSystemVolume(alarmVolumeLevel)) { // modified\n"
        "                    if (!volumeBoosted) {\n"
        "                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);\n"
        "                        volumeBoosted = true;\n"
        "                        // 【v4.2】同步落盘，进程被杀后仍可恢复（onDestroy 不一定执行）\n"
        "                        AlarmVolumeGuard.remember(this, savedAlarmVolume);\n"
        "                    }\n"
        "                    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM); // modified\n"
        "                    int target = VolumePolicy.targetStreamIndex(maxVolume, alarmVolumeLevel); // modified\n"
        "                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0); // modified\n"
        "                }\n"
        "            } catch (Exception e) {\n"
        "                Log.w(TAG, \"apply alarm volume level failed\", e); // modified\n"
        "            }\n"
        "        }",
    ),
    (
        "            if (audioManager != null && audioManager.isVolumeFixed()) {\n"
        "                mediaPlayer.setVolume(1.0f, 1.0f);\n"
        "            }\n"
        "            mediaPlayer.prepareAsync();\n"
        "            return true;",
        "            // modified: 固定音量设备不能改系统流，只能用播放器自身音量近似档位；\n"
        "            // 渐强音量一律从 25% 起步，5 秒内升到目标值，不反复写系统设置。\n"
        "            targetPlayerVolume = 1.0f; // modified\n"
        "            if (audioManager != null && audioManager.isVolumeFixed() // modified\n"
        "                    && alarmVolumeLevel > VolumePolicy.FOLLOW_SYSTEM) { // modified\n"
        "                targetPlayerVolume = alarmVolumeLevel / 100f; // modified\n"
        "            } // modified\n"
        "            float startVolume = VolumePolicy.initialPlayerVolume(crescendoEnabled) // modified\n"
        "                    * targetPlayerVolume; // modified\n"
        "            mediaPlayer.setVolume(startVolume, startVolume); // modified\n"
        "            mediaPlayer.prepareAsync();\n"
        "            return true;",
    ),
    (
        "        try {\n"
        "            mp.start();\n"
        "            Log.d(TAG, \"alarm playback started\");",
        "        try {\n"
        "            mp.start();\n"
        "            if (crescendoEnabled) { // modified\n"
        "                startCrescendo(); // modified\n"
        "            } // modified\n"
        "            Log.d(TAG, \"alarm playback started\");",
    ),
    ("    private void startVibration() {", CRESCENDO),
    (
        "        if (alertHandler != null) {\n"
        "            alertHandler.removeCallbacksAndMessages(null);\n"
        "        }",
        "        if (alertHandler != null) {\n"
        "            alertHandler.removeCallbacksAndMessages(null);\n"
        "        }\n"
        "        crescendoRunnable = null; // modified",
    ),
]


def main():
    with open(PATH, "r", encoding="utf-8") as f:
        text = f.read()
    for i, (old, new) in enumerate(PATCHES, 1):
        assert text.count(old) == 1, "patch %d anchor count=%d" % (i, text.count(old))
        text = text.replace(old, new, 1)
    with open(PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("applied %d patches to %s" % (len(PATCHES), PATH))


if __name__ == "__main__":
    main()
