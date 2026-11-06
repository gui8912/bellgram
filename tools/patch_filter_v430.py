#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.3.0 SmsForwarder-inspired features: a master switch and exclusion
keywords. Small, low-risk additions reusing the existing config + matcher."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java",
                    "com", "saiga", "find", "messagefinder")

EXCLUSION_SUMMARY = """            bindExclusionSummary(); // modified
        }

        /** modified: 排除关键词摘要 —— 未设置给提示，已设置显示当前词表。 */
        private void bindExclusionSummary() { // modified
            EditTextPreference pref = findPreference(getString(R.string.exclude_keywords_key)); // modified
            if (pref == null) { // modified
                return; // modified
            } // modified
            Preference.SummaryProvider<EditTextPreference> provider = p -> { // modified
                String value = p.getText(); // modified
                if (TextUtils.isEmpty(value)) { // modified
                    return getString(R.string.exclude_keywords_empty); // modified
                } // modified
                return getString(R.string.exclude_keywords_value, value); // modified
            }; // modified
            pref.setSummaryProvider(provider); // modified
        } // modified"""

PATCHES = [
    ("AppConfig.java", [
        ("    public static final String ACTION_STOP = \"stop\"; // modified",
         "    public static final String ACTION_STOP = \"stop\"; // modified\n"
         "    // modified: v4.3 借鉴 SmsForwarder：提醒总开关 + 排除关键词\n"
         "    private static final String KEY_ALERT_ENABLED = \"alert_enabled\"; // modified\n"
         "    private static final String KEY_EXCLUDE_KEYWORDS = \"exclude_keywords\"; // modified"),
        ("    /** modified: v4.3 全屏告警页音量键行为：none / snooze / stop。 */\n"
         "    public String getVolumeKeyAction() { // modified\n"
         "        String value = safeGetString(KEY_VOLUME_KEY_ACTION, ACTION_NONE); // modified\n"
         "        if (ACTION_SNOOZE.equals(value) || ACTION_STOP.equals(value)) { // modified\n"
         "            return value; // modified\n"
         "        } // modified\n"
         "        return ACTION_NONE; // modified\n"
         "    } // modified",
         "    /** modified: v4.3 全屏告警页音量键行为：none / snooze / stop。 */\n"
         "    public String getVolumeKeyAction() { // modified\n"
         "        String value = safeGetString(KEY_VOLUME_KEY_ACTION, ACTION_NONE); // modified\n"
         "        if (ACTION_SNOOZE.equals(value) || ACTION_STOP.equals(value)) { // modified\n"
         "            return value; // modified\n"
         "        } // modified\n"
         "        return ACTION_NONE; // modified\n"
         "    } // modified\n"
         "\n"
         "    /** modified: 借鉴 SmsForwarder「转发功能总开关」；默认开启，关闭后完全不触发提醒。 */\n"
         "    public boolean isAlertEnabled() { // modified\n"
         "        return safeGetBoolean(KEY_ALERT_ENABLED, true); // modified\n"
         "    } // modified\n"
         "\n"
         "    /** modified: 借鉴 SmsForwarder 过滤规则；排除词复用关键词的归一化解析。 */\n"
         "    public Set<String> getExcludeKeywords() { // modified\n"
         "        String raw = safeGetString(KEY_EXCLUDE_KEYWORDS, \"\"); // modified\n"
         "        if (raw == null || raw.trim().isEmpty()) { // modified\n"
         "            return Collections.emptySet(); // modified\n"
         "        } // modified\n"
         "        return Collections.unmodifiableSet( // modified\n"
         "                new LinkedHashSet<>(KeywordMatcher.parseKeywords(raw))); // modified\n"
         "    } // modified"),
    ]),
    ("KeywordMatcher.java", [
        ("    public static String findMatch(Collection<String> keywords, String body) {\n"
         "        return findMatch(keywords, body, false); // modified: 保留原签名，默认“任意命中”\n"
         "    }",
         "    public static String findMatch(Collection<String> keywords, String body) {\n"
         "        return findMatch(keywords, body, false); // modified: 保留原签名，默认“任意命中”\n"
         "    }\n"
         "\n"
         "    /**\n"
         "     * modified: v4.3 借鉴 SmsForwarder 的过滤规则。排除词用「任意命中即排除」，\n"
         "     * 复用 findMatch 的归一化逻辑，避免正反两套匹配规则不一致。\n"
         "     *\n"
         "     * @return 命中的排除词；未命中返回 null\n"
         "     */\n"
         "    public static String findExclusion(Collection<String> exclusions, String body) { // modified\n"
         "        return findMatch(exclusions, body, false); // modified\n"
         "    } // modified"),
    ]),
    ("SMSReceiver.java", [
        ("    private void handleSms(Context context, Intent intent) {\n"
         "        AppConfig config = AppConfig.get(context);\n"
         "        Set<String> keywords = config.getKeywords();",
         "    private void handleSms(Context context, Intent intent) {\n"
         "        AppConfig config = AppConfig.get(context);\n"
         "        // modified: 借鉴 SmsForwarder「转发功能总开关」，关闭后所有短信直接忽略\n"
         "        if (!config.isAlertEnabled()) { // modified\n"
         "            Log.d(TAG, \"alerts paused by master switch, skip\"); // modified\n"
         "            return; // modified\n"
         "        } // modified\n"
         "        Set<String> keywords = config.getKeywords();"),
        ("        if (isDuplicate(sender, body, matchedKeyword)) {",
         "        // modified: 借鉴 SmsForwarder 的过滤规则：先命中再排除，营销/验证码短信不会吵醒用户\n"
         "        String excludedBy = KeywordMatcher.findExclusion( // modified\n"
         "                config.getExcludeKeywords(), body); // modified\n"
         "        if (excludedBy != null) { // modified\n"
         "            Log.d(TAG, \"excluded by keyword: \" + excludedBy); // modified\n"
         "            return; // modified\n"
         "        } // modified\n"
         "\n"
         "        if (isDuplicate(sender, body, matchedKeyword)) {"),
    ]),
    ("SettingsActivity.java", [
        ("import androidx.preference.ListPreference;",
         "import androidx.preference.EditTextPreference; // modified\n"
         "import androidx.preference.ListPreference;"),
        ("            bindNumericSummary(R.string.max_repeat_key, R.string.custom_repeat_summary); // modified\n"
         "        }",
         "            bindNumericSummary(R.string.max_repeat_key, R.string.custom_repeat_summary); // modified\n"
         + EXCLUSION_SUMMARY),
    ]),
]


def main():
    for filename, patches in PATCHES:
        path = os.path.join(JAVA, filename)
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        for i, (old, new) in enumerate(patches, 1):
            assert text.count(old) == 1, "%s patch %d anchor count=%d" % (
                filename, i, text.count(old))
            text = text.replace(old, new, 1)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("patched %s (%d replacements)" % (filename, len(patches)))


if __name__ == "__main__":
    main()
