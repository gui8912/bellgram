package com.saiga.find.messagefinder;

// [新增文件] v4.5.3：应用不再内置任何音频文件，铃声统一跟随系统闹钟音。
//
// 为什么删掉 res/raw/priority_ringtone.mp3：
//   ① 音质：原文件虽是 44.1 kHz / 256 kbps / 联合立体声，编码参数并不差，
//      「灰蒙蒙」来自音源本身（泛音少、混响糊、动态平），换码率救不回来；
//   ② 体积：它是 APK 里最大的单个自有资源，占约 31.6%（裁剪后仍约 480 KB）；
//   ③ 可换：内置音频在设置页没有任何入口，用户改不了；走系统闹钟音后，
//      用户可在「系统设置 → 声音 / 闹钟」里自由换成自己喜欢的钢琴曲；
//   ④ 版权：知名演奏家的录音受唱片版权保护，打包进公开 GitHub Release
//      的 APK 属于公开发行，不能随应用分发；由用户自己设到手机里则没有问题。
//
// 与通知渠道的一致性：NotificationHelper 的「先响一声」本来就取
// RingtoneManager.getDefaultUri(TYPE_ALARM)，此前持续播放却用内置 mp3，
// 两段音色不一致；现在两端都指向同一个系统闹钟音。
//
// 兜底链：闹钟音 → 通知音 → 来电铃声 → null。
// 部分 ROM 把闹钟音设成「无」时 getDefaultUri 会返回 null，逐级退让；
// 仍为 null 时返回 null，调用方走既有失败分支：
//   · SMSForegroundService → fallbackToNotificationSound()（至少响一声通知音）
//   · AlertPreviewPlayer   → RESULT_FAILED（明确提示去系统里设铃声）
//
// 【修改】v4.6.0：设置页新增「提醒铃声」，用户可自定义。alarmUri() 的语义由
//   「系统闹钟音」扩展为「本应用最终采用的铃声」：自定义副本优先，没有/损坏时
//   退回原系统兜底链。两个调用点（SMSForegroundService / AlertPreviewPlayer）
//   一行都不用改就自然跟随；新增 systemUri() 保留原语义供设置页与诊断使用。
//   私有副本由 CustomRingtone 管理，只存在于用户手机的应用数据里，
//   APK 仍不打包任何音频，v4.5.3 的版权与体积结论不变。
//   通知渠道那一声仍由 NotificationHelper 独立取系统闹钟音（渠道声音创建后不可改，
//   且系统进程无法可靠读取应用私有文件），说明文件里已写明这条边界。
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

/** 铃声来源：自定义副本优先，未设置时跟随系统当前闹钟音；应用内不打包音频资源。 */
public final class AlertRingtone { // 新增 v4.5.3

    private static final String TAG = "BellgramRingtone"; // 新增

    private AlertRingtone() { // 新增
    } // 新增

    /**
     * 取「本应用此刻实际会播放」的铃声：自定义副本优先，未设置时跟随系统闹钟音。
     * 这是短信提醒与试响共用的唯一入口，两处调用点都无需感知自定义逻辑。
     *
     * @return 可直接交给 {@code MediaPlayer.setDataSource(Context, Uri)} 的 Uri；
     *         自定义文件不可用且系统也没有任何可用音时返回 {@code null}。
     */
    public static Uri alarmUri(Context context) { // 新增 / modified: v4.6.0
        if (context == null) { // 新增 v4.6.0
            return null; // 新增 v4.6.0
        } // 新增 v4.6.0
        try { // 新增 v4.6.0：自定义数据读失败绝不能掐断提醒主链路
            Uri custom = CustomRingtone.uri(context); // 新增 v4.6.0
            if (custom != null) { // 新增 v4.6.0
                return custom; // 新增 v4.6.0
            } // 新增 v4.6.0
        } catch (Exception e) { // 新增 v4.6.0
            Log.w(TAG, "read custom tone failed, fall back to system alarm", e); // 新增 v4.6.0
        } // 新增 v4.6.0
        return systemUri(context); // 新增 v4.6.0
    } // 新增

    /**
     * 【新增 v4.6.0】v4.5.3 的原语义：只取系统当前闹钟音，不可用时依次退到通知音、来电铃声。
     * 设置页「跟随系统」选项、铃声选择器初值、诊断文案用这个，不受自定义副本影响。
     *
     * @return 系统铃声 Uri；没有任何可用系统音时返回 {@code null}。
     */
    public static Uri systemUri(Context context) { // 新增 v4.6.0（由原 alarmUri 拆出）
        Context app = context.getApplicationContext() != null // 新增
                ? context.getApplicationContext() : context; // 新增
        Uri uri = getDefault(app, RingtoneManager.TYPE_ALARM); // 新增
        if (uri == null) { // 新增
            Log.w(TAG, "no system alarm sound, fallback to notification"); // 新增
            uri = getDefault(app, RingtoneManager.TYPE_NOTIFICATION); // 新增
        } // 新增
        if (uri == null) { // 新增
            Log.w(TAG, "no notification sound either, fallback to ringtone"); // 新增
            uri = getDefault(app, RingtoneManager.TYPE_RINGTONE); // 新增
        } // 新增
        return uri; // 新增
    } // 新增

    /**
     * 【新增 v4.6.0】诊断快照用的中文描述。只读，不写任何配置。
     * 与 SmsTrace.snapshot() 其它行的中文风格保持一致，故这里不做多语言。
     */
    public static String sourceLabel(Context context) { // 新增 v4.6.0
        try { // 新增 v4.6.0
            if (CustomRingtone.hasCustom(context)) { // 新增 v4.6.0
                return "自定义：" + CustomRingtone.label(context); // 新增 v4.6.0
            } // 新增 v4.6.0
        } catch (Exception e) { // 新增 v4.6.0
            Log.w(TAG, "build source label failed", e); // 新增 v4.6.0
        } // 新增 v4.6.0
        return "跟随系统闹钟音"; // 新增 v4.6.0
    } // 新增

    private static Uri getDefault(Context context, int type) { // 新增
        try { // 新增
            return RingtoneManager.getDefaultUri(type); // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "getDefaultUri(" + type + ") failed", e); // 新增
            return null; // 新增
        } // 新增
    } // 新增
} // 新增
