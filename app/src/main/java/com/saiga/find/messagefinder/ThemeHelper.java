package com.saiga.find.messagefinder;

// modified: v4.3.0 新增 —— 明亮 / 暗色 / 跟随系统三档主题的唯一换算入口。
// 参考 Android 官方 AppCompatDelegate 夜间模式用法：设置项持久化在默认 SharedPreferences，
// 冷启动由 BellgramApp 在任何 Activity 创建之前应用，避免第一帧闪白。
import android.content.Context; // modified

import androidx.appcompat.app.AppCompatDelegate; // modified

public final class ThemeHelper { // modified

    public static final String MODE_SYSTEM = "system"; // modified
    public static final String MODE_LIGHT = "light"; // modified
    public static final String MODE_DARK = "dark"; // modified

    private ThemeHelper() { // modified
        // 工具类不允许实例化 // modified
    } // modified

    /** 把持久化字符串换成 AppCompatDelegate 常量；非法值一律按跟随系统处理。 */
    public static int nightModeFor(String mode) { // modified
        if (MODE_LIGHT.equals(mode)) { // modified
            return AppCompatDelegate.MODE_NIGHT_NO; // modified
        } // modified
        if (MODE_DARK.equals(mode)) { // modified
            return AppCompatDelegate.MODE_NIGHT_YES; // modified
        } // modified
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; // modified
    } // modified

    /** 冷启动时调用一次；切换主题时由 SettingsActivity 直接调用 nightModeFor。 */
    public static void applySavedMode(Context context) { // modified
        String mode = AppConfig.get(context).getThemeMode(); // modified
        AppCompatDelegate.setDefaultNightMode(nightModeFor(mode)); // modified
    } // modified
} // modified
