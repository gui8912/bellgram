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

    private AlertDispatcher() {
        // 工具类不允许实例化
    }

    /**
     * 收到一条命中规则的短信：开启新会话并发出第一轮提醒。
     */
    public static void onSmsMatched(Context context, String sender, String keyword,
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
    public static void onRepeatAlarm(Context context, int round) { // modified: 新增
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
    public static void stop(Context context) { // modified: 新增
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
    public static void snooze(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, true);
    }

    /**
     * 供服务自身处理 v3 残留通知的「稍后提醒」时使用。
     * 服务已经自行停止，不需要（也不应该）再给自己发一次 startService，
     * 否则 stopSelf 之后又收到启动请求会触发一次无意义的 onCreate / onDestroy。
     */
    public static void snoozeFromService(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, false);
    }

    private static void snoozeInternal(Context context, int delayMinutes,
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            final int safeDelay = Math.max(1, delayMinutes);
            AlertScheduler.cancel(app);
            AlertSession session = AlertSession.get(app);
            session.setActive(true);
            int nextRound = session.getRound() + 1;
            session.ensureRound(nextRound);
            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            if (stopService) {
                requestServiceStop(app);
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
    public static void finish(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, true);
    }

    /** 服务自己收尾时调用，避免给自己发 STOP 造成无意义的重启。 */
    public static void finishFromService(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, false);
    }

    private static void finishInternal(Context context, int rounds,
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
    public static void markWaiting(Context context, int finishedRound,
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
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status);
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

    private static Context app(Context context) {
        return context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }
}
