package com.saiga.find.messagefinder;

// [新增文件 / v4.6.3] 常驻守护服务。
//
// 为什么需要它（2026-11-09 实测）：
//   HyperOS 把本应用判定为「不活跃」后，会把 SMS_RECEIVED 广播压进队列延迟投递，
//   实测延迟 2~3 分钟，且延迟上限不可控。触发条件不是权限（RECEIVE_SMS 已授予、
//   电池优化已是「无限制」、自启动已开启、全屏权限允许），而是**应用进程是否存在**：
//   模拟一次提醒把前台服务跑起来后，真实短信立刻能被处理；服务结束、进程被回收后，
//   广播又变回排队状态，直到用户打开「应用详情」等操作把进程拉起才补投。
//
// 做法：常驻一个 specialUse 类型的前台服务，让进程不进入 cached 状态。
// 短信广播到达时直接交给已有进程，不再经过「先新建进程」这一步，ROM 也就无从延迟。
//
// 刻意保持极轻：
//   · 不做任何循环、不持 WakeLock、不定时器、不联网；
//   · 通知渠道用 IMPORTANCE_MIN，无声、无振动、不上锁屏、无角标；
//   · 这不是保活黑科技，只是让系统知道「这个应用正在履行用户明确要求的职能」。
//
// 合规性：Android 14+ 的前台服务必须声明类型，这里用 specialUse 并在清单里
// 写明子类型（本应用不属于 mediaPlayback / dataSync 等任何一类）。
// Android 15 允许从 BOOT_COMPLETED 启动 specialUse 类型的前台服务，
// 而 dataSync / mediaPlayback 等类型已被禁止——这也是选 specialUse 的原因之一。

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class GuardService extends Service { // 新增 v4.6.3

    private static final String TAG = "GuardService"; // 新增 v4.6.3

    public static final String ACTION_START = // 新增 v4.6.3
            "com.saiga.find.messagefinder.action.GUARD_START"; // 新增 v4.6.3
    public static final String ACTION_STOP = // 新增 v4.6.3
            "com.saiga.find.messagefinder.action.GUARD_STOP"; // 新增 v4.6.3

    /** 与提醒通知（123）分开，避免守护通知把正在响的提醒覆盖掉。 */
    public static final int NOTIFICATION_ID = 1001; // 新增 v4.6.3

    /** 供诊断快照显示，不参与任何逻辑判断。 */
    private static volatile boolean running = false; // 新增 v4.6.3

    public static boolean isRunning() { // 新增 v4.6.3
        return running;
    }

    /**
     * 请求启动守护。
     *
     * <p>所有调用点都必须容忍失败：Android 12+ 在应用处于后台时启动前台服务会抛
     * {@code ForegroundServiceStartNotAllowedException}。这不是错误，也不该弹提示——
     * 等用户下次打开应用、或开机广播把应用拉起时自然会再试一次。
     */
    public static void start(Context context) { // 新增 v4.6.3
        if (context == null) { // 新增 v4.6.3
            return; // 新增 v4.6.3
        } // 新增 v4.6.3
        try { // 新增 v4.6.3
            Context app = context.getApplicationContext() != null // 新增 v4.6.3
                    ? context.getApplicationContext() : context; // 新增 v4.6.3
            Intent intent = new Intent(app, GuardService.class); // 新增 v4.6.3
            intent.setAction(ACTION_START); // 新增 v4.6.3
            ContextCompat.startForegroundService(app, intent); // 新增 v4.6.3
        } catch (Exception e) { // 新增 v4.6.3
            // 后台启动受限是正常现象，不是缺陷；不写诊断，避免把日志刷满
            Log.w(TAG, "start guard rejected, will retry later", e); // 新增 v4.6.3
        } // 新增 v4.6.3
    } // 新增 v4.6.3

    /** 用户主动关闭时调用（当前版本未接入 UI，保留给后续设置项）。 */
    public static void stop(Context context) { // 新增 v4.6.3
        if (context == null) { // 新增 v4.6.3
            return; // 新增 v4.6.3
        } // 新增 v4.6.3
        try { // 新增 v4.6.3
            Context app = context.getApplicationContext() != null // 新增 v4.6.3
                    ? context.getApplicationContext() : context; // 新增 v4.6.3
            Intent intent = new Intent(app, GuardService.class); // 新增 v4.6.3
            intent.setAction(ACTION_STOP); // 新增 v4.6.3
            app.startService(intent); // 新增 v4.6.3
        } catch (Exception e) { // 新增 v4.6.3
            Log.w(TAG, "stop guard failed", e); // 新增 v4.6.3
        } // 新增 v4.6.3
    } // 新增 v4.6.3

    @Override
    public IBinder onBind(Intent intent) { // 新增 v4.6.3
        return null; // 新增 v4.6.3
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { // 新增 v4.6.3
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { // 新增 v4.6.3
            running = false; // 新增 v4.6.3
            removeForeground(); // 新增 v4.6.3
            stopSelf(); // 新增 v4.6.3
            return START_NOT_STICKY; // 新增 v4.6.3
        } // 新增 v4.6.3
        if (!enterForeground()) { // 新增 v4.6.3
            // 通知发不出去时（用户关掉了应用通知总开关）绝不能让服务空转：
            // startForegroundService 之后 5 秒内没有 startForeground，系统会抛 ANR。
            running = false; // 新增 v4.6.3
            stopSelf(); // 新增 v4.6.3
            return START_NOT_STICKY; // 新增 v4.6.3
        } // 新增 v4.6.3
        boolean wasRunning = running; // 新增 v4.6.3
        running = true; // 新增 v4.6.3
        if (!wasRunning) { // 新增 v4.6.3
            // 只在「从没运行 → 运行」时记一条，避免每次 onStartCommand 都刷轨迹
            SmsTrace.add(this, "后台守护已启动", // 新增 v4.6.3
                    "常驻前台服务，短信广播不再被系统延迟"); // 新增 v4.6.3
        } // 新增 v4.6.3
        Log.i(TAG, "guard service running"); // 新增 v4.6.3
        // START_STICKY：被系统回收后自动重建。用户「强行停止」则不会重建，这是系统语义。
        return START_STICKY; // 新增 v4.6.3
    } // 新增 v4.6.3

    private boolean enterForeground() { // 新增 v4.6.3
        try { // 新增 v4.6.3
            NotificationHelper.ensureChannels(this); // 新增 v4.6.3
            Notification notification = NotificationHelper.buildGuardNotification(this); // 新增 v4.6.3
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // 新增 v4.6.3
                // Android 14+ 必须声明类型，否则 startForeground 抛 MissingForegroundServiceTypeException
                startForeground(NOTIFICATION_ID, notification, // 新增 v4.6.3
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE); // 新增 v4.6.3
            } else { // 新增 v4.6.3
                startForeground(NOTIFICATION_ID, notification); // 新增 v4.6.3
            } // 新增 v4.6.3
            return true; // 新增 v4.6.3
        } catch (Exception e) { // 新增 v4.6.3
            Log.e(TAG, "enter foreground failed", e); // 新增 v4.6.3
            return false; // 新增 v4.6.3
        } // 新增 v4.6.3
    } // 新增 v4.6.3

    private void removeForeground() { // 新增 v4.6.3
        try { // 新增 v4.6.3
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // 新增 v4.6.3
                stopForeground(STOP_FOREGROUND_REMOVE); // 新增 v4.6.3
            } else { // 新增 v4.6.3
                stopForeground(true); // 新增 v4.6.3
            } // 新增 v4.6.3
        } catch (Exception e) { // 新增 v4.6.3
            Log.w(TAG, "stop foreground failed", e); // 新增 v4.6.3
        } // 新增 v4.6.3
    } // 新增 v4.6.3

    @Override
    public void onDestroy() { // 新增 v4.6.3
        running = false; // 新增 v4.6.3
        super.onDestroy(); // 新增 v4.6.3
    } // 新增 v4.6.3
} // 新增 v4.6.3
