package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构的核心调度器：所有「要提醒」的请求都从这里进。
//
// 旧链路（v3）：SMSReceiver -> startForegroundService -> 服务里 Handler 等 5 分钟再响。
//   在 targetSdk 31+ 上，第一步就会被系统以 ForegroundServiceStartNotAllowedException 拒绝；
//   即使侥幸起来，服务被回收后 Handler 消失，重复提醒也就断了。
// 新链路（v4）：
//   SMSReceiver / RepeatAlarmReceiver
//     -> AlertDispatcher.postRound() 发高优先级通知（自带闹铃音 + 全屏 Intent）
//     -> 系统拉起 AlertActivity（用户可见）
//     -> AlertActivity 合法启动 SMSForegroundService，播放这一轮铃声/震动
//     -> 单轮结束 -> AlertScheduler.setAlarmClock() 安排下一轮 -> 服务 stopSelf
//   全程没有任何「后台启动前台服务」，符合 Android 12~16 的行为变更要求。
//
// 设计参考：AOSP 系统闹钟（DeskClock）与 Android 官方《Notifications / Full-screen intents》
// 的通行做法——把「通知」当作唯一可靠的唤醒入口，把「播放」放在用户可见组件里。
// 本项目只借鉴该架构思想，未复制第三方代码。

import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class AlertDispatcher {

    private static final String TAG = "AlertDispatcher";

    // modified: 【v4.2】一次会话状态迁移是「cancel 闹钟 -> 改会话 -> 发通知 -> 挂新闹钟」
    // 的长链，原来各入口完全无锁。短信广播与闹钟广播并发时会出现：A 读到旧会话、
    // B 开启新会话、A 再把新会话的 round 改成旧轮次，并用 FLAG_UPDATE_CURRENT
    // 覆写同一个 PendingIntent，导致某一轮闹钟丢失或错轮。
    // 所有 public 入口都声明 synchronized（静态方法锁 AlertDispatcher.class，共享同一把锁），
    // 保证整条迁移不可分割；调用链内部没有回环，不会死锁。

    private AlertDispatcher() {
        // 工具类不允许实例化
    }

    /**
     * 收到一条命中规则的短信：开启新会话并发出第一轮提醒。
     */
    public static synchronized void onSmsMatched(Context context, String sender, String keyword, // modified
                                    String person, String body) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            // 新短信到来时取消上一轮尚未触发的闹钟，避免两条短信的提醒交织叠加
            AlertScheduler.cancel(app); // modified: 新增
            AlertSession session = AlertSession.get(app);
            session.start(sender, keyword, person, body,
                    AppConfig.get(app).getTotalRounds()); // modified: 新增
            postRound(app, 1, app.getString(R.string.notif_status_round, 1));
            scheduleFollowUp(app, 1); // modified: 乐观调度，服务没起来也能有下一轮
        } catch (Exception e) {
            Log.e(TAG, "dispatch sms alert failed", e);
        }
    }

    /**
     * 精确闹钟到点：发出下一轮提醒。
     *
     * @param round 即将开始的轮次
     */
    public static synchronized void onRepeatAlarm(Context context, int round) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertSession session = AlertSession.get(app);
            if (!session.isActive()) {
                Log.i(TAG, "session already stopped, skip repeat");
                return;
            }
            int total = session.getTotalRounds();
            if (total > 0 && round > total) {
                finish(app, Math.max(1, round - 1));
                return;
            }
            // modified: 【v4.2】用户在这轮等待期间把「重复间隔」改成 0 或关掉响铃时，
            // 已注册进 AlarmManager 的闹钟不会自动撤销，到点仍会触发。原实现不复核配置，
            // 于是「我明明关了重复，它又响了一轮」。这里用实时配置再判一次。
            //
            // 但有一类例外必须放行：用户主动点的「稍后提醒」。AppConfig 里 interval=0 时
            // snooze 会用兜底间隔挂闹钟，若无条件复核就会把用户明确要求的这一轮也拦掉，
            // 属于本次修复自己引入的回归。故用一次性标记区分「自动重复」与「用户主动」。
            boolean userSnoozed = session.consumeSnoozed(); // modified: 区分来源
            if (!userSnoozed) { // modified
                AppConfig config = AppConfig.get(app); // modified
                if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total, // modified
                        config.getRingDurationSec(), config.getRepeatIntervalMin())) { // modified
                    Log.i(TAG, "repeat disabled by current config, finish session"); // modified
                    finish(app, Math.max(1, round - 1)); // modified
                    return;
                }
            }
            session.setRound(round);
            postRound(app, round, app.getString(R.string.notif_status_round, round));
            scheduleFollowUp(app, round); // modified: 每一轮发出通知时就挂好下一轮闹钟
        } catch (Exception e) {
            Log.e(TAG, "dispatch repeat alert failed", e);
        }
    }

    /**
     * 用户点「停止」：取消闹钟、结束会话、撤通知、并让正在播放的服务停下来。
     */
    public static synchronized void stop(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertScheduler.cancel(app);
            AlertSession.get(app).stop();
            NotificationHelper.cancel(app);
            requestServiceStop(app);
        } catch (Exception e) {
            Log.e(TAG, "stop alert failed", e);
        }
    }

    /**
     * 用户点「稍后提醒」：立即静音，过一个间隔再响一轮。
     *
     * <p>与「停止」的区别：会话保持 active，且 {@link AlertSession#ensureRound(int)}
     * 保证即使用完了重复次数，用户主动要求的这一轮也一定会响。
     */
    public static synchronized void snooze(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, true);
    }

    /**
     * 供服务自身处理 v3 残留通知的「稍后提醒」时使用。
     * 服务已经自行停止，不需要（也不应该）再给自己发一次 startService，
     * 否则 stopSelf 之后又收到启动请求会触发一次无意义的 onCreate / onDestroy。
     */
    public static synchronized void snoozeFromService(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, false);
    }

    private static synchronized void snoozeInternal(Context context, int delayMinutes, // modified
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            final int safeDelay = Math.max(1, delayMinutes);
            AlertSession session = AlertSession.get(app);
            // modified: 【v4.2 独立复审】原实现无条件 setActive(true)。
            // 「已结束」通知也带「稍后提醒」按钮，用户点一下就会把 stop() 过的死会话
            // 重新激活并挂新闹钟，状态机出现「死会话复活」。这里先校验。
            if (!session.isActive()) { // modified
                Log.i(TAG, "session already finished, ignore snooze"); // modified
                return;
            }
            AlertScheduler.cancel(app); // modified
            int nextRound = session.getRound() + 1;
            session.ensureRound(nextRound);
            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            session.markSnoozed(); // modified: 【v4.2】标记下一轮不受配置复核影响
            if (stopService) {
                // modified: v4.5.0 用「静音」而不是「停止」请求服务收尾。
                // handleLocalStop() 走的是 stopForeground(REMOVE)，会把下面刚发出的
                // 「已稍后提醒 · N 分钟后再次响铃」通知一起撤掉 —— 用户点完稍后提醒，
                // 通知立刻消失，看起来很像是操作没生效。ACTION_SILENCE 走 DETACH，
                // 停掉铃声但保留通知，语义与「稍后提醒」完全一致。
                requestServiceSilence(app); // modified
            }
            // 用静音渠道覆盖通知：只改文案，不再重复出声
            postQuiet(app, app.getString(R.string.notif_status_snoozed, safeDelay));
        } catch (Exception e) {
            Log.e(TAG, "snooze alert failed", e);
        }
    }

    /**
     * 所有轮次结束：留一条「已结束」通知供用户回看，并停掉闹钟与服务。
     */
    public static synchronized void finish(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, true);
    }

    /** 服务自己收尾时调用，避免给自己发 STOP 造成无意义的重启。 */
    public static synchronized void finishFromService(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, false);
    }

    private static synchronized void finishInternal(Context context, int rounds, // modified
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertScheduler.cancel(app);
            AlertSession.get(app).stop();
            CharSequence status = rounds > 1
                    ? app.getString(R.string.notif_status_done, rounds)
                    : app.getString(R.string.notif_status_done_once);
            postQuiet(app, status);
            if (stopService) {
                requestServiceStop(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "finish alert failed", e);
        }
    }

    /**
     * 发通知的同时就把下一轮闹钟挂好。
     *
     * <p>这一步是**兜底**，解决「亮屏时用户没点通知 -> 全屏页没弹出 -> 服务没启动 ->
     * 就没人负责安排下一轮」的断链。闹钟由系统托管，进程被杀也不受影响。
     *
     * <p>如果服务正常启动并播完这一轮，它会用同一个 requestCode 的 PendingIntent
     * 再覆盖一次闹钟，把计时起点校正为「响完的那一刻」，因此不会出现双重提醒。
     */
    private static void scheduleFollowUp(Context app, int justPostedRound) { // modified: 新增
        AppConfig config = AppConfig.get(app);
        int interval = config.getRepeatIntervalMin();
        int duration = config.getRingDurationSec();
        int total = AlertSession.get(app).getTotalRounds();
        // modified: 与「单轮播完后」用同一套规则，避免两个组件对「还有没有下一轮」判断不一致
        if (!RoundPolicy.hasNextRound(justPostedRound, total, duration, interval)) {
            return;
        }
        AlertScheduler.scheduleNext(app, interval, justPostedRound + 1);
    }

    /**
     * 通知「还有下一轮」：把通知改成「已提醒 N 次 · M 分钟后再次提醒」。
     * 由服务在单轮响铃结束时调用。
     */
    public static synchronized void markWaiting(Context context, int finishedRound, // modified
                                   int intervalMinutes) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            postQuiet(app, app.getString(R.string.notif_status_wait,
                    Math.max(1, finishedRound), Math.max(1, intervalMinutes)));
        } catch (Exception e) {
            Log.e(TAG, "mark waiting failed", e);
        }
    }

    /** 发一轮「带闹铃音 + 全屏 Intent」的通知。这是整个应用最可靠的唤醒入口。 */
    private static void postRound(Context app, int round, CharSequence status) { // modified: 新增
        AlertSession session = AlertSession.get(app);
        // modified: 【独立复审修正】只有这里传 true。全屏 Intent 是「叫人」的手段，
        // 只应出现在真正要提醒的那一轮；状态更新（等待中/已稍后/已结束）绝不能带，
        // 否则用户明明已经点过「停止」，锁屏却又弹出一个「提醒已结束」的全屏页。
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_AUDIBLE,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status, true);
        Log.d(TAG, "posted alert notification, round=" + round);
    }

    /** 发一条静音渠道的通知（只更新文案，不再出声，也不弹全屏页）。 */
    private static void postQuiet(Context app, CharSequence status) { // modified: 新增
        AlertSession session = AlertSession.get(app);
        // modified: 【v4.2】状态通知（等待中 / 已稍后 / 已结束）不挂操作按钮，
        // 避免用户从「已结束」通知上把会话复活；划掉也不再终止会话。
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status, false, false); // modified
    }

    /**
     * 让正在播放的服务停止。
     *
     * <p>正常情况下服务此时就在前台运行，应用处于「有前台服务」状态，
     * 追加一个 action 是允许的，服务会走 {@code handleLocalStop()} 优雅收尾
     * （归还音量、释放 MediaPlayer、停止震动、保留通知）。
     *
     * <p>【独立复审修正】原实现只做 {@code startService} 并吞掉异常，存在一个真实死局：
     * 用户把「单轮响铃时长」设为「一直响到手动停止」时，{@code endRound()} 永远不会被调用，
     * 一旦这个 {@code startService} 被系统拒绝（部分 ROM 在应用完全后台时会拒），
     * 铃声就会**无限响下去**，只能强杀进程。
     * 因此这里补一条兜底：{@code stopService} 不受后台启动限制，必定能终止服务，
     * 而 {@code onDestroy()} 里已经做了完整的资源清理（音量、MediaPlayer、震动、WakeLock）。
     */
    private static void requestServiceStop(Context app) { // modified: 增加 stopService 兜底
        // modified: 【v4.2 独立复审】服务已经 stopSelf 之后，startService 会把它
        // 重新创建一次（onCreate -> ACTION_STOP -> stopSelf -> onDestroy），纯属空转；
        // 且 Android 8+ 在应用完全后台时会直接拒绝。只在服务确实存活时发 STOP action。
        if (!SMSForegroundService.isRunning()) { // modified
            Log.d(TAG, "alert service not running, skip STOP action"); // modified
            return;
        }
        try {
            Intent intent = new Intent(app, SMSForegroundService.class);
            intent.setAction(SMSForegroundService.ACTION_STOP_ALERT);
            app.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "graceful stop rejected, fall back to stopService", e);
            try {
                // 兜底路径：直接停止服务。不受 Android 8+ 后台启动限制影响。
                app.stopService(new Intent(app, SMSForegroundService.class));
            } catch (Exception stopError) {
                Log.e(TAG, "stopService also failed", stopError);
            }
        }
    }

    /**
     * 让正在播放的服务**停下来但保留通知**。
     *
     * <p>modified: v4.5.0 新增。与 {@link #requestServiceStop} 的区别只在收尾方式：
     * STOP 走 stopForeground(REMOVE)（用户明确结束了提醒，通知该消失）；
     * SILENCE 走 DETACH（只是别响了，通知还要留着显示「已稍后提醒」）。
     */
    private static void requestServiceSilence(Context app) { // modified: v4.5.0 新增
        if (!SMSForegroundService.isRunning()) { // modified
            Log.d(TAG, "alert service not running, nothing to silence"); // modified
            return; // modified
        } // modified
        try { // modified
            Intent intent = new Intent(app, SMSForegroundService.class); // modified
            intent.setAction(SMSForegroundService.ACTION_SILENCE_ALERT); // modified
            app.startService(intent); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "graceful silence rejected, fall back to stopService", e); // modified
            try { // modified
                app.stopService(new Intent(app, SMSForegroundService.class)); // modified
            } catch (Exception stopError) { // modified
                Log.e(TAG, "stopService also failed", stopError); // modified
            } // modified
        } // modified
    } // modified

    /**
     * 试响一次：按当前设置播放固定 8 秒，**不建立会话、不排闹钟、不改会话状态**。
     *
     * <p>modified: v4.5.0 新增。参考 SmsForwarder 给每个转发通道都配「测试」按钮、
     * reReminder 提供可靠性自检的做法：用户改完音量/时长/渐强后，最需要的是立刻听到效果。
     *
     * <p>合规性：只能由用户可见的界面（设置页）调用。从用户可见状态启动前台服务
     * 属于 Android 12+ 的官方豁免情形，不会触发 ForegroundServiceStartNotAllowedException。
     *
     * @return true 表示已发出启动请求
     */
    public static synchronized boolean preview(Context context) { // modified: v4.5.0 新增
        if (context == null) { // modified
            return false; // modified
        } // modified
        try { // modified
            Context app = app(context); // modified
            if (SMSForegroundService.isRunning()) { // modified
                Log.i(TAG, "alert service busy, skip preview"); // modified
                return false; // modified
            } // modified
            Intent intent = new Intent(app, SMSForegroundService.class); // modified
            intent.setAction(SMSForegroundService.ACTION_PREVIEW_ALERT); // modified
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { // modified
                app.startForegroundService(intent); // modified
            } else { // modified
                app.startService(intent); // modified
            } // modified
            return true; // modified
        } catch (Exception e) { // modified
            Log.e(TAG, "start preview alert failed", e); // modified
            return false; // modified
        } // modified
    } // modified

    private static Context app(Context context) {
        return context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }
}
