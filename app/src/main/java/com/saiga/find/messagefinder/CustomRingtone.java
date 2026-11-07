package com.saiga.find.messagefinder;

// [新增文件] v4.6.0：设置页自定义闹铃声的存储与导入。
//
// 为什么把音频「复制一份到应用私有目录」，而不是直接保存用户选中的 content:// Uri：
//   ① 系统铃声选择器 / 文件选择器返回的是**临时** URI 读授权，进程重启后多半失效；
//      SAF 虽可 takePersistableUriPermission，但系统铃声库返回的 media://  URI 不行，
//      两条入口的行为会不一致，用户换完铃声「重启手机就变回系统音」。
//   ② 通知渠道音由系统进程读取，跨进程 URI 授权在国产 ROM 上更不可控。
//   ③ 复制成私有文件后，MediaPlayer 只认本地路径，不依赖任何运行时权限、不申请
//      READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE，也不需要 FileProvider 跨进程授权。
// 代价是占用一份用户自己的音频副本（上限 30 MB），只在用户主动设置时产生，
// APK 内**仍然不打包任何音频**，v4.5.3 的版权与体积结论不变。
//
// 与「通知那一声」的边界（必须讲清楚，否则用户会以为改了没生效）：
//   · 通知横幅自带的提示音由 NotificationHelper 的 CHANNEL_ID_AUDIBLE 渠道播放，
//     渠道声音创建后不可修改，且系统进程无法可靠读取应用私有文件，故仍用系统闹钟音；
//   · 自定义铃声作用于 SMSForegroundService 的 60 秒循环播放与「试响一次」。
//   v4.5.9 已用 1.5 秒 setAlarmClock 兜底保证亮屏时播放服务必被拉起，
//   所以用户听到的主体铃声就是自定义音。
//
// 导入时同步做一次 MediaPlayer.prepare() 校验：格式不支持 / 文件损坏当场失败并保留旧铃声，
// 不会等到来短信才发现「响一声就没了」。

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 用户自定义铃声：导入、校验、读取、清除。全部为应用私有数据，不外传。 */
public final class CustomRingtone { // 新增 v4.6.0

    private static final String TAG = "BellgramCustomTone"; // 新增

    /** 私有副本文件名。刻意不带扩展名：MediaPlayer 按内容嗅探格式，避免多份残留。 */
    private static final String FILE_NAME = "custom_alert_tone"; // 新增
    private static final String FILE_TMP = "custom_alert_tone.tmp"; // 新增
    /** 用户自选音频的体积上限，防止误选整张专辑把私有目录撑爆。 */
    private static final long MAX_BYTES = 30L * 1024L * 1024L; // 新增
    private static final int MIN_BYTES = 512; // 新增：小于这个基本不可能是有效音频

    /** 显示名键。只用于设置页摘要与诊断，不参与播放。 */
    private static final String KEY_LABEL = "custom_ringtone_label_v460"; // 新增

    /** 导入成功。 */
    public static final int ERR_NONE = 0; // 新增
    /** 读不到所选内容（授权失效 / 文件被删 / provider 异常）。 */
    public static final int ERR_READ = 1; // 新增
    /** 超过 30 MB。 */
    public static final int ERR_TOO_LARGE = 2; // 新增
    /** 不是本机可解码的音频。 */
    public static final int ERR_UNPLAYABLE = 3; // 新增
    /** 写入私有目录失败。 */
    public static final int ERR_WRITE = 4; // 新增

    private CustomRingtone() { // 新增
    } // 新增

    private static SharedPreferences prefs(Context context) { // 新增
        Context app = context.getApplicationContext() != null // 新增
                ? context.getApplicationContext() : context; // 新增
        return PreferenceManager.getDefaultSharedPreferences(app); // 新增
    } // 新增

    private static File file(Context context) { // 新增
        Context app = context.getApplicationContext() != null // 新增
                ? context.getApplicationContext() : context; // 新增
        return new File(app.getFilesDir(), FILE_NAME); // 新增
    } // 新增

    private static File tmpFile(Context context) { // 新增
        Context app = context.getApplicationContext() != null // 新增
                ? context.getApplicationContext() : context; // 新增
        return new File(app.getFilesDir(), FILE_TMP); // 新增
    } // 新增

    /** @return 是否已经成功导入自定义铃声。 */
    public static boolean hasCustom(Context context) { // 新增
        if (context == null) { // 新增
            return false; // 新增
        } // 新增
        try { // 新增
            File f = file(context); // 新增
            return f.isFile() && f.length() >= MIN_BYTES; // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "check custom tone failed", e); // 新增
            return false; // 新增
        } // 新增
    } // 新增

    /** @return 可直接交给 MediaPlayer.setDataSource(Context, Uri) 的 Uri；无自定义时返回 null。 */
    public static Uri uri(Context context) { // 新增
        return hasCustom(context) ? Uri.fromFile(file(context)) : null; // 新增
    } // 新增

    /** @return 用户在设置页看到的名称；异常/未命名时给一个可读兜底。 */
    public static String label(Context context) { // 新增
        if (context == null) { // 新增
            return ""; // 新增
        } // 新增
        String stored; // 新增
        try { // 新增：与 AppConfig 一样做类型兜底，prefs 被写成别的类型不能让设置页崩
            stored = prefs(context).getString(KEY_LABEL, ""); // 新增
        } catch (ClassCastException e) { // 新增
            stored = ""; // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "read custom tone label failed", e); // 新增
            stored = ""; // 新增
        } // 新增
        return TextUtils.isEmpty(stored) ? "自定义音频" : stored; // 新增
    } // 新增

    /**
     * 从选择器返回的 Uri 读出显示名。
     * 优先走 RingtoneManager（系统铃声库能拿到「卡农」这类标题），
     * 再退到 SAF 的 OpenableColumns.DISPLAY_NAME，最后用路径末段。
     */
    public static String displayName(Context context, Uri uri) { // 新增
        if (context == null || uri == null) { // 新增
            return "自定义音频"; // 新增
        } // 新增
        try { // 新增
            android.media.Ringtone ringtone = RingtoneManager.getRingtone(context, uri); // 新增
            if (ringtone != null) { // 新增
                String title = ringtone.getTitle(context); // 新增
                if (!TextUtils.isEmpty(title)) { // 新增
                    return title; // 新增
                } // 新增
            } // 新增
        } catch (Exception e) { // 新增
            Log.d(TAG, "ringtone title unavailable, fall back to display name"); // 新增
        } // 新增
        Cursor cursor = null; // 新增
        try { // 新增
            cursor = context.getContentResolver().query(uri, null, null, null, null); // 新增
            if (cursor != null && cursor.moveToFirst()) { // 新增
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); // 新增
                if (idx >= 0) { // 新增
                    String name = cursor.getString(idx); // 新增
                    if (!TextUtils.isEmpty(name)) { // 新增
                        return name; // 新增
                    } // 新增
                } // 新增
            } // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "query display name failed", e); // 新增
        } finally { // 新增
            if (cursor != null) { // 新增
                try { // 新增
                    cursor.close(); // 新增
                } catch (Exception ignored) { // 新增
                } // 新增
            } // 新增
        } // 新增
        String last = uri.getLastPathSegment(); // 新增
        return TextUtils.isEmpty(last) ? "自定义音频" : last; // 新增
    } // 新增

    /**
     * 把所选音频复制成私有副本并校验可播放。
     * 全程只写 .tmp，校验通过后才替换正式文件；任何失败都保留原有铃声不变。
     *
     * @return {@link #ERR_NONE} 或具体错误码
     */
    public static int importFromUri(Context context, Uri uri, String displayLabel) { // 新增
        if (context == null || uri == null) { // 新增
            return ERR_READ; // 新增
        } // 新增
        File tmp = tmpFile(context); // 新增
        try { // 新增
            if (tmp.exists() && !tmp.delete()) { // 新增
                Log.w(TAG, "stale tmp file cannot be removed"); // 新增
            } // 新增
            InputStream in = null; // 新增
            try { // 新增
                in = context.getContentResolver().openInputStream(uri); // 新增
                if (in == null) { // 新增
                    return ERR_READ; // 新增
                } // 新增
                long total = 0; // 新增
                FileOutputStream out = new FileOutputStream(tmp); // 新增
                try { // 新增
                    byte[] buffer = new byte[64 * 1024]; // 新增
                    int read; // 新增
                    while ((read = in.read(buffer)) != -1) { // 新增
                        total += read; // 新增
                        if (total > MAX_BYTES) { // 新增
                            Log.w(TAG, "selected audio exceeds size limit"); // 新增
                            return ERR_TOO_LARGE; // 新增
                        } // 新增
                        out.write(buffer, 0, read); // 新增
                    } // 新增
                    out.flush(); // 新增
                } finally { // 新增
                    try { // 新增
                        out.close(); // 新增
                    } catch (Exception ignored) { // 新增
                    } // 新增
                } // 新增
            } finally { // 新增
                if (in != null) { // 新增
                    try { // 新增
                        in.close(); // 新增
                    } catch (Exception ignored) { // 新增
                    } // 新增
                } // 新增
            } // 新增
            if (tmp.length() < MIN_BYTES) { // 新增
                return ERR_READ; // 新增
            } // 新增
            if (!isPlayable(tmp)) { // 新增
                return ERR_UNPLAYABLE; // 新增
            } // 新增
            File target = file(context); // 新增
            if (target.exists() && !target.delete()) { // 新增
                Log.w(TAG, "delete previous custom tone failed"); // 新增
            } // 新增
            if (!tmp.renameTo(target)) { // 新增
                return ERR_WRITE; // 新增
            } // 新增
            String safeLabel = TextUtils.isEmpty(displayLabel) ? "自定义音频" : displayLabel; // 新增
            if (safeLabel.length() > 80) { // 新增
                safeLabel = safeLabel.substring(0, 80); // 新增
            } // 新增
            prefs(context).edit().putString(KEY_LABEL, safeLabel).commit(); // 新增：同步落盘
            Log.i(TAG, "custom tone imported: " + safeLabel + ", " + target.length() + " bytes"); // 新增
            return ERR_NONE; // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "import custom tone failed", e); // 新增
            return ERR_READ; // 新增
        } finally { // 新增
            if (tmp.exists()) { // 新增
                try { // 新增
                    tmp.delete(); // 新增
                } catch (Exception ignored) { // 新增
                } // 新增
            } // 新增
        } // 新增
    } // 新增

    /** 用一次同步 prepare 确认本机确实能解码这个文件，避免等真实短信才暴露格式问题。 */
    private static boolean isPlayable(File candidate) { // 新增
        MediaPlayer probe = null; // 新增
        try { // 新增
            probe = new MediaPlayer(); // 新增
            probe.setDataSource(candidate.getAbsolutePath()); // 新增
            probe.prepare(); // 新增
            return true; // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "selected audio is not playable", e); // 新增
            return false; // 新增
        } finally { // 新增
            if (probe != null) { // 新增
                try { // 新增
                    probe.release(); // 新增
                } catch (Exception ignored) { // 新增
                } // 新增
            } // 新增
        } // 新增
    } // 新增

    /** 恢复跟随系统闹钟音：删掉私有副本与显示名。 */
    public static boolean clearCustom(Context context) { // 新增
        if (context == null) { // 新增
            return false; // 新增
        } // 新增
        try { // 新增
            File target = file(context); // 新增
            if (target.exists() && !target.delete()) { // 新增
                Log.w(TAG, "delete custom tone failed"); // 新增
            } // 新增
            prefs(context).edit().remove(KEY_LABEL).commit(); // 新增
            return true; // 新增
        } catch (Exception e) { // 新增
            Log.w(TAG, "clear custom tone failed", e); // 新增
            return false; // 新增
        } // 新增
    } // 新增

    /**
     * 系统铃声选择器里选「默认」时应当恢复跟随系统，而不是把动态 Uri 复制成静态副本。
     * content://settings/system/* 是系统当前铃声的**引用**，复制它会导致以后改系统铃声不跟随。
     */
    public static boolean isSystemDefaultUri(Context context, Uri uri) { // 新增
        if (uri == null) { // 新增
            return true; // 新增
        } // 新增
        String text = uri.toString(); // 新增
        if (text.startsWith("content://settings/system/")) { // 新增
            return true; // 新增
        } // 新增
        try { // 新增
            Uri systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); // 新增
            return systemDefault != null && systemDefault.equals(uri); // 新增
        } catch (Exception e) { // 新增
            return false; // 新增
        } // 新增
    } // 新增
} // 新增
