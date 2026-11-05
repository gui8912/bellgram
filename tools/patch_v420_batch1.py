# -*- coding: utf-8 -*-
"""v4.2.0 批次 1：核心提醒链路修复。每处替换都做存在性断言，避免静默漏改。"""
import io, os, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main")
JAVA = os.path.join(ROOT, "java", "com", "saiga", "find", "messagefinder")
changes = []


def read(name):
    with io.open(os.path.join(JAVA, name), encoding="utf-8") as f:
        return f.read()


def write(name, s):
    with io.open(os.path.join(JAVA, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def rep(name, s, old, new, tag, count=1):
    n = s.count(old)
    assert n == count, "%s :: %s 期望 %d 处，实际 %d 处" % (name, tag, count, n)
    changes.append((name, tag))
    return s.replace(old, new, count)


# ============================================================ NotificationHelper
n = "NotificationHelper.java"
s = read(n)

old_ensure = '''    /** 创建（或更新）两个通知渠道。重复调用是安全的。 */
    public static void ensureChannels(Context context) { // modified: 新增带声音的主渠道
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription(context.getString(R.string.channel_desc));
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alertChannel.setSound(null, null); // modified: 服务播放铃声时不出通知音
            alertChannel.enableVibration(false);
            alertChannel.setBypassDnd(true);
            manager.createNotificationChannel(alertChannel);

            NotificationChannel audibleChannel = new NotificationChannel(
                    CHANNEL_ID_AUDIBLE,
                    context.getString(R.string.channel_name_audible),
                    NotificationManager.IMPORTANCE_HIGH);
            audibleChannel.setDescription(context.getString(R.string.channel_desc_audible));
            audibleChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            audibleChannel.setSound(alarmSound, audioAttributes); // modified: 广播阶段自带闹铃音
            audibleChannel.enableVibration(true);
            audibleChannel.setBypassDnd(true);
            manager.createNotificationChannel(audibleChannel);

            // modified: 清掉 v3 遗留的旧渠道。它在 v3 里是带提示音的，
            // v4 已不再使用；留着会让用户在系统设置里看到一条多余渠道，
            // 也可能被误认为仍然生效。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V3);
            }
        } catch (Exception e) {
            // 渠道创建失败不应影响主流程（例如部分定制 ROM 对 setBypassDnd 抛异常）
            Log.e(TAG, "create notification channel failed", e);
        }
    }
'''

new_ensure = '''    /** 创建（或更新）两个通知渠道。重复调用是安全的。 */
    public static void ensureChannels(Context context) { // modified: 新增带声音的主渠道
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        // modified: 【v4.2 独立复审】原实现把两个渠道的创建 + 删旧渠道全塞进一个 try。
        // 部分定制 ROM 的 setBypassDnd() 会抛异常，一旦发生在「静音渠道已创建、
        // 有声渠道未创建」之间，异常被整体吞掉，之后 postRound() 恒用有声渠道发通知，
        // 而该渠道根本不存在 —— Android 8+ 会静默丢弃通知，主链路 100% 失效且无报错。
        // 现在每个渠道独立 try，且把非关键的 setBypassDnd 单独包起来，
        // 保证 createNotificationChannel() 这一步一定有机会执行。
        createQuietChannel(manager, context);   // modified: 渠道创建相互隔离
        createAudibleChannel(manager, context); // modified: 渠道创建相互隔离
        try {
            // modified: 清掉 v3 遗留的旧渠道（声音属性创建后不可改，留着只会在系统设置里多出一条）
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V3);
        } catch (Exception e) {
            Log.w(TAG, "delete legacy channel failed", e);
        }
    }

    private static void createQuietChannel(NotificationManager manager, Context context) { // modified: 新增
        try {
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription(context.getString(R.string.channel_desc));
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alertChannel.setSound(null, null); // modified: 服务播放铃声时不出通知音
            alertChannel.enableVibration(false);
            // modified: 非关键属性单独兜底，不能让它阻断渠道创建（部分定制 ROM 会抛异常）
            tryBypassDnd(alertChannel);
            manager.createNotificationChannel(alertChannel);
        } catch (Exception e) {
            Log.e(TAG, "create quiet channel failed", e);
        }
    }

    private static void createAudibleChannel(NotificationManager manager, Context context) { // modified: 新增
        try {
            NotificationChannel audibleChannel = new NotificationChannel(
                    CHANNEL_ID_AUDIBLE,
                    context.getString(R.string.channel_name_audible),
                    NotificationManager.IMPORTANCE_HIGH);
            audibleChannel.setDescription(context.getString(R.string.channel_desc_audible));
            audibleChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            audibleChannel.setSound(alarmSound, audioAttributes); // modified: 广播阶段自带闹铃音
            audibleChannel.enableVibration(true);
            tryBypassDnd(audibleChannel); // modified: 同上，单独兜底
            manager.createNotificationChannel(audibleChannel);
        } catch (Exception e) {
            Log.e(TAG, "create audible channel failed", e);
        }
    }

    private static void tryBypassDnd(NotificationChannel channel) { // modified: 新增
        try {
            channel.setBypassDnd(true);
        } catch (Exception e) {
            Log.w(TAG, "setBypassDnd unsupported on this ROM", e);
        }
    }

    /** 渠道是否真实存在。创建失败 / 被用户在系统设置里删掉时返回 false。 */
    private static boolean channelExists(Context context, String channelId) { // modified: 新增
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true; // API 25 及以下没有渠道概念
        }
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.getNotificationChannel(channelId) != null;
        } catch (Exception e) {
            return false;
        }
    }
'''
s = rep(n, s, old_ensure, new_ensure, "ensureChannels 拆分独立 try")

# build 的重载：全部改为委托到带 withActions 的完整实现
s = rep(n, s,
'''        return build(context, channelId, sender, keyword, personName, body, null, false);
    }''',
'''        return build(context, channelId, sender, keyword, personName, body, null, false, true);
    }''', "build 3参重载")

s = rep(n, s,
'''        return build(context, channelId, sender, keyword, personName, body, status, false);
    }''',
'''        return build(context, channelId, sender, keyword, personName, body, status, false, true);
    }''', "build 4参重载")

s = rep(n, s,
'''                                     CharSequence status,
                                     boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        String displaySender''',
'''                                     CharSequence status,
                                     boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        return build(context, channelId, sender, keyword, personName, body, status,
                withFullScreenIntent, true);
    }

    /**
     * 完整构建入口。
     *
     * @param withActions 是否附带「停止 / 稍后提醒」按钮。
     *                    「等待中 / 已稍后 / 已结束」这类纯状态通知传 false ——
     *                    否则用户能从一条「已结束」通知上点「稍后提醒」，把已停止的会话复活。
     */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status, boolean withFullScreenIntent,
                                     boolean withActions) { // modified: v4.2 新增动作开关
        String displaySender''', "build 完整签名")

# actions / deleteIntent
s = rep(n, s,
'''        builder.addAction(R.drawable.ic_block_24dp,
                context.getString(R.string.stop), buildStopIntent(context));
        builder.addAction(0,
                context.getString(R.string.notif_snooze), buildSnoozeIntent(context));
        builder.setDeleteIntent(buildStopIntent(context)); // modified: 划掉通知 = 停止提醒
''',
'''        // modified: 【v4.2 独立复审】原实现给所有通知（含「已结束」状态通知）都挂按钮，
        // 且把「划掉通知」等同于「停止整个会话」。后者与项目核心需求
        // 「用户不处理就 5 分钟后再提醒」直接冲突：随手划掉横幅会取消后续全部轮次。
        // 现在：只有真正要叫人的一轮带按钮；划掉通知不再改变会话状态，
        // 想彻底停止请点明确的「停止」按钮或告警页里的停止。
        if (withActions) {
            builder.addAction(R.drawable.ic_block_24dp,
                    context.getString(R.string.stop), buildStopIntent(context));
            builder.addAction(0,
                    context.getString(R.string.notif_snooze), buildSnoozeIntent(context));
        }
''', "通知动作与删除意图")

# API < 26 / 渠道缺失时补声音
s = rep(n, s,
'''                .setContentIntent(buildAlertScreenIntent(context, REQ_CONTENT,
                        sender, keyword, personName, body));
        if (!TextUtils.isEmpty(status)) {''',
'''                .setContentIntent(buildAlertScreenIntent(context, REQ_CONTENT,
                        sender, keyword, personName, body));
        // modified: 【v4.2】minSdk 23 覆盖 API 23-25，这三代系统没有通知渠道，
        // 渠道上配的闹铃音完全不存在；渠道创建失败时同理。补 builder 级声音/震动，
        // 保证「第一声」在任何情况下都不会变成一条静音横幅。
        // API 26+ 且渠道正常时 builder 的声音会被渠道设置覆盖，不会造成双重响铃。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channelExists(context, channelId)) {
            Uri legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (legacySound == null) {
                legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            builder.setSound(legacySound, AudioAttributes.USAGE_ALARM);
            builder.setVibrate(new long[]{0, 400, 300, 400});
        }
        if (!TextUtils.isEmpty(status)) {''', "API<26 声音兜底")

# post 重载
s = rep(n, s,
'''        // 默认不带全屏 Intent：绝大多数调用都是「更新状态」而不是「叫人」。
        post(context, channelId, sender, keyword, person, body, status, false);
    }''',
'''        // 默认不带全屏 Intent：绝大多数调用都是「更新状态」而不是「叫人」。
        post(context, channelId, sender, keyword, person, body, status, false, true);
    }''', "post 7参重载")

s = rep(n, s,
'''                            boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(app);
            if (!manager.areNotificationsEnabled()) {
                // 用户关了通知权限 / 通知总开关。此时 notify() 只会静默失败，
                // 提前返回并留下日志，便于从 logcat 判断「为什么没提醒」。
                Log.w(TAG, "notifications disabled by user/system, skip posting");
                return;
            }
            ensureChannels(app);
            manager.notify(NOTIFICATION_ID,
                    build(app, channelId, sender, keyword, person, body, status,
                            withFullScreenIntent));
        } catch (SecurityException e) {
            Log.e(TAG, "POST_NOTIFICATIONS not granted, cannot post alert", e);
        } catch (Exception e) {
            Log.e(TAG, "post notification failed", e);
        }
    }''',
'''                            boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        post(context, channelId, sender, keyword, person, body, status,
                withFullScreenIntent, true);
    }

    /** 完整发通知入口，可同时控制全屏 Intent 与通知动作。 */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status,
                            boolean withFullScreenIntent,
                            boolean withActions) { // modified: v4.2 新增动作开关
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(app);
            if (!manager.areNotificationsEnabled()) {
                // 用户关了通知权限 / 通知总开关。此时 notify() 只会静默失败，
                // 提前返回并留下日志，便于从 logcat 判断「为什么没提醒」。
                Log.w(TAG, "notifications disabled by user/system, skip posting");
                return;
            }
            ensureChannels(app);
            // modified: 【v4.2】渠道不存在时 notify() 会被系统静默丢弃且不抛异常。
            // 再建一次；仍失败则退回系统默认渠道，至少保证通知能显示出来。
            String effectiveChannel = channelId;
            if (!channelExists(app, channelId)) {
                ensureChannels(app);
                if (!channelExists(app, channelId)) {
                    effectiveChannel = NotificationChannel.DEFAULT_CHANNEL_ID;
                    Log.w(TAG, "target channel missing, fall back to default channel");
                }
            }
            manager.notify(NOTIFICATION_ID,
                    build(app, effectiveChannel, sender, keyword, person, body, status,
                            withFullScreenIntent, withActions));
        } catch (SecurityException e) {
            Log.e(TAG, "POST_NOTIFICATIONS not granted, cannot post alert", e);
        } catch (Exception e) {
            Log.e(TAG, "post notification failed", e);
        }
    }''', "post 完整重载与渠道校验")

# notifyDirectly 去掉 FSI
s = rep(n, s,
'''        // 这条路径的用途是「铃声没能播放，用通知音兜底叫人」，所以需要全屏 Intent。
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, true);
    }''',
'''        // modified: 【v4.2 独立复审】原实现这里传 withFullScreenIntent=true。
        // 它只在「播放失败 / startForeground 失败」的降级路径被调用，若再带全屏 Intent，
        // 系统会拉起 AlertActivity，而 AlertActivity 又会 startForegroundService 再失败一次，
        // 形成「失败 -> 发通知 -> 拉页 -> 再失败」的无限循环（通知刷屏、闹铃反复响一声）。
        // 它只是补一声，不需要也不应该再拉页面；动作按钮同样去掉。
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, false, false);
    }''', "notifyDirectly 去 FSI")

write(n, s)

# ============================================================ SMSReceiver
n = "SMSReceiver.java"
s = read(n)
s = rep(n, s,
'''    private static volatile String lastFingerprint = null;
    private static volatile long lastFingerprintAt = 0L;''',
'''    private static volatile String lastFingerprint = null;
    private static volatile long lastFingerprintAt = 0L;
    /** modified: 【v4.2】volatile 只保证可见性，不保证「比较 + 两次写」原子。并发投递时去重会失效。 */
    private static final Object DEDUP_LOCK = new Object();''', "去重锁字段")

s = rep(n, s,
'''    private boolean isDuplicate(String sender, String body, String keyword) { // modified: 新增
        String fingerprint = (sender == null ? "" : sender) + '#' + body.length() + '#'
                + body.hashCode() + '#' + keyword;
        long now = System.currentTimeMillis();
        if (fingerprint.equals(lastFingerprint) && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
            return true;
        }
        lastFingerprint = fingerprint;
        lastFingerprintAt = now;
        return false;
    }''',
'''    private boolean isDuplicate(String sender, String body, String keyword) { // modified: 新增
        // modified: 【v4.2 独立复审】原指纹用 body.length() + body.hashCode()。
        // String.hashCode() 是可构造碰撞的 32 位哈希，3 秒窗口内两条内容不同、
        // 长度相同且哈希相同的短信会被误判为「重复投递」而静默吞掉（漏报）。
        // 改用完整正文参与比较，'\0' 作分隔避免字段拼接歧义。
        String fingerprint = (sender == null ? "" : sender) + '\\0' + body + '\\0' + keyword;
        long now = System.currentTimeMillis();
        // modified: 原实现把「比较」和「两个字段的写入」拆成三步，且两个字段分两次写。
        // 部分 ROM 在线程池里并发投递广播时，两个线程可能都读到旧值而双双放行，
        // 或读到「新指纹 + 旧时间戳」的错误组合。这里用一把锁把整段收成原子操作。
        synchronized (DEDUP_LOCK) {
            if (fingerprint.equals(lastFingerprint)
                    && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
                return true;
            }
            lastFingerprint = fingerprint;
            lastFingerprintAt = now;
            return false;
        }
    }''', "去重原子化与完整指纹")
write(n, s)

# ============================================================ AlertDispatcher
n = "AlertDispatcher.java"
s = read(n)
s = rep(n, s,
'''    private static final String TAG = "AlertDispatcher";''',
'''    private static final String TAG = "AlertDispatcher";

    // modified: 【v4.2】一次会话状态迁移是「cancel 闹钟 -> 改会话 -> 发通知 -> 挂新闹钟」
    // 的长链，原来各入口完全无锁。短信广播与闹钟广播并发时会出现：A 读到旧会话、
    // B 开启新会话、A 再把新会话的 round 改成旧轮次，并用 FLAG_UPDATE_CURRENT
    // 覆写同一个 PendingIntent，导致某一轮闹钟丢失或错轮。
    // 所有 public 入口都声明 synchronized（静态方法锁 AlertDispatcher.class，共享同一把锁），
    // 保证整条迁移不可分割；调用链内部没有回环，不会死锁。''', "会话锁说明")

for sig, tag in [
    ("public static void onSmsMatched(Context context, String sender, String keyword,", "onSmsMatched"),
    ("public static void onRepeatAlarm(Context context, int round)", "onRepeatAlarm"),
    ("public static void stop(Context context)", "stop"),
    ("public static void snooze(Context context, int delayMinutes)", "snooze"),
    ("public static void snoozeFromService(Context context, int delayMinutes)", "snoozeFromService"),
    ("private static void snoozeInternal(Context context, int delayMinutes,", "snoozeInternal"),
    ("public static void finish(Context context, int rounds)", "finish"),
    ("public static void finishFromService(Context context, int rounds)", "finishFromService"),
    ("private static void finishInternal(Context context, int rounds,", "finishInternal"),
    ("public static void markWaiting(Context context, int finishedRound,", "markWaiting"),
]:
    s = rep(n, s, sig, sig.replace("static void", "static synchronized void"), tag + " 加锁")

# onRepeatAlarm 实时配置复核
s = rep(n, s,
'''            int total = session.getTotalRounds();
            if (total > 0 && round > total) {
                finish(app, Math.max(1, round - 1));
                return;
            }
            session.setRound(round);''',
'''            int total = session.getTotalRounds();
            if (total > 0 && round > total) {
                finish(app, Math.max(1, round - 1));
                return;
            }
            // modified: 【v4.2】用户在这轮等待期间把「重复间隔」改成 0 或关掉响铃时，
            // 已注册进 AlarmManager 的闹钟不会自动撤销，到点仍会触发。原实现不复核配置，
            // 于是「我明明关了重复，它又响了一轮」。这里用实时配置再判一次。
            AppConfig config = AppConfig.get(app);
            if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total,
                    config.getRingDurationSec(), config.getRepeatIntervalMin())) {
                Log.i(TAG, "repeat disabled by current config, finish session");
                finish(app, Math.max(1, round - 1));
                return;
            }
            session.setRound(round);''', "闹钟到点复核实时配置")

# snooze 不复活已结束会话
s = rep(n, s,
'''            AlertScheduler.cancel(app);
            AlertSession session = AlertSession.get(app);
            session.setActive(true);
            int nextRound = session.getRound() + 1;''',
'''            AlertSession session = AlertSession.get(app);
            // modified: 【v4.2 独立复审】原实现无条件 setActive(true)。
            // 「已结束」通知也带「稍后提醒」按钮，用户点一下就会把 stop() 过的死会话
            // 重新激活并挂新闹钟，状态机出现「死会话复活」。这里先校验。
            if (!session.isActive()) {
                Log.i(TAG, "session already finished, ignore snooze");
                return;
            }
            AlertScheduler.cancel(app);
            int nextRound = session.getRound() + 1;''', "snooze 会话状态校验")

# postQuiet 不带动作
s = rep(n, s,
'''        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status);
    }''',
'''        // modified: 【v4.2】状态通知（等待中 / 已稍后 / 已结束）不挂操作按钮，
        // 避免用户从「已结束」通知上把会话复活；划掉也不再终止会话。
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status, false, false);
    }''', "postQuiet 无动作")

# requestServiceStop 存活判断
s = rep(n, s,
'''    private static void requestServiceStop(Context app) { // modified: 增加 stopService 兜底
        try {''',
'''    private static void requestServiceStop(Context app) { // modified: 增加 stopService 兜底
        // modified: 【v4.2 独立复审】服务已经 stopSelf 之后，startService 会把它
        // 重新创建一次（onCreate -> ACTION_STOP -> stopSelf -> onDestroy），纯属空转；
        // 且 Android 8+ 在应用完全后台时会直接拒绝。只在服务确实存活时发 STOP action。
        if (!SMSForegroundService.isRunning()) {
            Log.d(TAG, "alert service not running, skip STOP action");
            return;
        }
        try {''', "requestServiceStop 存活判断")
write(n, s)

# ============================================================ AlertActivity
n = "AlertActivity.java"
s = read(n)
s = rep(n, s,
'''    private String body = "";

    @Override
    protected void onCreate''',
'''    private String body = "";
    /** modified: 【v4.2】记录本实例已经为哪一輪启动过服务，用于 onNewIntent 去重。 */
    private int lastStartedRound = -1;

    @Override
    protected void onCreate''', "轮次字段")

s = rep(n, s,
'''        if (savedInstanceState == null) {
            startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
        }''',
'''        if (savedInstanceState == null) {
            lastStartedRound = AlertSession.get(this).getRound(); // modified: 登记已启动轮次
            startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
        }''', "onCreate 登记轮次")

s = rep(n, s,
'''        setIntent(intent);
        bindContent(intent);
        startAlertService(); // modified: 新一轮提醒复用本实例时重新播放
    }''',
'''        setIntent(intent);
        bindContent(intent);
        // modified: 【v4.2 独立复审】原实现无条件 startAlertService()。
        // 全屏告警页已显示、铃声正在响时，用户点通知横幅/正文会走 onNewIntent，
        // 服务因此重新 handleStartAlert：取消本轮定时器、reset MediaPlayer、从头播放，
        // 反复点通知就能让这一轮永远响不完。只有轮次真的变了才需要重新启动。
        int incomingRound = AlertSession.get(this).getRound();
        if (incomingRound != lastStartedRound) {
            lastStartedRound = incomingRound;
            startAlertService(); // modified: 仅新一轮重启
        } else {
            Log.d(TAG, "same round re-delivered, keep current playback");
        }
    }''', "onNewIntent 去重")
write(n, s)

print("批次 1（通知/去重/调度/告警页）完成，共 %d 处" % len(changes))
for name, tag in changes:
    print("  %-32s %s" % (name, tag))
