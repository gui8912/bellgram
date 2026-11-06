package com.saiga.find.messagefinder;

// modified: v4.3.0 新增 —— 全局初始化入口。
// 主题必须在任何 Activity 的 super.onCreate() 之前生效，否则冷启动第一帧会闪一下亮色背景；
// 音量守护同样放在这里，补上「重启后用户还没打开 App」这一空档。
import android.app.Application; // modified

public class BellgramApp extends Application { // modified

    @Override // modified
    public void onCreate() { // modified
        super.onCreate(); // modified
        ThemeHelper.applySavedMode(this); // modified
        AlarmVolumeGuard.restoreIfStale(this); // modified
    } // modified
} // modified
