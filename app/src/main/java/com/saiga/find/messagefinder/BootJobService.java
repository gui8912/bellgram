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
            // 【修改 v4.6.9】取回真实启动结果。原实现无视失败一律记「已重新拉起」：
            // Android 12+ 的后台启动前台服务豁免清单里没有 JobScheduler（官方文档核实），
            // 电池优化未关闭时这次启动会被系统拒绝，守护其实没起来，诊断却显示正常，
            // 「开机运行久了偶尔不报警」就查不到根因。现在被拒时如实记录，
            // 并挂一条 1 分钟后的精确闹钟重试——闹钟广播是官方豁免上下文，
            // 不依赖电池优化开关，守护空窗从「最长 15 分钟甚至更久」缩到约 1 分钟。
            boolean requested = GuardService.start(this); // 新增 v4.6.5 // modified: v4.6.9
            if (!wasRunning) { // 新增 v4.6.5
                if (requested) { // 新增 v4.6.9
                    SmsTrace.add(this, "兜底任务拉起守护", // 新增 v4.6.5
                            "执行时守护未运行（进程曾被回收），已请求重新拉起"); // 新增 v4.6.5 // modified: v4.6.9
                } else { // 新增 v4.6.9
                    SmsTrace.add(this, "兜底拉起守护被拒", // 新增 v4.6.9
                            "后台启动受限（电池优化未关闭），改由精确闹钟 1 分钟后重试"); // 新增 v4.6.9
                    AlertScheduler.scheduleGuardRetry(this); // 新增 v4.6.9
                } // 新增 v4.6.9
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
