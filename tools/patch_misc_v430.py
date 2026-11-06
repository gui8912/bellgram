#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4.3.0 small patches: configurable snooze everywhere, volume-key action on
the full-screen alert, and volume restoration on boot. Anchors are asserted."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "app", "src", "main", "java",
                    "com", "saiga", "find", "messagefinder")

VOLUME_KEY_BLOCK = """    /**
     * modified: v4.3 参考 Google Clock / MIUI 时钟的「音量按钮」设置。
     * 默认不拦截（音量键仍正常调音量），也可设为「稍后提醒」或「停止提醒」。
     * 只在全屏告警页生效，避免影响用户其它场景。
     */
    @Override // modified
    public boolean onKeyDown(int keyCode, KeyEvent event) { // modified
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP // modified
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { // modified
            String action = AppConfig.get(this).getVolumeKeyAction(); // modified
            if (AppConfig.ACTION_NONE.equals(action)) { // modified
                return super.onKeyDown(keyCode, event); // modified
            } // modified
            if (AppConfig.ACTION_SNOOZE.equals(action)) { // modified
                snoozeAndFinish(); // modified
            } else { // modified
                AlertDispatcher.stop(this); // modified
            } // modified
            finishSafely(); // modified
            return true; // modified
        } // modified
        return super.onKeyDown(keyCode, event); // modified
    } // modified

    /** 返回键 = 稍后提醒。用 OnBackPressedDispatcher 兼容预测式返回手势。 */"""

PATCHES = [
    ("AlertActivity.java", [
        ("import android.os.Bundle;\nimport android.text.TextUtils;",
         "import android.os.Bundle;\nimport android.text.TextUtils;\n"
         "import android.view.KeyEvent; // modified"),
        ("        int minutes = RoundPolicy.safeDelayMinutes(\n"
         "                AppConfig.get(this).getRepeatIntervalMin(), 5); // modified: 规则集中\n"
         "        AlertDispatcher.snooze(this, minutes);",
         "        // modified: 稍后提醒时长改为独立可配（参考系统时钟的贪睡时长）\n"
         "        int minutes = AppConfig.get(this).getSnoozeMinutes(); // modified\n"
         "        AlertDispatcher.snooze(this, minutes);"),
        ("    /** 返回键 = 稍后提醒。用 OnBackPressedDispatcher 兼容预测式返回手势。 */",
         VOLUME_KEY_BLOCK),
    ]),
    ("AlertActionReceiver.java", [
        ("    /** 用户把重复间隔设为 0（不重复）时，点「稍后提醒」的兜底间隔。 */\n"
         "    private static final int FALLBACK_SNOOZE_MINUTES = 5;",
         "    // modified: 稍后提醒时长统一由 AppConfig.getSnoozeMinutes() 托管"),
        ("                int minutes = RoundPolicy.safeDelayMinutes(\n"
         "                        AppConfig.get(context).getRepeatIntervalMin(),\n"
         "                        FALLBACK_SNOOZE_MINUTES); // modified: 规则集中\n"
         "                AlertDispatcher.snooze(context, minutes);",
         "                // modified: 与告警页 / 服务保持一致，使用独立的贪睡时长设置\n"
         "                int minutes = AppConfig.get(context).getSnoozeMinutes(); // modified\n"
         "                AlertDispatcher.snooze(context, minutes);"),
    ]),
    ("BootReceiver.java", [
        ("            Context app = context.getApplicationContext() != null // modified\n"
         "                    ? context.getApplicationContext() : context; // modified\n"
         "            AlertSession session = AlertSession.get(app); // modified",
         "            Context app = context.getApplicationContext() != null // modified\n"
         "                    ? context.getApplicationContext() : context; // modified\n"
         "            // modified: 重启 / 覆盖安装后用户可能还没打开 App，这里补一次音量还原\n"
         "            AlarmVolumeGuard.restoreIfStale(app); // modified\n"
         "            AlertSession session = AlertSession.get(app); // modified"),
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
