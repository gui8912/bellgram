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
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

/** 铃声来源：统一取系统当前闹钟音，应用内不再打包音频资源。 */
public final class AlertRingtone { // 新增 v4.5.3

    private static final String TAG = "BellgramRingtone"; // 新增

    private AlertRingtone() { // 新增
    } // 新增

    /**
     * 取系统当前闹钟音；不可用时依次退到通知音、来电铃声。
     *
     * @return 可直接交给 {@code MediaPlayer.setDataSource(Context, Uri)} 的 Uri；
     *         没有任何可用系统音时返回 {@code null}。
     */
    public static Uri alarmUri(Context context) { // 新增
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

    private static Uri getDefault(Context context, int type) { // 新增
        try { // 新增
            return RingtoneManager.getDefaultUri(type); // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "getDefaultUri(" + type + ") failed", e); // 新增
            return null; // 新增
        } // 新增
    } // 新增
} // 新增
