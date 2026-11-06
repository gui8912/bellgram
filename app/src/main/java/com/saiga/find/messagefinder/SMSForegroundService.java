package com.saiga.find.messagefinder;

// [v4 重写 / modified] 单轮提醒播放器。
//
// 职责变化（破坏性）：v3 里这个服务是「常驻总控」——持有 Handler 等 5 分钟、自己决定
// 下一轮什么时候响。v4 里它只负责**当前这一轮**：播放闹铃 + 震动，响满时长后把
// 下一轮交给 AlertScheduler 的精确闹钟，然后立刻 stopSelf()。
//
// 为什么要这样拆：
//  1) targetSdk 31+ 禁止从后台启动前台服务。现在服务只由全屏告警页（用户可见）启动，
//     属于官方列出的豁免情形，不再依赖短信广播；
//  2) Android 14+ 的 shortService 前台服务约 3 分钟就会被 onTimeout 甚至 ANR，
//     而本应用的重复间隔是 5 分钟，长驻服务在规范上根本走不通；
//  3) 服务被系统回收后 Handler 会消失，「5 分钟后再响」就永远不响；闹钟由系统托管，更可靠。
//  4) 前台服务类型改为 mediaPlayback（确实在播放音频），没有短时限，权限为安装即授予的普通权限。
//
// 参考：Android 官方《Foreground service types》《Restrictions on starting a foreground
// service from the background》《Schedule alarms》。未复制第三方代码。

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

public class SMSForegroundService extends Service implements MediaPlayer.OnPreparedListener {

    /** 启动一轮提醒的 action（保留原有接口，调用方仍是显式 Intent）。 */
    public static final String ACTION_START_ALERT = "com.start.foreground";
    /** 停止提醒的 action。 */
    public static final String ACTION_STOP_ALERT = "com.stop.foreground";
    /** 兼容 v3 残留通知的「稍后提醒」action。 */
    public static final String ACTION_SNOOZE_ALERT = "com.snooze.foreground";
    /** modified: v4.5.0 停止播放但保留通知（「稍后提醒」收尾用，与 STOP 的 REMOVE 区分）。 */
    public static final String ACTION_SILENCE_ALERT = "com.silence.foreground";
    /** modified: v4.5.0 试响：按当前设置播一轮，不建立会话、不排闹钟。 */
    public static final String ACTION_PREVIEW_ALERT = "com.preview.foreground";
    /** modified: 试响固定播放 8 秒：足够判断音量/震动是否正常，又不会让人找不到停止入口。 */
    private static final int PREVIEW_DURATION_SEC = 8;

    private static final String TAG = "SmsService";
    private static final String RINGTONE_RESOURCE_PATH = "raw/priority_ringtone";
    private static final long WAKE_LOCK_MARGIN_MS = 60 * 1000L;
    private static final long NO_TIMEOUT_WAKE_LOCK_MS = 30 * 60 * 1000L;
    // modified: 稍后提醒时长改由 AppConfig.getSnoozeMinutes() 托管，可在设置页调整

    /** modified: 【v4.2】服务存活标志，供 AlertDispatcher 判断是否需要发 STOP action。 */
    private static volatile boolean sRunning = false; // modified
    /** modified: 【v4.2】本轮是否正在播放，用于拒绝同轮重复 START_ALERT。 */
    public static boolean isRunning() { // modified
        return sRunning; // modified
    }

    private MediaPlayer mediaPlayer;
    private Vibrator vibratorService;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private Handler alertHandler;
    private Runnable ringTimeoutRunnable;

    private int savedAlarmVolume = -1;
    private boolean volumeBoosted = false;
    private volatile boolean alertActive = false;
    private volatile boolean ringWindowOpen = false;
    private boolean foregroundStarted = false;

    private boolean ringEnabled = true;
    private boolean vibrateEnabled = true;
    private boolean quietHoursActive = false;
    /** modified: v4.5.0 本次运行是否为「试响」：结束时只退服务，不碰会话与闹钟。 */
    private boolean previewMode = false;
    private int ringDurationSec = 60;
    private int repeatIntervalMin = 5;
    private int currentRound = 1;
    // modified: v4.3 音量档位 / 渐强音量
    private int alarmVolumeLevel = VolumePolicy.DEFAULT_LEVEL; // modified
    private boolean crescendoEnabled = false; // modified
    private float targetPlayerVolume = 1.0f; // modified
    private Runnable crescendoRunnable; // modified

    private String currentSender = "";
    private String currentKeyword = "";
    private String currentPerson = "";
    private String currentBody = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "alert service created (single-round player)");
        mediaPlayer = new MediaPlayer();
        vibratorService = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        alertHandler = new Handler(Looper.getMainLooper());
        NotificationHelper.ensureChannels(this);
        sRunning = true; // modified: 【v4.2】登记存活
        // modified: 上次进程被杀时可能把系统闹钟音量永久改成了最大，先还原
        AlarmVolumeGuard.restoreIfStale(this); // modified
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand without action, stop self");
            stopSelfSafely(false);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START_ALERT.equals(action)) {
            handleStartAlert(intent);
        } else if (ACTION_STOP_ALERT.equals(action)) {
            // 纯本地清理：状态、闹钟、通知都由 AlertDispatcher 处理，避免两边互相递归调用
            handleLocalStop(true); // modified: v4.5.0 参数化：用户明确停止 -> 连通知一起撤
        } else if (ACTION_SILENCE_ALERT.equals(action)) { // modified: v4.5.0 只静音、保留通知
            handleLocalStop(false); // modified
        } else if (ACTION_PREVIEW_ALERT.equals(action)) { // modified: v4.5.0 试响
            handlePreview(); // modified
        } else if (ACTION_SNOOZE_ALERT.equals(action)) {
            handleLocalStop(true); // modified: v4.5.0
            // modified: 稍后提醒时长不再跟随重复间隔，也不再写死 5 分钟
            int minutes = AppConfig.get(this).getSnoozeMinutes(); // modified
            AlertDispatcher.snoozeFromService(this, minutes); // modified: 避免自重启
        } else {
            Log.w(TAG, "unknown action: " + action);
            stopSelfSafely(false);
        }
        // 一次性服务：不希望被系统自动重建后莫名其妙再次响铃
        return START_NOT_STICKY;
    }

    // ==================== 单轮提醒主流程 ====================

    private void handleStartAlert(Intent intent) {
        // modified: 【v4.2 独立复审】同一轮的 START_ALERT 重复到达会取消本轮定时器、
        // reset MediaPlayer、从头播放（用户反复点通知 / FSI 重复投递时本轮永远响不完）。
        // 这里做本轮幂等，配合 AlertActivity.onNewIntent 的轮次去重形成双保险。
        if (alertActive) { // modified
            Log.d(TAG, "alert round already playing, ignore duplicate START"); // modified
            return;
        }
        AlertSession session = AlertSession.get(this);
        // 会话状态为准；intent extras 只作为兼容兜底（v3 残留的通知可能直接带内容启动服务）
        currentSender = fallback(session.getFrom(), NotificationHelper.getFrom(intent));
        currentKeyword = fallback(session.getKeyword(), NotificationHelper.getKeyword(intent));
        currentPerson = fallback(session.getPerson(), NotificationHelper.getPerson(intent));
        currentBody = fallback(session.getBody(), NotificationHelper.getPayload(intent));
        currentRound = session.getRound();

        readReminderPolicy();
        cancelRingTimeout();
        stopRingAndVibration();

        // Android 8+ 通过 startForegroundService 启动后必须在 5 秒内 startForeground，
        // 即使会话已被用户停止，也要先挂通知再立刻收掉，否则系统会判 ANR。
        //
        // modified: 【独立复审修正】这里显式传 false。服务的前台通知只承担「保持前台 + 显示状态」，
        // 绝不能再带全屏 Intent——否则服务每次 startForeground 都会尝试拉起告警页，
        // 与 AlertActivity 形成互相触发的循环（锁屏场景下会反复弹页）。
        Notification notification = NotificationHelper.build(this,
                NotificationHelper.CHANNEL_ID_ALERT, currentSender, currentKeyword,
                currentPerson, currentBody,
                getString(R.string.notif_status_round, currentRound), false);
        try {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification); // modified: 最先调用
            foregroundStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed, degrade to plain notification", e);
            NotificationHelper.notifyDirectly(this, currentSender, currentKeyword,
                    currentPerson, currentBody);
            stopSelf();
            return;
        }

        if (!session.isActive()) {
            Log.d(TAG, "session already stopped, discard this round");
            stopSelfSafely(true);
            return;
        }

        alertActive = true;
        acquireWakeLock(computeWakeLockTimeoutMs());
        startRound();
    }

    /**
     * modified: v4.5.0 新增。试响：按当前音量 / 渐强 / 震动设置播一轮，固定 8 秒后自动停。
     *
     * <p>与 {@link #handleStartAlert} 的差别是**完全不碰会话**：
     * 不读 AlertSession、不写轮次、不排闹钟，因此不会把用户等待中的提醒覆盖掉。
     * 时长也不跟随「单次响铃时长」——那条设置允许 0（一直响），试响若照做，
     * 用户会得到一个没有停止按钮的长铃。
     */
    private void handlePreview() { // modified
        if (alertActive) { // modified
            Log.d(TAG, "alert round already playing, ignore preview"); // modified
            return; // modified
        } // modified
        currentSender = getString(R.string.preview_sender); // modified
        currentKeyword = getString(R.string.preview_keyword); // modified
        currentPerson = ""; // modified
        currentBody = getString(R.string.preview_body); // modified
        currentRound = 1; // modified
        previewMode = true; // modified

        readReminderPolicy(); // modified: 与真实提醒用同一套音量/渐强/震动设置
        // modified: 试响是用户主动点的，不该被夜间静音吞掉 —— 否则半夜想验证设置时会以为坏了
        ringEnabled = AppConfig.get(this).isRingEnabled(); // modified
        ringDurationSec = PREVIEW_DURATION_SEC; // modified: 固定 8 秒，避免 0 = 无限响
        cancelRingTimeout(); // modified
        stopRingAndVibration(); // modified

        // modified: 9 参重载，withActions=false —— 试响不该带「停止 / 稍后提醒」按钮：
        // 那两个按钮走的是 AlertDispatcher，会把一条正在等待的真实会话一起结束掉。
        // 试响固定 8 秒自动收尾，用户不需要按钮。
        Notification notification = NotificationHelper.build(this, // modified
                NotificationHelper.CHANNEL_ID_ALERT, currentSender, currentKeyword, // modified
                currentPerson, currentBody, getString(R.string.preview_alert_title), // modified
                false, false); // modified
        try { // modified
            startForeground(NotificationHelper.NOTIFICATION_ID, notification); // modified
            foregroundStarted = true; // modified
        } catch (Exception e) { // modified
            Log.e(TAG, "startForeground failed, cancel preview", e); // modified
            previewMode = false; // modified
            stopSelf(); // modified
            return; // modified
        } // modified

        alertActive = true; // modified
        acquireWakeLock(computeWakeLockTimeoutMs()); // modified
        startRound(); // modified
    } // modified

    /** 读取本轮生效的提醒策略（用户在设置页改完，下一轮立刻生效）。 */
    private void readReminderPolicy() { // modified: 重写，不再计算常驻总轮次
        AppConfig config = AppConfig.get(this);
        quietHoursActive = config.isInQuietHours();
        ringEnabled = config.isRingEnabled() && !quietHoursActive;
        vibrateEnabled = config.isVibrateEnabled();
        ringDurationSec = config.getRingDurationSec();
        repeatIntervalMin = config.getRepeatIntervalMin();
        alarmVolumeLevel = config.getAlarmVolumeLevel(); // modified
        crescendoEnabled = config.isCrescendoEnabled(); // modified
        Log.d(TAG, "policy: ring=" + ringEnabled + (quietHoursActive ? "(quiet hours)" : "")
                + ", vibrate=" + vibrateEnabled
                + ", duration=" + ringDurationSec + "s, interval=" + repeatIntervalMin + "min"
                + ", volume=" + alarmVolumeLevel + "%" // modified
                + ", crescendo=" + crescendoEnabled // modified
                + ", round=" + currentRound);
    }

    /** 开始本轮：响铃 + 震动 + 挂「响满时长」的定时器。 */
    private void startRound() { // modified: 重写为单轮
        if (!alertActive) {
            return;
        }
        // modified: v4.5.0 响铃和震动都被关掉时，这一轮没有任何可播的内容。
        // 原实现会照常挂上定时器；若「单次响铃时长」选的是「直到我手动停止」（0），
        // 定时器根本不创建，服务就带着前台通知与 WakeLock 一直挂着，
        // 直到用户发现通知栏里有个关不掉的东西。这里直接收尾，让轮次继续按间隔推进。
        if (!ringEnabled && !vibrateEnabled) { // modified
            Log.d(TAG, "ring and vibration both disabled, end this round at once"); // modified
            alertHandler.post(new Runnable() { // modified
                @Override // modified
                public void run() { // modified
                    endRound(); // modified
                } // modified
            }); // modified
            return; // modified
        } // modified
        if (ringEnabled) {
            ringWindowOpen = true;
            if (!startAlarmPlayback()) {
                Log.w(TAG, "alarm playback failed, fallback to notification sound");
                fallbackToNotificationSound();
            }
        } else {
            Log.d(TAG, "ring disabled (user setting or quiet hours), skip playback");
        }
        if (vibrateEnabled) {
            startVibration();
        }
        cancelRingTimeout();
        if (ringDurationSec > 0) {
            ringTimeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    ringTimeoutRunnable = null;
                    endRound();
                }
            };
            alertHandler.postDelayed(ringTimeoutRunnable, ringDurationSec * 1000L);
        }
        // ringDurationSec == 0：一直响到用户点「停止」（mediaPlayback 类型无 3 分钟时限）
    }

    /**
     * 本轮响完：停铃、恢复音量、把下一轮交给精确闹钟、退出前台并结束服务。
     */
    private void endRound() { // modified: 重写，不再用 Handler 常驻等待
        if (!alertActive) {
            return;
        }
        alertActive = false;
        ringWindowOpen = false;
        cancelRingTimeout();
        stopRingAndVibration();
        restoreAlarmVolume();
        releasePlayer();
        releaseWakeLock();

        // modified: v4.5.0 试响到此为止：不读会话、不排闹钟、不改任何状态，直接退服务。
        // 通知用 REMOVE，否则通知栏会留下一条写着「试响一次」的死通知。
        if (previewMode) { // modified
            previewMode = false; // modified
            Log.d(TAG, "preview finished"); // modified
            stopSelfSafely(true); // modified
            return; // modified
        } // modified

        AlertSession session = AlertSession.get(this);
        int rounds = Math.max(1, currentRound);
        if (!session.isActive()) {
            stopSelfSafely(true);
            return;
        }
        int total = session.getTotalRounds();
        boolean hasNextRound = RoundPolicy.hasNextRound(rounds, total,
                ringDurationSec, repeatIntervalMin); // modified: 规则集中
        if (hasNextRound) {
            // 闹钟由系统托管，服务退出后依然会在 5 分钟后把接收器拉起来
            AlertScheduler.scheduleNext(this, repeatIntervalMin, rounds + 1); // modified: 新增
            AlertDispatcher.markWaiting(this, rounds, repeatIntervalMin); // modified: 新增
        } else {
            AlertDispatcher.finishFromService(this, rounds); // modified: 避免自重启
        }
        // detach = 保留通知，用户回来时仍能看到「第几次 / 几分钟后再响 / 已结束」
        stopSelfSafely(false);
    }

    /**
     * 收到 STOP / SILENCE：只做本地清理，绝不碰闹钟与会话状态（由 AlertDispatcher 统一负责）。
     *
     * @param removeNotification true = 连通知一起撤（用户明确停止 / 试响结束）；
     *                           false = 保留通知（稍后提醒：通知还要显示「已稍后提醒」）
     */
    private void handleLocalStop(boolean removeNotification) { // modified: v4.5.0 参数化
        Log.d(TAG, "local stop, removeNotification=" + removeNotification);
        alertActive = false;
        ringWindowOpen = false;
        previewMode = false; // modified: 试响被中断时也要复位，避免影响下一次真实提醒
        cancelRingTimeout();
        stopRingAndVibration();
        restoreAlarmVolume();
        releasePlayer();
        releaseWakeLock();
        // modified: 【v4.2 独立复审】原实现用 DETACH，官方语义是「服务销毁后通知仍保留」。
        // AlertDispatcher.stop() 先 cancel 通知再请求停止，服务随后 DETACH 会把
        // 一条仍带「停止 / 稍后提醒」按钮的通知重新留在通知栏，用户点了停止却没停干净。
        // 本地停止属于「用户明确结束」，应当 REMOVE；endRound 的等待通知仍用 DETACH。
        // modified: v4.5.0 「稍后提醒」不属于「明确结束」，走 SILENCE + DETACH，见 AlertDispatcher。
        stopSelfSafely(removeNotification); // modified
    }

    /** 铃声播放失败时，换到带声音渠道再提醒一次，保证至少响一声。 */
    private void fallbackToNotificationSound() {
        try {
            NotificationHelper.notifyDirectly(this, currentSender, currentKeyword,
                    currentPerson, currentBody);
        } catch (Exception e) {
            Log.e(TAG, "fallback notification failed", e);
        }
    }

    /** 准备并播放闹钟铃声。 */
    private boolean startAlarmPlayback() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager != null) {
            try {
                // modified: v4.3 只有「非跟随系统」档位才写系统设置，且不再强制最大值
                if (!audioManager.isVolumeFixed() // modified
                        && VolumePolicy.shouldChangeSystemVolume(alarmVolumeLevel)) { // modified
                    if (!volumeBoosted) {
                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                        volumeBoosted = true;
                        // 【v4.2】同步落盘，进程被杀后仍可恢复（onDestroy 不一定执行）
                        AlarmVolumeGuard.remember(this, savedAlarmVolume); // modified
                    }
                    int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM); // modified
                    int target = VolumePolicy.targetStreamIndex(maxVolume, alarmVolumeLevel); // modified
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0); // modified
                }
            } catch (Exception e) {
                Log.w(TAG, "apply alarm volume level failed", e); // modified
            }
        }
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            }
            mediaPlayer.reset();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            Uri ringtoneUri = Uri.parse("android.resource://" + getPackageName() + "/"
                    + RINGTONE_RESOURCE_PATH);
            mediaPlayer.setDataSource(this, ringtoneUri);
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "media player error, what=" + what + ", extra=" + extra);
                    // modified: 【v4.2 独立复审】返回 true 表示「应用自行处理」，
                    // 原实现除打日志外什么都没做：MediaPlayer 进入 Error 状态、
                    // setLooping 失效，本轮彻底静音，服务却继续占前台直到超时。
                    // 现在显式收尾并补一声通知音，至少让用户知道有事发生。
                    ringWindowOpen = false; // modified
                    stopRingAndVibration(); // modified
                    releasePlayer(); // modified
                    restoreAlarmVolume(); // modified
                    fallbackToNotificationSound(); // modified
                    return true;
                }
            });
            // modified: 固定音量设备不能改系统流，只能用播放器自身音量近似档位；
            // 渐强音量一律从 25% 起步，5 秒内升到目标值，不反复写系统设置。
            targetPlayerVolume = 1.0f; // modified
            if (audioManager != null && audioManager.isVolumeFixed() // modified
                    && alarmVolumeLevel > VolumePolicy.FOLLOW_SYSTEM) { // modified
                targetPlayerVolume = alarmVolumeLevel / 100f; // modified
            } // modified
            float startVolume = VolumePolicy.initialPlayerVolume(crescendoEnabled) // modified
                    * targetPlayerVolume; // modified
            mediaPlayer.setVolume(startVolume, startVolume); // modified
            mediaPlayer.prepareAsync();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "prepare alarm playback failed", e);
            releasePlayer();
            // modified: 【v4.2】音量可能已经提升成功，失败分支必须立刻归还，
            // 否则「一直响到手动停止」配置下系统闹钟音量会被钉在最大。
            restoreAlarmVolume(); // modified
            return false;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (!alertActive || !ringWindowOpen) {
            Log.d(TAG, "onPrepared but ring window closed, skip start");
            return;
        }
        try {
            mp.start();
            if (crescendoEnabled) { // modified
                startCrescendo(); // modified
            } // modified
            Log.d(TAG, "alarm playback started");
        } catch (IllegalStateException e) {
            Log.e(TAG, "start playback failed", e);
        }
    }

    /**
     * modified: v4.3 渐强音量。参考系统时钟 "Gradually increase volume"，
     * 只调 MediaPlayer 自身音量，不反复写 STREAM_ALARM（那是持久化系统设置）。
     * 5 秒 10 步；ringWindowOpen 关闭或 MediaPlayer 被释放后自动停止。
     */
    private void startCrescendo() { // modified
        final int steps = 10; // modified
        final int[] step = {0}; // modified
        crescendoRunnable = new Runnable() { // modified
            @Override // modified
            public void run() { // modified
                if (!alertActive || !ringWindowOpen || mediaPlayer == null) { // modified
                    return; // modified
                } // modified
                step[0]++; // modified
                float volume = Math.min(1.0f, // modified
                        0.25f + 0.75f * (step[0] / (float) steps)) // modified
                        * targetPlayerVolume; // modified
                try { // modified
                    mediaPlayer.setVolume(volume, volume); // modified
                } catch (Exception e) { // modified
                    Log.w(TAG, "crescendo step failed", e); // modified
                    return; // modified
                } // modified
                if (step[0] < steps && alertHandler != null) { // modified
                    alertHandler.postDelayed(this, 500L); // modified
                } // modified
            } // modified
        }; // modified
        if (alertHandler != null) { // modified
            alertHandler.postDelayed(crescendoRunnable, 500L); // modified
        } // modified
    } // modified

    private void startVibration() {
        if (vibratorService == null || !vibratorService.hasVibrator()) {
            return;
        }
        try {
            long[] pattern = new long[]{50, 1000, 400, 900, 300, 800};
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                int[] amplitudes = new int[]{0, 255, 0, 200, 0, 150};
                vibratorService.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 2));
            } else {
                vibratorService.vibrate(pattern, 2);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    private void stopRingAndVibration() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "pause playback failed", e);
        }
        try {
            if (vibratorService != null) {
                vibratorService.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel vibration failed", e);
        }
    }

    private void cancelRingTimeout() {
        if (ringTimeoutRunnable != null && alertHandler != null) {
            alertHandler.removeCallbacks(ringTimeoutRunnable);
            ringTimeoutRunnable = null;
        }
        if (alertHandler != null) {
            alertHandler.removeCallbacksAndMessages(null);
        }
        crescendoRunnable = null; // modified
    }

    /** 恢复用户原来的闹钟音量。只有确实提升过才恢复，避免把音量写成 0。 */
    private void restoreAlarmVolume() {
        if (!volumeBoosted || audioManager == null || savedAlarmVolume < 0) {
            return;
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
        } catch (Exception e) {
            Log.w(TAG, "restore alarm volume failed", e);
        } finally {
            volumeBoosted = false;
            savedAlarmVolume = -1;
            AlarmVolumeGuard.clear(this); // modified: 【v4.2】正常收尾，清除跨进程残留标记
        }
    }

    /**
     * 退出前台并结束服务。
     *
     * @param removeNotification true = 连通知一起撤掉（会话已停止时）；
     *                           false = detach，把通知留在通知栏供用户查看
     */
    private void stopSelfSafely(boolean removeNotification) { // modified: 区分 detach / remove
        try {
            if (foregroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(removeNotification
                            ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(removeNotification);
                }
                foregroundStarted = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        stopSelf();
    }

    private long computeWakeLockTimeoutMs() { // modified: 单轮时长，不再按总轮次估算
        if (ringDurationSec <= 0) {
            return NO_TIMEOUT_WAKE_LOCK_MS;
        }
        return Math.max(3 * 60_000L, ringDurationSec * 1000L + WAKE_LOCK_MARGIN_MS);
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            releaseWakeLock();
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bellgram:alert");
            wakeLock.acquire(timeoutMs);
        } catch (Exception e) {
            Log.w(TAG, "acquire wake lock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "release wake lock failed", e);
        } finally {
            wakeLock = null;
        }
    }

    private void releasePlayer() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                mediaPlayer.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "release media player failed", e);
        } finally {
            mediaPlayer = null;
        }
    }

    /** Android 14+ 前台服务超时兜底。mediaPlayback 正常不会触发，仍按规范实现。 */
    @Override
    public void onTimeout(int startId, int fgsType) { // modified: 新增
        Log.w(TAG, "foreground service timeout, stop round");
        handleLocalStop(true); // modified: v4.5.0 参数化
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        alertActive = false;
        ringWindowOpen = false;
        cancelRingTimeout();
        restoreAlarmVolume();
        releasePlayer();
        try {
            if (vibratorService != null) {
                vibratorService.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel vibration on destroy failed", e);
        }
        releaseWakeLock();
        sRunning = false; // modified: 【v4.2】注销存活标志
        Log.d(TAG, "alert service destroyed");
    }

    private static String fallback(String primary, String secondary) {
        return primary == null || primary.isEmpty() ? (secondary == null ? "" : secondary) : primary;
    }
}
