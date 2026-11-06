package com.saiga.find.messagefinder;

// [新增文件] v4.5.1：试响改为「设置页原地播放」，不再启动前台服务。
//
// 为什么不再用 SMSForegroundService（v4.5.0 的做法，实测在部分机型上无声）：
//   试响只播 8 秒，用户此刻就在设置页看着，根本不需要前台服务的保活能力。
//   借用 mediaPlayback 前台服务反而引入三道平台关卡，任何一道不过就静默失败：
//     ① Android 12+ 一律先从后台启动（startForegroundService 受限）；
//     ② Android 14+ startForeground() 会校验 foregroundServiceType 与对应权限；
//     ③ 前台通知依赖 POST_NOTIFICATIONS，权限被拒时行为随 ROM 而异。
//   三者任一失败，服务在 startForeground 处 catch 后 stopSelf()，
//   扬声器一声不响、震动也不动 —— 表现就是「点了确认什么反应都没有」。
//   改成在这里直接放，失败可以立刻拿到原因并提示用户，而不是吞掉。
//
// 另外两点刻意的设计（与真实提醒的区别）：
//   · 不改系统闹钟音量（真实提醒会改，由 AlarmVolumeGuard 兜底还原）。
//     试响是「听一下」，不该留下任何系统设置副作用；代价是响度受当前系统闹钟音量影响，
//     所以音量正好是 0 时直接告知原因，而不是放一段听不见的声音。
//   · 固定 8 秒，不跟随「单次响铃时长」——那条设置允许 0（一直响），照做会变成停不下来的长铃。
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/** 试响播放器：原地播 8 秒，不建会话、不排闹钟、不启动任何服务。 */
public final class AlertPreviewPlayer { // modified: v4.5.1 新增

    private static final String TAG = "BellgramPreview"; // modified
    private static final long DURATION_MS = 8000L; // modified
    private static final String RINGTONE_RESOURCE_PATH = "raw/priority_ringtone"; // modified

    /** 播放中，已开始计时。 */
    public static final int RESULT_OK = 0; // modified
    /** 上一轮试响还没结束。 */
    public static final int RESULT_ALREADY_PLAYING = 1; // modified
    /** 响铃与震动都被关掉，播了也没有任何效果。 */
    public static final int RESULT_NOTHING_ENABLED = 2; // modified
    /** 系统闹钟音量为 0：本播放器不改系统音量，所以必然听不到。 */
    public static final int RESULT_ALARM_VOLUME_ZERO = 3; // modified
    /** 铃声资源加载失败。 */
    public static final int RESULT_FAILED = 4; // modified

    private final Handler handler = new Handler(Looper.getMainLooper()); // modified
    private final Runnable stopRunnable = new Runnable() { // modified
        @Override
        public void run() { // modified
            stop(); // modified
        } // modified
    }; // modified

    private MediaPlayer player; // modified
    private Vibrator vibrator; // modified
    private boolean playing; // modified

    /** 是否正在试响。同一时刻只允许一次，避免两路声音叠加。 */
    public boolean isPlaying() { // modified
        return playing; // modified
    } // modified

    /**
     * 按当前设置播放一次试响。
     *
     * @return {@link #RESULT_OK} 表示已经开始播放；其余为具体失败原因，调用方据此提示。
     */
    public int play(Context context) { // modified
        if (playing) { // modified
            return RESULT_ALREADY_PLAYING; // modified
        } // modified
        Context app = context.getApplicationContext() != null // modified
                ? context.getApplicationContext() : context; // modified
        AppConfig config = AppConfig.get(app); // modified
        boolean ring = config.isRingEnabled(); // modified
        boolean vibrate = config.isVibrateEnabled(); // modified
        if (!ring && !vibrate) { // modified
            return RESULT_NOTHING_ENABLED; // modified
        } // modified

        // 不改系统音量，所以系统闹钟音量为 0 时必定无声 —— 与其放一段静音让人以为坏了，
        // 不如直接说清楚。跟随系统档位与固定档位都一样：本播放器不动系统流。
        if (ring) { // modified
            AudioManager audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE); // modified
            if (audioManager != null) { // modified
                try { // modified
                    if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) { // modified
                        return RESULT_ALARM_VOLUME_ZERO; // modified
                    } // modified
                } catch (Exception e) { // modified
                    Log.w(TAG, "read alarm volume failed", e); // modified
                } // modified
            } // modified
        } // modified

        if (ring && !startPlayback(app, config)) { // modified
            releasePlayer(); // modified
            return RESULT_FAILED; // modified
        } // modified
        if (vibrate) { // modified
            startVibration(app); // modified
        } // modified
        playing = true; // modified
        handler.postDelayed(stopRunnable, DURATION_MS); // modified
        Log.d(TAG, "preview started, ring=" + ring + ", vibrate=" + vibrate); // modified
        return RESULT_OK; // modified
    } // modified

    private boolean startPlayback(Context app, AppConfig config) { // modified
        try { // modified
            player = new MediaPlayer(); // modified
            player.setAudioAttributes(new AudioAttributes.Builder() // modified
                    .setUsage(AudioAttributes.USAGE_ALARM) // modified: 与真实提醒同一音频用途
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION) // modified
                    .build()); // modified
            Uri uri = Uri.parse("android.resource://" + app.getPackageName() + "/" // modified
                    + RINGTONE_RESOURCE_PATH); // modified
            player.setDataSource(app, uri); // modified
            player.setLooping(true); // modified
            // 渐强开启时从 25% 起步，与真实提醒保持一致，方便用户判断渐强是否合适
            float volume = VolumePolicy.initialPlayerVolume(config.isCrescendoEnabled()); // modified
            player.setVolume(volume, volume); // modified
            player.setOnPreparedListener(mp -> { // modified
                try { // modified
                    mp.start(); // modified
                } catch (Exception e) { // modified
                    Log.w(TAG, "start preview playback failed", e); // modified
                } // modified
            }); // modified
            player.setOnErrorListener((mp, what, extra) -> { // modified
                Log.e(TAG, "preview player error what=" + what + " extra=" + extra); // modified
                stop(); // modified
                return true; // modified
            }); // modified
            player.prepareAsync(); // modified
            return true; // modified
        } catch (Exception e) { // modified
            Log.e(TAG, "prepare preview playback failed", e); // modified
            return false; // modified
        } // modified
    } // modified

    private void startVibration(Context app) { // modified
        try { // modified
            if (vibrator == null) { // modified
                vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE); // modified
            } // modified
            if (vibrator == null || !vibrator.hasVibrator()) { // modified
                return; // modified
            } // modified
            // 与 SMSForegroundService.startVibration 同一套波形，保证「试听到的」就是「真实会有的」
            long[] pattern = new long[]{50, 1000, 400, 900, 300, 800}; // modified
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) { // modified
                int[] amplitudes = new int[]{0, 255, 0, 200, 0, 150}; // modified
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 2)); // modified
            } else { // modified
                vibrator.vibrate(pattern, 2); // modified
            } // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "preview vibrate failed", e); // modified
        } // modified
    } // modified

    /** 停止并释放。页面不可见、页面销毁、播放出错时都必须调用。 */
    public void stop() { // modified
        handler.removeCallbacks(stopRunnable); // modified
        boolean wasPlaying = playing; // modified
        playing = false; // modified
        try { // modified
            if (vibrator != null) { // modified
                vibrator.cancel(); // modified
            } // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "cancel preview vibration failed", e); // modified
        } // modified
        releasePlayer(); // modified
        if (wasPlaying) { // modified
            Log.d(TAG, "preview stopped"); // modified
        } // modified
    } // modified

    private void releasePlayer() { // modified
        try { // modified
            if (player != null) { // modified
                player.reset(); // modified
                player.release(); // modified
            } // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "release preview player failed", e); // modified
        } finally { // modified
            player = null; // modified
        } // modified
    } // modified
} // modified
