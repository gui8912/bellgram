#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.3.0 second string patch: SmsForwarder-inspired master switch and
exclusion keywords. Inserted before </resources> with an existence assert."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

NEW_EN = """    <!-- modified: v4.3 借鉴 SmsForwarder「转发总开关 / 过滤规则」的两项设置 -->
    <string name="alert_enabled_key">alert_enabled</string>
    <string name="alert_enabled_title">Reminder master switch</string>
    <string name="alert_enabled_on">Keyword alerts are active</string>
    <string name="alert_enabled_off">Paused: no ringing, vibration or repeat alarms</string>
    <string name="exclude_keywords_key">exclude_keywords</string>
    <string name="exclude_keywords_title">Exclusion keywords</string>
    <string name="exclude_keywords_hint">A message containing any of these words is ignored. Separate multiple words with commas, for example: unsubscribe, verification code.</string>
    <string name="exclude_keywords_empty">Not set (nothing is excluded)</string>
    <string name="exclude_keywords_value">Excluding: %1$s</string>
"""

NEW_ZH = """    <!-- modified: v4.3 借鉴 SmsForwarder「转发总开关 / 过滤规则」的两项设置 -->
    <string name="alert_enabled_key">alert_enabled</string>
    <string name="alert_enabled_title">提醒总开关</string>
    <string name="alert_enabled_on">正在按关键词监控短信</string>
    <string name="alert_enabled_off">已暂停：不响铃、不震动、不重复提醒</string>
    <string name="exclude_keywords_key">exclude_keywords</string>
    <string name="exclude_keywords_title">排除关键词</string>
    <string name="exclude_keywords_hint">短信正文含有其中任意一个词就直接忽略，多个词用逗号分隔，例如：退订、验证码、营销。</string>
    <string name="exclude_keywords_empty">未设置（不排除任何短信）</string>
    <string name="exclude_keywords_value">当前排除：%1$s</string>
"""

TARGETS = [
    ("values/strings.xml", NEW_EN),
    ("values-zh-rCN/strings.xml", NEW_ZH),
]


def main():
    for rel, block in TARGETS:
        path = os.path.join(RES, rel)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        assert 'name="exclude_keywords_key"' not in text, "already patched: " + rel
        assert text.count("</resources>") == 1, "unexpected resources close: " + rel
        text = text.replace("</resources>", block + "</resources>")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("patched", rel, "new keys:", block.count("<string "))


if __name__ == "__main__":
    main()
