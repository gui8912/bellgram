package com.saiga.find.messagefinder;

// [新增文件 / v4.6.5] 开机兜底调度。
//
// 背景：BOOT_COMPLETED 是应用「开机后自己醒来」的唯一官方入口，但部分 ROM
// （HyperOS 实测）在「自启动被重置」或「应用被判为不活跃」时并不投递它，
// 表现为：手机每天早上自动开机后，应用的进程启动时间一直停在几天前，
// 期间收到带关键词的短信也完全不响，直到用户手动点开应用。
//
// JobScheduler 的持久化任务（setPersisted(true)）由系统在重启后重新调度，
// 即使 BOOT_COMPLETED 没送到应用，系统仍可能按时执行这个 job，从而把进程拉起来。
// 它不是主力路径（周期至少 15 分钟、Doze 下会被顺延），而是第二道保险：
//   BOOT_COMPLETED（即时） → 失败 →
//   JobScheduler 持久任务（≤15 分钟） → 拉起 GuardService → 进程常驻，短信不再延迟
//
// 注意：若用户主动「强行停止」应用，系统会连持久化 job 一起取消——这是
// Android 的语义，代码层面无法绕过，只能靠用户避免强停。

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public final class BootFallbackScheduler { // 新增 v4.6.5

    private static final String TAG = "BootFallback"; // 新增 v4.6.5

    /** 固定 ID：重复 schedule 同一 ID 会覆盖旧任务，不会堆积。 */
    private static final int JOB_ID = 0x0B3117; // 新增 v4.6.5
    /** JobScheduler 允许的最小周期（15 分钟）。 */
    private static final long INTERVAL_MS = 15 * 60 * 1000L; // 新增 v4.6.5

    private BootFallbackScheduler() { // 新增 v4.6.5
        // 工具类不允许实例化
    }

    /**
     * 确保开机兜底任务已注册。可在 Application.onCreate、开机广播里重复调用。
     *
     * <p>所有异常都吞掉：调度失败最多是少一层保险，绝不能让应用启动失败。
     */
    public static void ensureScheduled(Context context) { // 新增 v4.6.5
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) { // 新增 v4.6.5
            return; // 新增 v4.6.5
        } // 新增 v4.6.5
        try { // 新增 v4.6.5
            Context app = context.getApplicationContext() != null // 新增 v4.6.5
                    ? context.getApplicationContext() : context; // 新增 v4.6.5
            JobScheduler scheduler = (JobScheduler) app.getSystemService( // 新增 v4.6.5
                    Context.JOB_SCHEDULER_SERVICE); // 新增 v4.6.5
            if (scheduler == null) { // 新增 v4.6.5
                return; // 新增 v4.6.5
            } // 新增 v4.6.5
            // 已有挂起的同一任务就不再重建，避免每次冷启动都把周期往后推
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N // 新增 v4.6.5
                    && scheduler.getPendingJob(JOB_ID) != null) { // 新增 v4.6.5
                return; // 新增 v4.6.5
            } // 新增 v4.6.5
            JobInfo job = new JobInfo.Builder(JOB_ID, // 新增 v4.6.5
                    new ComponentName(app, BootJobService.class)) // 新增 v4.6.5
                    .setPersisted(true) // 重启后由系统重新调度，这是兜底的关键
                    .setPeriodic(INTERVAL_MS) // 新增 v4.6.5
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE) // 不联网也能跑
                    .build(); // 新增 v4.6.5
            int result = scheduler.schedule(job); // 新增 v4.6.5
            if (result == JobScheduler.RESULT_SUCCESS) { // 新增 v4.6.5
                Log.i(TAG, "boot fallback job scheduled"); // 新增 v4.6.5
            } else { // 新增 v4.6.5
                Log.w(TAG, "boot fallback job rejected: " + result); // 新增 v4.6.5
            } // 新增 v4.6.5
        } catch (Exception e) { // 新增 v4.6.5
            Log.w(TAG, "schedule boot fallback failed", e); // 新增 v4.6.5
        } // 新增 v4.6.5
    } // 新增 v4.6.5

    /**
     * 【新增 v4.6.5】诊断用：兜底任务是否已在系统里注册。
     *
     * <p>API 24 以下拿不到 pending job 的查询接口，返回 true（不误报）。
     */
    public static boolean isScheduled(Context context) { // 新增 v4.6.5
        try { // 新增 v4.6.5
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // 新增 v4.6.5
                return true; // 新增 v4.6.5
            } // 新增 v4.6.5
            Context app = context.getApplicationContext() != null // 新增 v4.6.5
                    ? context.getApplicationContext() : context; // 新增 v4.6.5
            JobScheduler scheduler = (JobScheduler) app.getSystemService( // 新增 v4.6.5
                    Context.JOB_SCHEDULER_SERVICE); // 新增 v4.6.5
            return scheduler != null && scheduler.getPendingJob(JOB_ID) != null; // 新增 v4.6.5
        } catch (Exception e) { // 新增 v4.6.5
            Log.w(TAG, "query boot fallback failed", e); // 新增 v4.6.5
            return false; // 新增 v4.6.5
        } // 新增 v4.6.5
    } // 新增 v4.6.5
} // 新增 v4.6.5
