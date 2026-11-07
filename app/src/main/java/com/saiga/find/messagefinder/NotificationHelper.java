package com.saiga.find.messagefinder;

// [v4 重写 / modified] 通知与通知渠道的唯一构建入口。
//
// v3 的定位是「服务内的通知 + 服务起不来时的降级通知」；
// v4 架构重构后，通知变成**主链路的唯一唤醒入口**：
//   · 广播接收器（后台）只发通知，绝不启动前台服务；
//   · 通知自带闹铃音 + 全屏 Intent，锁屏/息屏时拉起 AlertActivity；
//   · 用户可见的 Activity 才启动前台服务，把通知换到静音渠道后由 MediaPlayer 独占响铃。
//
// 因此需要两个渠道，且语义与 v3 相反：
//   CHANNEL_ID_AUDIBLE（带闹铃音）：广播/闹钟发出的「第一声」，保证服务起不来也能响；
//   CHANNEL_ID_ALERT  （静音）    ：服务已接手播放后使用，避免通知音 + MediaPlayer 双重响铃。
// 渠道声音一旦创建不可修改，所以换用全新的 ID，而不是去改老用户设备上已存在的渠道。
//
// 参考：Android 官方《Create a notification / Full-screen intents》《Full-screen intent limits》(AOSP)。
// 未复制第三方代码。

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** 优先级提醒通知构建器。 */
public final class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    /**
     * 静音渠道：服务已接手播放铃声时使用，防止双重响铃。
     *
     * <p>【独立复审修正】原实现沿用 v3 的 {@code priority_alert_v2}，这是错的：
     * 通知渠道的声音属性**创建后不可修改**，而 v3 是用「带提示音」创建的该渠道。
     * 老用户覆盖安装后，v4 里 {@code setSound(null, null)} 对已存在的渠道完全不生效，
     * 结果是「通知音 + MediaPlayer」双重响铃——正是本次改造要消除的问题。
     * 因此静音渠道也必须换新 ID，并在 {@link #ensureChannels} 里顺手删掉旧渠道。
     */
    public static final String CHANNEL_ID_ALERT = "priority_alert_quiet_v4"; // modified: 换新 ID，旧渠道声音不可变
    /** 带闹铃音的主提醒渠道：v4 新 ID，避免旧渠道声音不可变的历史包袱。 */
    public static final String CHANNEL_ID_AUDIBLE = "priority_alert_audible_v4"; // modified: 新增
    /** v3 遗留渠道，仅用于清理（声音属性创建后不可改，留着只会在系统设置里多出一条）。 */
    private static final String CHANNEL_ID_LEGACY_V3 = "priority_alert_v2"; // modified: 新增
    /** 兼容旧调用点的别名（v3 的「降级渠道」语义已废弃）。 */
    public static final String CHANNEL_ID_FALLBACK = CHANNEL_ID_AUDIBLE; // modified: 改为别名

    /** 通知 ID，保持与原实现一致，方便用户清理时对应。 */
    public static final int NOTIFICATION_ID = 123;

    private static final int REQ_CONTENT = 10;
    private static final int REQ_STOP = 11;
    private static final int REQ_SNOOZE = 12;
    private static final int REQ_FULLSCREEN = 13;

    private static final String EXTRA_FROM = "From";
    private static final String EXTRA_KEYWORD = "Keyword";
    private static final String EXTRA_PAYLOAD = "Payload";
    private static final String EXTRA_PERSON = "Person";

    private NotificationHelper() {
        // 工具类不允许实例化
    }

    /** 创建（或更新）两个通知渠道。重复调用是安全的。 */
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
        try { // modified
            // modified: 清掉 v3 遗留的旧渠道（声音属性创建后不可改，留着只会在系统设置里多出一条）
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V3); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "delete legacy channel failed", e); // modified
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
            tryBypassDnd(alertChannel); // modified
            manager.createNotificationChannel(alertChannel);
        } catch (Exception e) { // modified
            Log.e(TAG, "create quiet channel failed", e); // modified
        }
    }

    private static void createAudibleChannel(NotificationManager manager, Context context) { // modified: 新增
        try { // modified
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
        } catch (Exception e) { // modified
            Log.e(TAG, "create audible channel failed", e); // modified
        }
    }

    private static void tryBypassDnd(NotificationChannel channel) { // modified: 新增
        try { // modified
            channel.setBypassDnd(true); // modified
        } catch (Exception e) {
            Log.w(TAG, "setBypassDnd unsupported on this ROM", e); // modified
        }
    }

    /** 渠道是否真实存在。创建失败 / 被用户在系统设置里删掉时返回 false。 */
    private static boolean channelExists(Context context, String channelId) { // modified: 新增
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { // modified
            return true; // API 25 及以下没有渠道概念 // modified
        }
        try { // modified
            NotificationManager manager = // modified
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE); // modified
            return manager != null && manager.getNotificationChannel(channelId) != null; // modified
        } catch (Exception e) { // modified
            return false; // modified
        }
    }

    /** 构建提醒通知（不带轮次状态）。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body) {
        return build(context, channelId, sender, keyword, personName, body, null, false, true); // modified
    }

    /** 构建提醒通知，并附带一行「第几次提醒 / 几分钟后再响 / 已结束」。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status) {
        return build(context, channelId, sender, keyword, personName, body, status, false, true); // modified
    }

    /**
     * 构建提醒通知。
     *
     * @param withFullScreenIntent 是否附带全屏 Intent（锁屏/息屏时自动拉起告警页）。
     *                             **只有「真正要叫人」的那一轮才传 true**。
     *                             服务的前台通知、以及「等待中 / 已稍后 / 已结束」这类
     *                             纯状态更新必须传 false——否则会出现「已经点过停止，
     *                             锁屏却又弹出一个写着『提醒已结束』的全屏页」，
     *                             甚至与服务前台通知互相触发形成重复拉起。
     */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status,
                                     boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        return build(context, channelId, sender, keyword, personName, body, status, // modified
                withFullScreenIntent, true); // modified
    }

    /**
     * 完整构建入口。
     *
     * @param withActions 是否附带「停止 / 稍后提醒」按钮。
     *                    「等待中 / 已稍后 / 已结束」这类纯状态通知传 false ——
     *                    否则用户能从一条「已结束」通知上点「稍后提醒」，把已停止的会话复活。
     */
    public static Notification build(Context context, String channelId, String sender, // modified
                                     String keyword, String personName, String body, // modified
                                     CharSequence status, boolean withFullScreenIntent, // modified
                                     boolean withActions) { // modified: v4.2 新增动作开关
        String displaySender = TextUtils.isEmpty(personName) ? safeText(sender) : personName;
        String displayKeyword = safeText(keyword);
        String displayBody = safeText(body);

        CharSequence summaryText = TextUtils.isEmpty(status)
                ? context.getString(R.string.notif_summary) : status;
        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(context.getString(R.string.notif_title))
                .bigText(context.getString(R.string.notif_bigtext, displayBody, displaySender))
                .setSummaryText(summaryText);

        // 注意：刻意不使用 setOnlyAlertOnce(true)。每一轮重复提醒都需要重新出声，
        // 加上该标志会导致第二轮通知静默。
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(context.getString(R.string.notif_text, displayKeyword, displaySender))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                // modified: 原写法 getColor(resId, null) 第二个参数传 null，
                // 在部分 ROM / API 23 上 theme.resolveAttribute 会 NPE；改用兼容入口。
                .setColor(ContextCompat.getColor(context, R.color.secondaryDarkColor))
                .setColorized(true)
                .setShowWhen(false)
                .setAutoCancel(false)
                .setOngoing(false)
                .setStyle(bigTextStyle)
                .setSmallIcon(R.drawable.notification_mf_icon)
                // modified: 点击通知正文 -> 打开全屏告警页（进而合法启动服务响铃），
                // 而不是只打开主界面；亮屏状态下这是用户获得强提醒的入口。
                .setContentIntent(buildAlertScreenIntent(context, REQ_CONTENT,
                        sender, keyword, personName, body));
        // modified: 【v4.2】minSdk 23 覆盖 API 23-25，这三代系统没有通知渠道，
        // 渠道上配的闹铃音完全不存在；渠道创建失败时同理。补 builder 级声音/震动，
        // 保证「第一声」在任何情况下都不会变成一条静音横幅。
        // API 26+ 且渠道正常时 builder 的声音会被渠道设置覆盖，不会造成双重响铃。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channelExists(context, channelId)) { // modified
            Uri legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); // modified
            if (legacySound == null) { // modified
                legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); // modified
            }
            builder.setSound(legacySound, AudioAttributes.USAGE_ALARM); // modified
            builder.setVibrate(new long[]{0, 400, 300, 400}); // modified
        }
        if (!TextUtils.isEmpty(status)) {
            builder.setSubText(status);
        }
        // modified: 【v4.2 独立复审】原实现给所有通知（含「已结束」状态通知）都挂按钮，
        // 且把「划掉通知」等同于「停止整个会话」。后者与项目核心需求
        // 「用户不处理就 5 分钟后再提醒」直接冲突：随手划掉横幅会取消后续全部轮次。
        // 现在：只有真正要叫人的一轮带按钮；划掉通知不再改变会话状态，
        // 想彻底停止请点明确的「停止」按钮或告警页里的停止。
        if (withActions) { // modified
            builder.addAction(R.drawable.ic_block_24dp, // modified
                    context.getString(R.string.stop), buildStopIntent(context)); // modified
            builder.addAction(0, // modified
                    context.getString(R.string.notif_snooze), buildSnoozeIntent(context)); // modified
        }

        // modified: 仅当调用方明确要求、且系统确实允许时才挂全屏 Intent。
        // 未授予时不设置，让系统退化为悬挂通知（HUN），用户点击进入告警页；
        // 强行设置不仅无效，部分 ROM 还会抛异常。
        if (withFullScreenIntent && canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(buildAlertScreenIntent(context, REQ_FULLSCREEN,
                    sender, keyword, personName, body), true);
        }
        return builder.build();
    }

    /**
     * 发（或更新）通知。这是所有组件发通知的唯一出口。
     *
     * <p>统一处理 targetSdk 33+ 的 POST_NOTIFICATIONS：未授权时 AndroidX 会抛
     * SecurityException，通知总开关被关时直接放弃并记日志——两种情况都不能让应用崩溃。
     */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status) {
        // 默认不带全屏 Intent：绝大多数调用都是「更新状态」而不是「叫人」。
        post(context, channelId, sender, keyword, person, body, status, false, true); // modified
    }

    /**
     * 发（或更新）通知，显式控制是否附带全屏 Intent。
     *
     * @param withFullScreenIntent true = 锁屏/息屏时自动拉起告警页（仅用于真正要提醒的那一轮）
     */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status,
                            boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        post(context, channelId, sender, keyword, person, body, status, // modified
                withFullScreenIntent, true); // modified
    }

    /** 完整发通知入口，可同时控制全屏 Intent 与通知动作。 */
    public static void post(Context context, String channelId, String sender, String keyword, // modified
                            String person, String body, CharSequence status, // modified
                            boolean withFullScreenIntent, // modified
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
                // 【新增 v4.5.6】通知是主链路唯一的唤醒入口，这里直接返回＝100% 没反应。
                // 落到可见诊断，用户才知道该去系统设置开哪一项。
                SmsTrace.skip(app, "通知被系统关闭",
                        "请到 系统设置 → 应用 → 铃达 → 通知，打开「允许通知」及该通知类别");
                return;
            }
            ensureChannels(app);
            // modified: 【v4.2】渠道不存在时 notify() 会被系统静默丢弃且不抛异常。
            // 再建一次；仍失败则退回系统默认渠道，至少保证通知能显示出来。
            String effectiveChannel = channelId; // modified
            if (!channelExists(app, channelId)) { // modified
                ensureChannels(app); // modified
                if (!channelExists(app, channelId)) { // modified
                    effectiveChannel = NotificationChannel.DEFAULT_CHANNEL_ID; // modified
                    Log.w(TAG, "target channel missing, fall back to default channel"); // modified
                    SmsTrace.add(app, "通知渠道降级", "目标渠道不可用，改用系统默认渠道；可能无声"); // 新增 v4.5.6
                }
            }
            manager.notify(NOTIFICATION_ID,
                    build(app, effectiveChannel, sender, keyword, person, body, status, // modified
                            withFullScreenIntent, withActions)); // modified
            // 【新增 v4.5.6】走到这里说明应用侧已把通知交给系统。若用户仍说没反应，
            // 问题就在系统侧：通知类别被静音、全屏 Intent 特殊权限未授予、或 ROM 拦截后台弹窗。
            SmsTrace.add(app, withFullScreenIntent ? "提醒通知已提交" : "状态通知已更新",
                    "渠道=" + effectiveChannel + "；仍无动静请查系统通知类别与全屏权限");
        } catch (SecurityException e) {
            Log.e(TAG, "POST_NOTIFICATIONS not granted, cannot post alert", e);
            SmsTrace.skip(app, "缺少通知权限", "POST_NOTIFICATIONS 未授予，通知被系统拒绝"); // 新增 v4.5.6
        } catch (Exception e) {
            Log.e(TAG, "post notification failed", e);
            SmsTrace.skip(app, "发通知失败", e.toString()); // 新增 v4.5.6
        }
    }

    /** 撤销提醒通知。 */
    public static void cancel(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            NotificationManagerCompat.from(context.getApplicationContext())
                    .cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w(TAG, "cancel notification failed", e);
        }
    }

    /**
     * 兼容 v3 的降级入口（服务里铃声播放失败时调用）。
     * v4 中它不再是「降级」，而是主动换到带声音渠道再提醒一次。
     */
    public static void notifyDirectly(Context context, String sender, String keyword,
                                      String personName, String body) { // modified: 语义调整
        // modified: 【v4.2 独立复审】原实现这里传 withFullScreenIntent=true。
        // 它只在「播放失败 / startForeground 失败」的降级路径被调用，若再带全屏 Intent，
        // 系统会拉起 AlertActivity，而 AlertActivity 又会 startForegroundService 再失败一次，
        // 形成「失败 -> 发通知 -> 拉页 -> 再失败」的无限循环（通知刷屏、闹铃反复响一声）。
        // 它只是补一声，不需要也不应该再拉页面；动作按钮同样去掉。
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, false, false); // modified
    }

    /**
     * Android 14(API 34)+ 起，USE_FULL_SCREEN_INTENT 不再默认授予非「通话/闹钟」类应用。
     * 系统提供 canUseFullScreenIntent() 供应用自检，用户可在「特殊应用权限」里手动开启。
     */
    public static boolean canUseFullScreenIntent(Context context) { // modified: 新增
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true; // Android 13 及以下安装即授予
        }
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.canUseFullScreenIntent();
        } catch (Exception e) {
            Log.w(TAG, "canUseFullScreenIntent failed", e);
            return false;
        }
    }

    /** 「停止」动作：发广播，不再直接启动服务（后台启动服务同样受限）。 */
    private static PendingIntent buildStopIntent(Context context) { // modified: getService -> getBroadcast
        Intent intent = new Intent(context, AlertActionReceiver.class);
        intent.setAction(AlertActionReceiver.ACTION_STOP);
        return PendingIntent.getBroadcast(context, REQ_STOP, intent, pendingIntentFlags());
    }

    /** 「稍后提醒」动作。 */
    private static PendingIntent buildSnoozeIntent(Context context) { // modified: getService -> getBroadcast
        Intent intent = new Intent(context, AlertActionReceiver.class);
        intent.setAction(AlertActionReceiver.ACTION_SNOOZE);
        return PendingIntent.getBroadcast(context, REQ_SNOOZE, intent, pendingIntentFlags());
    }

    /**
     * 全屏告警页的 PendingIntent。显式 Intent（targetSdk 34+ 对隐式 Intent 有严格限制）。
     *
     * @param requestCode 内容点击与全屏 Intent 用不同 requestCode，避免 PendingIntent 互相覆盖
     */
    private static PendingIntent buildAlertScreenIntent(Context context, int requestCode,
                                                        String sender, String keyword,
                                                        String person, String body) { // modified: 新增
        Intent intent = new Intent(context, AlertActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        putAlertExtras(intent, sender, keyword, person, body);
        return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags());
    }

    /** Android 12+ 必须显式声明 PendingIntent 可变性。 */
    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static String safeText(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
    }

    /** 打包「提醒请求」所需的 extras。 */
    static void putAlertExtras(Intent intent, String sender, String keyword,
                               String person, String body) {
        intent.putExtra(EXTRA_FROM, sender);
        intent.putExtra(EXTRA_KEYWORD, keyword);
        intent.putExtra(EXTRA_PERSON, person);
        intent.putExtra(EXTRA_PAYLOAD, body);
    }

    static String getFrom(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_FROM));
    }

    static String getKeyword(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_KEYWORD));
    }

    static String getPerson(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_PERSON));
    }

    static String getPayload(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_PAYLOAD));
    }
}
