package com.saiga.find.messagefinder;

// modified: v4.3.0 新增 —— 全局初始化入口。
// 主题必须在任何 Activity 的 super.onCreate() 之前生效，否则冷启动第一帧会闪一下亮色背景；
// 音量守护同样放在这里，补上「重启后用户还没打开 App」这一空档。
//
// 【修改 v4.6.3】两处初始化都加了独立 try/catch。原因：
//   Application.onCreate() 抛出的异常没有任何上层可以接住，进程会直接崩溃，
//   而崩溃的是**整个应用进程**——包括静态注册的 SMSReceiver。
//   表现就是「收到短信完全没反应」，同时用户一打开应用就看到「屡次停止运行」。
//   这两步都不是提醒主链路的必需前置：主题失败最多回到系统默认外观，
//   音量还原失败下次启动还会再试（AlarmVolumeGuard 只在真正恢复成功后才清标记）。
//   为它们让整个应用瘫痪，代价完全不成比例。
//
//   同时记录本次/上次进程启动时间，供诊断快照显示。这是「重启后失效」类问题的
//   关键证据：若重启后系统从未拉起过本应用，这两个时间会停留在重启之前。

import android.app.Application; // modified
import android.util.Log; // 新增 v4.6.3

public class BellgramApp extends Application { // modified

    private static final String TAG = "BellgramApp"; // 新增 v4.6.3

    @Override // modified
    public void onCreate() { // modified
        super.onCreate(); // modified
        // 先记时间戳再跑初始化：即使下面某一步抛异常，也能留下「进程启动过」的证据
        SmsTrace.markProcessStart(this); // 新增 v4.6.3
        try { // 新增 v4.6.3
            ThemeHelper.applySavedMode(this); // modified
        } catch (Exception e) { // 新增 v4.6.3
            Log.w(TAG, "apply saved theme failed, fall back to system default", e); // 新增 v4.6.3
        } // 新增 v4.6.3
        try { // 新增 v4.6.3
            AlarmVolumeGuard.restoreIfStale(this); // modified
        } catch (Exception e) { // 新增 v4.6.3
            Log.w(TAG, "restore stale alarm volume failed", e); // 新增 v4.6.3
        } // 新增 v4.6.3
    } // modified
} // modified
