package com.saiga.find.messagefinder;

// [新增文件 / v4.6.5] 开机兜底任务的执行体。
//
// 由 BootFallbackScheduler 用 setPersisted(true) 注册，系统在重启后会重新调度它。
// 任务本身只做一件事：把 GuardService 拉起来，让进程回到「常驻」状态。
// 不读配置、不碰短信、不弹任何界面；失败也只记一行日志。

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

public class BootJobService extends JobService { // 新增 v4.6.5

    private static final String TAG = "BootJobService"; // 新增 v4.6.5

    @Override
    public boolean onStartJob(JobParameters params) { // 新增 v4.6.5
        try { // 新增 v4.6.5
            // 【新增 v4.6.5】健康自检：只有发现守护本来没在跑时才拉起并记一条诊断。
            // 守护正常时这里什么都不做，避免每 15 分钟往 30 条环形轨迹里刷一条。
            boolean wasRunning = GuardService.isRunning(); // 新增 v4.6.5
            GuardService.start(this); // 新增 v4.6.5
            if (!wasRunning) { // 新增 v4.6.5
                SmsTrace.add(this, "兜底任务拉起守护", // 新增 v4.6.5
                        "执行时守护未运行（进程曾被回收），已重新拉起"); // 新增 v4.6.5
            } // 新增 v4.6.5
        } catch (Exception e) { // 新增 v4.6.5
            Log.w(TAG, "boot fallback start guard failed", e); // 新增 v4.6.5
        } // 新增 v4.6.5
        // 不重试：下一轮周期会再来一次，没必要让系统立刻重排
        jobFinished(params, false); // 新增 v4.6.5
        return false; // 新增 v4.6.5
    } // 新增 v4.6.5

    @Override
    public boolean onStopJob(JobParameters params) { // 新增 v4.6.5
        return false; // 新增 v4.6.5
    } // 新增 v4.6.5
} // 新增 v4.6.5
