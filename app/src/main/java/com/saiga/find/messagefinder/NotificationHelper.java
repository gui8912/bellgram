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

    /** 构建提醒通知（不带轮次状态）。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body) {
        return build(context, channelId, sender, keyword, personName, body, null, false);
    }

    /** 构建提醒通知，并附带一行「第几次提醒 / 几分钟后再响 / 已结束」。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status) {
        return build(context, channelId, sender, keyword, personName, body, status, false);
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
        if (!TextUtils.isEmpty(status)) {
            builder.setSubText(status);
        }
        builder.addAction(R.drawable.ic_block_24dp,
                context.getString(R.string.stop), buildStopIntent(context));
        builder.addAction(0,
                context.getString(R.string.notif_snooze), buildSnoozeIntent(context));
        builder.setDeleteIntent(buildStopIntent(context)); // modified: 划掉通知 = 停止提醒

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
        post(context, channelId, sender, keyword, person, body, status, false);
    }

    /**
     * 发（或更新）通知，显式控制是否附带全屏 Intent。
     *
     * @param withFullScreenIntent true = 锁屏/息屏时自动拉起告警页（仅用于真正要提醒的那一轮）
     */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status,
                            boolean withFullScreenIntent) { // modified: 新增 FSI 开关
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
        // 这条路径的用途是「铃声没能播放，用通知音兜底叫人」，所以需要全屏 Intent。
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, true);
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
