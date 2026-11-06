#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Idempotent-ish patch for v4.3.0 string resources.

Every replacement is asserted so a missing anchor fails loudly instead of
silently producing an incomplete resource file.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

NEW_EN = """    <!-- modified: v4.3.0 新增设置项文案；*_key 用作 SharedPreferences 键，禁止翻译 -->
    <string name="pref_category_appearance">Appearance</string>
    <string name="pref_category_appearance_key">appearance_category_key</string>
    <string name="pref_category_repeat">Repeat and snooze</string>
    <string name="pref_category_repeat_key">repeat_category_key</string>
    <string name="theme_mode_key">theme_mode</string>
    <string name="theme_mode_title">Theme</string>
    <string name="alarm_volume_key">alarm_volume_level</string>
    <string name="alarm_volume_title">Alarm volume</string>
    <string name="alarm_volume_hint">How loud the alarm plays. Follow system leaves your phone alarm volume untouched. The first notification tone still follows the system channel.</string>
    <string name="crescendo_enabled_key">crescendo_enabled</string>
    <string name="crescendo_enabled_title">Gradually increase volume</string>
    <string name="crescendo_enabled_on">Starts quietly and ramps up over about 5 seconds</string>
    <string name="crescendo_enabled_off">Plays at the chosen volume immediately</string>
    <string name="snooze_minutes_key">snooze_minutes</string>
    <string name="snooze_minutes_title">Snooze length</string>
    <string name="snooze_minutes_hint">How long to wait after tapping Snooze.</string>
    <string name="volume_key_action_key">volume_key_action</string>
    <string name="volume_key_action_title">Volume button behaviour</string>
    <string name="volume_key_action_hint">What the hardware volume keys do on the full-screen alert.</string>
    <string name="custom_value_dialog_title">Enter a custom value</string>
    <string name="custom_duration_message">Single ring duration, 0 to 120 seconds. 0 = until I stop it.</string>
    <string name="custom_interval_message">Repeat interval, 0 to 60 minutes. 0 = do not repeat.</string>
    <string name="custom_repeat_message">Maximum repeats, 0 to 30. 0 = unlimited.</string>
    <string name="custom_value_invalid">Enter a whole number between %1$d and %2$d</string>
    <string name="custom_duration_summary">Custom: %1$d s</string>
    <string name="custom_interval_summary">Custom: %1$d min</string>
    <string name="custom_repeat_summary">Custom: %1$d times</string>
"""

NEW_ZH = """    <!-- modified: v4.3.0 新增设置项文案；*_key 用作 SharedPreferences 键，禁止翻译 -->
    <string name="pref_category_appearance">外观</string>
    <string name="pref_category_appearance_key">appearance_category_key</string>
    <string name="pref_category_repeat">重复与稍后</string>
    <string name="pref_category_repeat_key">repeat_category_key</string>
    <string name="theme_mode_key">theme_mode</string>
    <string name="theme_mode_title">主题</string>
    <string name="alarm_volume_key">alarm_volume_level</string>
    <string name="alarm_volume_title">闹钟音量</string>
    <string name="alarm_volume_hint">选择闹铃播放的响度。「跟随系统」完全不改动手机闹钟音量。通知响起时的第一声仍由系统通知渠道决定。</string>
    <string name="crescendo_enabled_key">crescendo_enabled</string>
    <string name="crescendo_enabled_title">渐强音量</string>
    <string name="crescendo_enabled_on">先小声，约 5 秒内逐渐升到所选音量</string>
    <string name="crescendo_enabled_off">立即按所选音量播放</string>
    <string name="snooze_minutes_key">snooze_minutes</string>
    <string name="snooze_minutes_title">稍后提醒时长</string>
    <string name="snooze_minutes_hint">点「稍后提醒」之后，隔多久再响一次。</string>
    <string name="volume_key_action_key">volume_key_action</string>
    <string name="volume_key_action_title">音量键行为</string>
    <string name="volume_key_action_hint">全屏告警页上，按下手机实体音量键时执行什么操作。</string>
    <string name="custom_value_dialog_title">输入自定义数值</string>
    <string name="custom_duration_message">单次响铃时长，0 到 120 秒；0 = 一直响到手动停止。</string>
    <string name="custom_interval_message">重复提醒间隔，0 到 60 分钟；0 = 不重复。</string>
    <string name="custom_repeat_message">最多重复次数，0 到 30；0 = 不限。</string>
    <string name="custom_value_invalid">请输入 %1$d 到 %2$d 之间的整数</string>
    <string name="custom_duration_summary">自定义：%1$d 秒</string>
    <string name="custom_interval_summary">自定义：%1$d 分钟</string>
    <string name="custom_repeat_summary">自定义：%1$d 次</string>
"""

TARGETS = [
    ("values/strings.xml", NEW_EN,
     "Alarm tone plays at max volume", "Alarm tone plays at the chosen volume"),
    ("values-zh-rCN/strings.xml", NEW_ZH,
     "以最大音量播放闹钟铃声", "按所选音量播放闹钟铃声"),
]


def main():
    for rel, block, old_ring, new_ring in TARGETS:
        path = os.path.join(RES, rel)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()

        old_ver = '<string name="current_app_version">4.2.0</string>'
        assert old_ver in text, "version anchor missing: " + rel
        text = text.replace(old_ver,
                            '<string name="current_app_version">4.3.0</string>')

        assert old_ring in text, "ring_enabled_on anchor missing: " + rel
        text = text.replace(old_ring, new_ring)

        assert text.count("</resources>") == 1, "unexpected resources close: " + rel
        assert 'name="theme_mode_key"' not in text, "already patched: " + rel
        text = text.replace("</resources>", block + "</resources>")

        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("patched", rel, "new keys:", block.count("<string "))


if __name__ == "__main__":
    main()
