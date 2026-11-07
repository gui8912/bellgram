package com.saiga.find.messagefinder;

// [v4 重写 / modified] 设置页迁移到 AndroidX Preference。
//
// 原实现基于 android.preference（API 28 起已废弃）：PreferenceFragment、SwitchPreference、
// legacy-preference-v14 依赖，以及布局里静态 <fragment> 标签。targetSdk 36 下继续依赖
// 平台废弃 API 既不符合规范，也会在后续 AndroidX 版本里出现主题/样式不兼容。
//
// 迁移要点：
//   · PreferenceFragment -> PreferenceFragmentCompat（onCreatePreferences 装载）；
//   · SwitchPreference   -> SwitchPreferenceCompat（XML 与 Java 两侧同步）；
//   · 布局改成容器 FrameLayout，由 FragmentTransaction 装载，避免 <fragment> 的隐式生命周期问题；
//   · ListPreference 改用官方 SimpleSummaryProvider 显示当前选中项；
//   · 新增「权限体检」入口，集中展示 targetSdk 36 下所有会影响提醒的系统开关。
//
// modified: v4.3 追加三类交互：主题即时切换、闹钟/贪睡等下拉摘要、
// 三个数值项的「自定义…」输入（带范围校验）。参考系统时钟的「响铃时长 / 贪睡时长 /
// 音量按钮」设置项组织方式，未引入任何新依赖。
//
// 参考：Android 官方《Settings / Migrate to AndroidX Preference》。未复制第三方代码。
//
// modified: v4.4.0 两处交互改造：
//   1) 首页「清除配置」按钮迁入本页的「应用配置」分组，点击后先弹二次确认框，
//      确认才调用 AppConfig.clear()；「清空命中记录」同样补确认框；
//   2) 8 个 ListPreference 的点击改由本类显式接管并自行绘制选项列表，
//      不再依赖 AndroidX 默认 ListPreferenceDialogFragmentCompat（见 bindListPreferenceDialogs 注释）。
//
// modified: v4.5.0 —— 把「接管」做彻底：仅挂 OnPreferenceClickListener 是不够的，
//   Preference.performClick() 会先执行 DialogPreference.onClick()（已提交框架弹窗），
//   再回调监听器，于是自绘弹窗与框架弹窗会叠在一起。8 个设置项改用
//   SelfDrawnListPreference（onClick 置空），框架弹窗彻底不再出现。
//   同时修掉两处交互缺陷：自定义数值输入非法时对话框被系统关掉、页面销毁时自绘弹窗窗口泄漏；
//   并新增「试响一次」入口，让用户改完设置能立刻验证。

import android.app.Activity; // 新增 v4.6.0：RESULT_OK
import android.content.Context; // modified
import android.content.Intent; // 新增 v4.6.0：系统铃声选择器 / 文件选择器
import android.media.RingtoneManager; // 新增 v4.6.0
import android.net.Uri; // 新增 v4.6.0
import android.os.Bundle;
import android.os.Handler; // 新增 v4.6.0：导入音频后回主线程刷新
import android.os.Looper; // 新增 v4.6.0
import android.text.InputType; // modified
import android.text.TextUtils;
import android.util.Log;
import android.view.View; // modified: v4.4.0 View.generateViewId()
import android.view.ViewGroup; // modified
import android.widget.EditText; // modified
import android.widget.FrameLayout; // modified
import android.widget.LinearLayout; // modified: v4.4.0 自定义单选弹窗容器
import android.widget.RadioButton; // modified: v4.4.0 自定义单选弹窗选项
import android.widget.RadioGroup; // modified: v4.4.0 自定义单选弹窗选项组
import android.widget.ScrollView; // modified: v4.4.0 选项多时可滚动
import android.widget.TextView; // modified: v4.4.0 弹窗提示文字
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog; // modified: v4.4.0 MaterialAlertDialogBuilder 的产物类型
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate; // modified
import androidx.preference.EditTextPreference; // modified
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder; // modified

import java.util.Set;

/** 设置页：展示当前生效的配置、命中记录、权限体检与版本号。 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SmsSettingsActivity";
    /** modified: 「自定义…」在 arrays.xml 里的哨兵值，不会被写入配置。 */
    private static final String CUSTOM_VALUE = "custom"; // modified

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        EdgeToEdgeHelper.apply(this, R.id.settings_container); // modified: 边到边适配
        // 【独立复审修正】原实现无条件 replace。旋转屏幕 / 深色模式切换等配置变更时
        // Activity 会重建，FragmentManager 已经自动恢复了旧的 PreferenceFragment，
        // 这里再 replace 一个新的，会出现「两份 Preference 叠加 / 刚改的值被旧实例覆盖」。
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new AppPreferenceFragment())
                    .commit();
        }
    }

    /** 设置页内容由该 Fragment 承载。 */
    public static class AppPreferenceFragment extends PreferenceFragmentCompat
            implements Preference.OnPreferenceChangeListener {

        /**
         * modified: v4.5.0 当前显示的对话框。
         *
         * <p>自绘弹窗用的是 Activity 的 Context，一旦页面在弹窗显示期间销毁（旋转屏幕、
         * 主题切换重建、返回键退出），窗口就会泄漏，logcat 里是 WindowLeaked，
         * 部分 ROM 上还会直接抛 BadTokenException。这里统一登记，在 onDestroyView 里关掉。
         */
        private AlertDialog activeDialog;

        /**
         * modified: v4.5.1 试响播放器。
         *
         * <p>v4.5.0 是让 {@code SMSForegroundService} 播的，实测部分机型「点了确认什么反应都没有」：
         * 试响只有 8 秒、用户就盯着设置页，却要走 mediaPlayback 前台服务，
         * 于是卡在 Android 12+ 的启动限制 / 14+ 的类型校验 / 通知权限上，失败还被 catch 吞掉。
         * 现在改成页面内直接播，失败原因能立刻回显给用户。
         */
        private AlertPreviewPlayer previewPlayer;

        /** 【新增 v4.6.0】两个系统选择器的 requestCode，互不冲突。 */
        private static final int REQ_RINGTONE_PICKER = 1001; // 新增
        private static final int REQ_AUDIO_FILE = 1002; // 新增

        /** modified: v4.5.0 统一登记并显示对话框，替代散落的 .show()。 */
        private void showTracked(AlertDialog dialog) {
            activeDialog = dialog;
            dialog.setOnDismissListener(d -> {
                if (activeDialog == dialog) {
                    activeDialog = null;
                }
            });
            dialog.show();
        }

        private void dismissActiveDialog() { // modified: v4.5.0
            AlertDialog dialog = activeDialog;
            activeDialog = null;
            if (dialog != null && dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Exception e) {
                    Log.w(TAG, "dismiss active dialog failed", e);
                }
            }
        }

        /** modified: v4.5.1 停止试响并释放播放器，页面离开/销毁时调用。 */
        private void stopPreview() {
            AlertPreviewPlayer preview = previewPlayer;
            previewPlayer = null;
            if (preview != null) {
                try {
                    preview.stop();
                } catch (Exception e) {
                    Log.w(TAG, "stop preview failed", e);
                }
            }
        }

        /**
         * modified: v4.5.1 执行一次试响，并把真实结果告诉用户。
         *
         * <p>这里刻意不用「请求已发出就算成功」的写法：播放器会回一个明确的结果码，
         * 失败时能说清是「都没开」「闹钟音量为 0」还是「铃声资源加载失败」，
         * 而不是让用户面对一个毫无反馈的按钮。
         */
        private void startPreview(Context context) {
            if (previewPlayer == null) {
                previewPlayer = new AlertPreviewPlayer();
            }
            int result = previewPlayer.play(context);
            int message;
            switch (result) {
                case AlertPreviewPlayer.RESULT_OK:
                    message = R.string.preview_alert_started;
                    break;
                case AlertPreviewPlayer.RESULT_ALREADY_PLAYING:
                    message = R.string.preview_alert_playing;
                    break;
                case AlertPreviewPlayer.RESULT_NOTHING_ENABLED:
                    message = R.string.preview_alert_silent;
                    break;
                case AlertPreviewPlayer.RESULT_ALARM_VOLUME_ZERO:
                    message = R.string.preview_alert_volume_zero;
                    break;
                default:
                    message = R.string.preview_alert_failed;
                    break;
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }

        // ==================== 【新增 v4.6.0】自定义提醒铃声 ====================

        /**
         * 「提醒铃声」入口。刻意用普通 Preference + 自绘选项列表，不用框架对话框：
         * v4.4.0 已实测 AppCompat 的 AlertController 在 setMessage 与列表同时存在时
         * 只渲染提示文字、不渲染选项，所以说明文字单独放在「关于自定义铃声」里。
         */
        private void bindCustomRingtonePreference() { // 新增 v4.6.0
            Preference pref = findPreference(getString(R.string.custom_ringtone_key)); // 新增
            if (pref == null) { // 新增
                return; // 新增
            } // 新增
            pref.setOnPreferenceClickListener(preference -> { // 新增
                Context context = getContext(); // 新增
                if (context == null) { // 新增
                    return true; // 新增
                } // 新增
                CharSequence[] items = new CharSequence[]{ // 新增：固定 5 项，which 恒定
                        getString(R.string.custom_ringtone_choice_system), // 新增
                        getString(R.string.custom_ringtone_choice_library), // 新增
                        getString(R.string.custom_ringtone_choice_file), // 新增
                        getString(R.string.custom_ringtone_choice_preview), // 新增
                        getString(R.string.custom_ringtone_choice_note) // 新增
                }; // 新增
                showTracked(new MaterialAlertDialogBuilder(context) // 新增
                        .setTitle(R.string.custom_ringtone_title) // 新增
                        .setItems(items, (dialog, which) -> // 新增
                                handleRingtoneChoice(context, which)) // 新增
                        .setNegativeButton(android.R.string.cancel, null) // 新增
                        .create()); // 新增
                return true; // 新增
            }); // 新增
        } // 新增

        /** 分发 5 个固定选项。说明对话框延后一帧，避免与正在 dismiss 的选项框叠加。 */
        private void handleRingtoneChoice(Context context, int which) { // 新增 v4.6.0
            switch (which) { // 新增
                case 0: // 新增：跟随系统
                    applySystemDefault(context); // 新增
                    break; // 新增
                case 1: // 新增：系统铃声库
                    openRingtonePicker(context); // 新增
                    break; // 新增
                case 2: // 新增：手机文件
                    openAudioFilePicker(context); // 新增
                    break; // 新增
                case 3: // 新增：试听当前
                    if (previewPlayer != null && previewPlayer.isPlaying()) { // 新增
                        Toast.makeText(context, R.string.preview_alert_playing, // 新增
                                Toast.LENGTH_LONG).show(); // 新增
                    } else { // 新增
                        startPreview(context); // 新增
                    } // 新增
                    break; // 新增
                case 4: // 新增：说明
                    new Handler(Looper.getMainLooper()).post(() -> { // 新增
                        Context live = getContext(); // 新增
                        if (live == null || !isAdded()) { // 新增
                            return; // 新增
                        } // 新增
                        showTracked(new MaterialAlertDialogBuilder(live) // 新增
                                .setTitle(R.string.custom_ringtone_choice_note) // 新增
                                .setMessage(R.string.custom_ringtone_note) // 新增
                                .setPositiveButton(android.R.string.ok, null) // 新增
                                .create()); // 新增
                    }); // 新增
                    break; // 新增
                default: // 新增
                    break; // 新增
            } // 新增
        } // 新增

        /** 系统铃声库选择器。选「默认」时返回的是系统引用 Uri，按恢复默认处理。 */
        private void openRingtonePicker(Context context) { // 新增 v4.6.0
            Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER); // 新增
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, // 新增
                    RingtoneManager.TYPE_ALARM); // 新增
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true); // 新增
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false); // 新增
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, // 新增
                    getString(R.string.custom_ringtone_title)); // 新增
            Uri existing = AlertRingtone.systemUri(context); // 新增
            if (existing != null) { // 新增
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing); // 新增
            } // 新增
            try { // 新增
                startActivityForResult(intent, REQ_RINGTONE_PICKER); // 新增
            } catch (Exception e) { // 新增
                Log.w(TAG, "ringtone picker unavailable", e); // 新增
                Toast.makeText(context, R.string.custom_ringtone_picker_missing, // 新增
                        Toast.LENGTH_LONG).show(); // 新增
            } // 新增
        } // 新增

        /**
         * 手机文件选择器。用「任意 MIME 类型」+ EXTRA_MIME_TYPES 而不是 audio 类型：
         * 部分国产 ROM 把 .ogg 报成 application/ogg，audio/* 会把用户刚裁好的
         * 卡农 Ogg 直接过滤掉。非音频文件由导入时的 MediaPlayer 校验挡住。
         */
        private void openAudioFilePicker(Context context) { // 新增 v4.6.0
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); // 新增
            intent.addCategory(Intent.CATEGORY_OPENABLE); // 新增
            intent.setType("*/*"); // 新增
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ // 新增
                    "audio/*", "application/ogg", "audio/ogg", // 新增
                    "application/octet-stream" // 新增
            }); // 新增
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // 新增
            try { // 新增
                startActivityForResult(intent, REQ_AUDIO_FILE); // 新增
            } catch (Exception e) { // 新增
                Log.w(TAG, "document picker unavailable", e); // 新增
                Toast.makeText(context, R.string.custom_ringtone_file_picker_missing, // 新增
                        Toast.LENGTH_LONG).show(); // 新增
            } // 新增
        } // 新增

        @Override
        public void onActivityResult(int requestCode, int resultCode, // 新增 v4.6.0
                                     @Nullable Intent data) { // 新增
            super.onActivityResult(requestCode, resultCode, data); // 新增
            Context context = getContext(); // 新增
            if (context == null || resultCode != Activity.RESULT_OK) { // 新增
                return; // 新增
            } // 新增
            if (requestCode == REQ_RINGTONE_PICKER) { // 新增
                Uri picked = data == null ? null // 新增
                        : data.getParcelableExtra( // 新增
                                RingtoneManager.EXTRA_RINGTONE_PICKED_URI); // 新增
                if (CustomRingtone.isSystemDefaultUri(context, picked)) { // 新增
                    applySystemDefault(context); // 新增
                } else { // 新增
                    importRingtoneAsync(context, picked); // 新增
                } // 新增
            } else if (requestCode == REQ_AUDIO_FILE && data != null) { // 新增
                Uri picked = data.getData(); // 新增
                if (picked != null) { // 新增
                    importRingtoneAsync(context, picked); // 新增
                } // 新增
            } // 新增
        } // 新增

        /** 删除自定义副本，恢复跟随系统闹钟音。 */
        private void applySystemDefault(Context context) { // 新增 v4.6.0
            stopPreview(); // 新增：正在试听旧铃声时换源必须先停
            CustomRingtone.clearCustom(context); // 新增
            refreshActiveConfiguration(); // 新增
            Toast.makeText(context, R.string.custom_ringtone_restored, // 新增
                    Toast.LENGTH_SHORT).show(); // 新增
            Context app = context.getApplicationContext(); // 新增
            SmsTrace.add(app, "铃声来源切换", "恢复跟随系统闹钟音"); // 新增
        } // 新增

        /**
         * 复制音频 + 解码校验放到后台线程：30 MB 的流式复制和同步 prepare 都不能压在
         * UI 线程上。失败只提示并保留原铃声，绝不把半截文件当成新铃声。
         */
        private void importRingtoneAsync(Context context, Uri uri) { // 新增 v4.6.0
            if (uri == null) { // 新增
                Toast.makeText(context, context.getString( // 新增
                        R.string.custom_ringtone_failed, // 新增
                        context.getString(R.string.custom_ringtone_error_read)), // 新增
                        Toast.LENGTH_LONG).show(); // 新增
                return; // 新增
            } // 新增
            final Context app = context.getApplicationContext(); // 新增
            stopPreview(); // 新增
            final String label = CustomRingtone.displayName(app, uri); // 新增
            Toast.makeText(context, R.string.custom_ringtone_importing, // 新增
                    Toast.LENGTH_SHORT).show(); // 新增
            new Thread(() -> { // 新增
                final int code = CustomRingtone.importFromUri(app, uri, label); // 新增
                new Handler(Looper.getMainLooper()).post(() -> { // 新增
                    Context live = getContext(); // 新增
                    if (live == null || !isAdded()) { // 新增
                        return; // 新增
                    } // 新增
                    if (code == CustomRingtone.ERR_NONE) { // 新增
                        refreshActiveConfiguration(); // 新增
                        Toast.makeText(live, R.string.custom_ringtone_saved, // 新增
                                Toast.LENGTH_LONG).show(); // 新增
                        SmsTrace.add(app, "铃声来源切换", "自定义：" + label); // 新增
                    } else { // 新增
                        Toast.makeText(live, live.getString( // 新增
                                R.string.custom_ringtone_failed, // 新增
                                ringtoneErrorText(live, code)), // 新增
                                Toast.LENGTH_LONG).show(); // 新增
                    } // 新增
                }); // 新增
            }, "bellgram-import-tone").start(); // 新增
        } // 新增

        /** 错误码 -> 用户能看懂的一句话。 */
        private String ringtoneErrorText(Context context, int code) { // 新增 v4.6.0
            switch (code) { // 新增
                case CustomRingtone.ERR_TOO_LARGE: // 新增
                    return context.getString(R.string.custom_ringtone_error_large); // 新增
                case CustomRingtone.ERR_UNPLAYABLE: // 新增
                    return context.getString(R.string.custom_ringtone_error_unplayable); // 新增
                case CustomRingtone.ERR_WRITE: // 新增
                    return context.getString(R.string.custom_ringtone_error_write); // 新增
                case CustomRingtone.ERR_READ: // 新增
                default: // 新增
                    return context.getString(R.string.custom_ringtone_error_read); // 新增
            } // 新增
        } // 新增

        @Override
        public void onStop() { // modified: v4.5.1 页面不可见就停，避免切走后还在响
            stopPreview();
            super.onStop();
        }

        @Override
        public void onDestroyView() { // modified: v4.5.0 防止窗口泄漏
            stopPreview(); // modified: v4.5.1
            dismissActiveDialog();
            super.onDestroyView();
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) { // modified: AndroidX 标准回调
            setPreferencesFromResource(R.xml.app_preferences, rootKey);
            bindListSummaries();
            bindInteractivePreferences(); // modified
            bindListPreferenceDialogs(); // modified: v4.4.0 显式接管全部下拉设置项
            bindCustomRingtonePreference(); // 新增 v4.6.0：自定义提醒铃声入口

            Preference autoSuggestionPref = findPreference(getString(R.string.auto_suggestion_key));
            if (autoSuggestionPref != null) {
                autoSuggestionPref.setOnPreferenceChangeListener(this);
                syncAutoSuggestionState(autoSuggestionPref);
            }

            // modified: v4.4.0 清空命中记录同样属于不可逆操作，补二次确认，与清除配置保持一致
            Preference clearLogPref = findPreference(getString(R.string.clear_hit_log_key));
            if (clearLogPref != null) {
                clearLogPref.setOnPreferenceClickListener(preference -> {
                    final Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    showTracked(new MaterialAlertDialogBuilder(context) // modified: v4.5.0 统一登记
                            .setTitle(R.string.clear_hit_log)
                            .setMessage(R.string.clear_hit_log_confirm)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.confirm_positive, (dialog, which) -> {
                                HitLog.clear(context);
                                Toast.makeText(context, R.string.hit_log_cleared,
                                        Toast.LENGTH_SHORT).show();
                                refreshActiveConfiguration();
                            })
                            .create()); // modified
                    return true;
                });
            }

            // modified: v4.4.0 【新增】清除配置入口。首页那枚一按就清空的按钮已删除，
            // 这里必须先确认才真正删除；清除范围与旧按钮完全一致（号码/关键词/联系人姓名）。
            Preference clearConfigPref = findPreference(getString(R.string.clear_config_key));
            if (clearConfigPref != null) {
                clearConfigPref.setOnPreferenceClickListener(preference -> {
                    final Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    showTracked(new MaterialAlertDialogBuilder(context) // modified: v4.5.0 统一登记
                            .setTitle(R.string.clear_config_title)
                            .setMessage(R.string.clear_config_confirm)
                            .setIcon(R.drawable.ic_block_24dp)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.clear_config_positive, (dialog, which) -> {
                                AppConfig.get(context.getApplicationContext()).clear();
                                Toast.makeText(context, R.string.clear_config_done,
                                        Toast.LENGTH_SHORT).show();
                                refreshActiveConfiguration();
                            })
                            .create()); // modified
                    return true;
                });
            }

            // modified: v4.5.0 【新增】试响一次。改完音量 / 时长 / 渐强后立刻能听到效果，
            // 不用等一条真实短信来验证——这是「设置到底生效没有」最直接的答案。
            // modified: v4.5.1 改为页面内直接播放（AlertPreviewPlayer），
            // 不再经 SMSForegroundService —— 见该类注释里对「各机型无声」根因的说明。
            Preference previewPref = findPreference(getString(R.string.preview_alert_key));
            if (previewPref != null) {
                previewPref.setOnPreferenceClickListener(preference -> {
                    final Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    if (previewPlayer != null && previewPlayer.isPlaying()) {
                        Toast.makeText(context, R.string.preview_alert_playing,
                                Toast.LENGTH_LONG).show();
                        return true;
                    }
                    showTracked(new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.preview_alert_title)
                            .setMessage(R.string.preview_alert_confirm)
                            .setIcon(R.drawable.ic_bell_24)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.preview_alert_action, (dialog, which) ->
                                    startPreview(context))
                            .create());
                    return true;
                });
            }

            // 权限体检入口。点击后列出未就绪项，点条目直达对应系统设置页
            Preference checkPref = findPreference(getString(R.string.permission_check_key));
            if (checkPref != null) {
                checkPref.setOnPreferenceClickListener(preference -> {
                    if (getActivity() != null) {
                        PermissionChecker.showDialog(getActivity());
                    }
                    return true;
                });
            }

            // 【新增 v4.5.6】接收诊断：把「收到短信却没反应」变成可查看的逐步骤记录。
            // 对话框由 SmsTrace 统一提供，首页状态条点开的也是同一个。
            Preference tracePref = findPreference(getString(R.string.sms_trace_key));
            if (tracePref != null) {
                tracePref.setOnPreferenceClickListener(preference -> {
                    Context context = getContext();
                    if (context != null) {
                        SmsTrace.showDialog(context);
                    }
                    return true;
                });
            }

            // 【新增 v4.5.7】用户报「收到短信没反应」时直觉会去点「最近命中记录」，
            // 但那一页空态只写「暂无命中记录」，不给任何线索，导致排查卡在原地。
            // 改成点开同一个诊断对话框（接收轨迹 + 当前规则权限快照 + 命中记录），
            // 无论从哪个入口进来都能看到全貌。
            Preference hitLogPref = findPreference(getString(R.string.hit_log_key));
            if (hitLogPref != null) {
                hitLogPref.setOnPreferenceClickListener(preference -> {
                    Context context = getContext();
                    if (context != null) {
                        SmsTrace.showDialog(context);
                    }
                    return true;
                });
            }

            // 【新增 v4.5.6】模拟一条命中短信：不依赖真实短信、SIM 卡与运营商，
            // 直接调用 AlertDispatcher，用来二分「接收/匹配端」和「通知/响铃端」。
            // 测试提醒响了 → 问题在短信权限或规则；没响 → 问题在通知权限/系统拦截。
            Preference testSmsPref = findPreference(getString(R.string.send_test_sms_key));
            if (testSmsPref != null) {
                testSmsPref.setOnPreferenceClickListener(preference -> {
                    final Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    showTracked(new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.send_test_sms_title)
                            .setMessage(R.string.send_test_sms_confirm)
                            .setIcon(R.drawable.ic_bell_24)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.send_test_sms_action, (dialog, which) -> {
                                Context app = context.getApplicationContext();
                                SmsTrace.add(app, "手动模拟短信",
                                        "从设置页直接调用提醒调度，不经过短信广播");
                                AlertDispatcher.onSmsMatched(app,
                                        context.getString(R.string.send_test_sms_sender),
                                        context.getString(R.string.send_test_sms_keyword),
                                        "",
                                        context.getString(R.string.send_test_sms_body));
                                Toast.makeText(context, R.string.send_test_sms_sent,
                                        Toast.LENGTH_LONG).show();
                            })
                            .create());
                    return true;
                });
            }
            refreshActiveConfiguration();
        }

        /**
         * modified: v4.4.0 显式接管全部 ListPreference 的点击，保证「点了一定有可选项」。
         *
         * <p>原实现完全依赖 AndroidX 的 ListPreferenceDialogFragmentCompat：框架用
         * {@code builder.setMessage(dialogMessage)} + {@code setSingleChoiceItems(entries)}
         * 拼出对话框，而 AppCompat 的 AlertController 在两者同时存在时只渲染提示文字、
         * 不渲染选项列表，用户报告的 5 个数值项正是这种表现。这里改为应用自己绘制选项视图：
         * 提示单独一行、选项逐条列出、点选即写入。
         *
         * <p>modified: v4.5.0 补上关键一步。仅靠 {@code setOnPreferenceClickListener} 返回 true
         * 并**不能**阻止框架弹窗：androidx.preference 1.2.1 的
         * {@code Preference.performClick()} 先调用 {@code onClick()}
         * （{@code DialogPreference} 里已经 {@code showDialog()} 提交了 DialogFragment），
         * 之后才回调监听器。因此 XML 里这 8 项改成了 {@link SelfDrawnListPreference}，
         * 它的 {@code onClick()} 是空实现，框架不再提交任何弹窗，只剩下面这一个。
         *
         * <p>值仍通过 {@link ListPreference#setValue(String)} 写入默认 SharedPreferences，
         * 键名 / 默认值 / 摘要 provider / AppConfig 读取链路一律不变；主题切换与
         * 「自定义…」继续走既有的 OnPreferenceChangeListener 分支（见 applyListValue）。
         */
        private void bindListPreferenceDialogs() { // modified
            int[] keys = { // modified
                    R.string.theme_mode_key, // modified
                    R.string.alarm_volume_key, // modified
                    R.string.ring_duration_key, // modified
                    R.string.volume_key_action_key, // modified
                    R.string.repeat_interval_key, // modified
                    R.string.max_repeat_key, // modified
                    R.string.snooze_minutes_key, // modified
                    R.string.quiet_hours_key // modified
            }; // modified
            for (int keyRes : keys) { // modified
                Preference preference = findPreference(getString(keyRes)); // modified
                if (preference instanceof ListPreference) { // modified
                    preference.setOnPreferenceClickListener(p -> { // modified
                        showChoiceDialog((ListPreference) p); // modified
                        return true; // modified: 拦截框架默认对话框，改弹我们自己的
                    }); // modified
                } // modified
            } // modified
        } // modified

        /** modified: v4.4.0 自绘单选弹窗：提示文字 + 选项列表，点选项即保存并关闭。 */
        private void showChoiceDialog(ListPreference pref) { // modified
            Context context = getContext(); // modified
            if (context == null) { // modified
                return; // modified
            } // modified
            CharSequence[] entries = pref.getEntries(); // modified
            CharSequence[] values = pref.getEntryValues(); // modified
            if (entries == null || values == null // modified
                    || entries.length == 0 || values.length == 0) { // modified
                Log.w(TAG, "no entries for preference: " + pref.getKey()); // modified
                Toast.makeText(context, R.string.choice_dialog_no_options, // modified
                        Toast.LENGTH_SHORT).show(); // modified
                return; // modified
            } // modified
            final int count = Math.min(entries.length, values.length); // modified
            final int currentIndex = pref.findIndexOfValue(pref.getValue()); // modified
            final int pad = (int) (20 * context.getResources().getDisplayMetrics().density); // modified

            LinearLayout root = new LinearLayout(context); // modified
            root.setOrientation(LinearLayout.VERTICAL); // modified
            root.setPadding(pad, pad / 2, pad, 0); // modified

            // 提示文字单独一行，不再与选项列表争抢同一块内容区
            CharSequence hint = pref.getDialogMessage(); // modified
            if (!TextUtils.isEmpty(hint)) { // modified
                TextView tip = new TextView(context); // modified
                tip.setText(hint); // modified
                tip.setTextSize(13); // modified
                tip.setPadding(0, 0, 0, pad / 2); // modified
                root.addView(tip, new LinearLayout.LayoutParams( // modified
                        ViewGroup.LayoutParams.MATCH_PARENT, // modified
                        ViewGroup.LayoutParams.WRAP_CONTENT)); // modified
            } // modified

            RadioGroup group = new RadioGroup(context); // modified
            final AlertDialog[] holder = new AlertDialog[1]; // modified
            for (int i = 0; i < count; i++) { // modified
                RadioButton item = new RadioButton(context); // modified
                item.setId(View.generateViewId()); // modified
                item.setText(entries[i]); // modified
                item.setTextSize(15); // modified
                item.setPadding(0, pad / 3, 0, pad / 3); // modified
                final String value = values[i] == null ? "" : values[i].toString(); // modified
                item.setOnClickListener(v -> { // modified
                    if (holder[0] != null) { // modified
                        holder[0].dismiss(); // modified
                    } // modified
                    if (CUSTOM_VALUE.equals(value) && isNumericPreference(pref.getKey())) { // modified
                        showCustomValueDialog(pref, pref.getKey()); // modified
                    } else { // modified
                        applyListValue(pref, value); // modified
                    } // modified
                }); // modified
                group.addView(item, new RadioGroup.LayoutParams( // modified
                        ViewGroup.LayoutParams.MATCH_PARENT, // modified
                        ViewGroup.LayoutParams.WRAP_CONTENT)); // modified
                if (i == currentIndex) { // modified
                    item.setChecked(true); // modified
                } // modified
            } // modified
            ScrollView scroll = new ScrollView(context); // modified
            scroll.addView(group, new FrameLayout.LayoutParams( // modified
                    ViewGroup.LayoutParams.MATCH_PARENT, // modified
                    ViewGroup.LayoutParams.WRAP_CONTENT)); // modified
            root.addView(scroll, new LinearLayout.LayoutParams( // modified
                    ViewGroup.LayoutParams.MATCH_PARENT, // modified
                    ViewGroup.LayoutParams.WRAP_CONTENT)); // modified

            CharSequence title = pref.getDialogTitle(); // modified
            if (TextUtils.isEmpty(title)) { // modified
                title = pref.getTitle(); // modified
            } // modified
            AlertDialog dialog = new MaterialAlertDialogBuilder(context) // modified
                    .setTitle(title) // modified
                    .setView(root) // modified
                    .setNegativeButton(android.R.string.cancel, null) // modified
                    .create(); // modified
            holder[0] = dialog; // modified
            showTracked(dialog); // modified: v4.5.0 统一登记，页面销毁时自动关掉
        } // modified

        /**
         * modified: v4.4.0 统一写入口，等价于框架默认的 callChangeListener + setValue。
         * listener 返回 false 表示它已自行处理（主题重建 / 自定义数值输入），这里不再写值。
         */
        private void applyListValue(ListPreference pref, String value) { // modified
            Preference.OnPreferenceChangeListener listener = // modified
                    pref.getOnPreferenceChangeListener(); // modified
            if (listener != null && !listener.onPreferenceChange(pref, value)) { // modified
                return; // modified
            } // modified
            pref.setValue(value); // modified: persistString + 触发摘要 provider 刷新
            Log.d(TAG, "preference saved: " + pref.getKey() + " = " + value); // modified
        } // modified

        /** modified: 需要拦截变更的项（主题切换 + 三个可手工输入的数值项）。 */
        private void bindInteractivePreferences() { // modified
            int[] keys = { // modified
                    R.string.theme_mode_key, // modified
                    R.string.alert_enabled_key, // modified: 关闭时连已排队的闹钟一起取消
                    R.string.ring_duration_key, // modified
                    R.string.repeat_interval_key, // modified
                    R.string.max_repeat_key // modified
            }; // modified
            for (int keyRes : keys) { // modified
                Preference preference = findPreference(getString(keyRes)); // modified
                if (preference != null) { // modified
                    preference.setOnPreferenceChangeListener(this); // modified
                } // modified
            } // modified
        } // modified

        /**
         * androidx.preference 的现代写法：用官方 SummaryProvider 显示 ListPreference 当前值。
         * 旧版 {@code android:summary="%s"} 的框架行为在 AndroidX 上不保证保留，
         * 不显式设置会出现「选了 5 分钟但摘要一片空白」。
         */
        private void bindListSummaries() { // modified: 新增
            // modified: 普通下拉项（含 v4.3 新增项）用官方 provider
            int[] simpleKeys = { // modified
                    R.string.theme_mode_key, // modified
                    R.string.alarm_volume_key, // modified
                    R.string.snooze_minutes_key, // modified
                    R.string.volume_key_action_key, // modified
                    R.string.quiet_hours_key // modified
            }; // modified
            for (int keyRes : simpleKeys) { // modified
                Preference preference = findPreference(getString(keyRes)); // modified
                if (preference instanceof ListPreference) { // modified
                    ((ListPreference) preference).setSummaryProvider( // modified
                            ListPreference.SimpleSummaryProvider.getInstance()); // modified
                } // modified
            } // modified
            // modified: 三个数值项允许写入任意整数，官方 provider 遇到不在 entryValues
            // 里的值会返回 null 导致摘要空白，因此用自定义 provider 显示「自定义：N 单位」。
            bindNumericSummary(R.string.ring_duration_key, R.string.custom_duration_summary); // modified
            bindNumericSummary(R.string.repeat_interval_key, R.string.custom_interval_summary); // modified
            bindNumericSummary(R.string.max_repeat_key, R.string.custom_repeat_summary); // modified
            bindExclusionSummary(); // modified
        }

        /** modified: 排除关键词摘要 —— 未设置给提示，已设置显示当前词表。 */
        private void bindExclusionSummary() { // modified
            EditTextPreference pref = findPreference(getString(R.string.exclude_keywords_key)); // modified
            if (pref == null) { // modified
                return; // modified
            } // modified
            Preference.SummaryProvider<EditTextPreference> provider = p -> { // modified
                String value = p.getText(); // modified
                if (TextUtils.isEmpty(value)) { // modified
                    return getString(R.string.exclude_keywords_empty); // modified
                } // modified
                return getString(R.string.exclude_keywords_value, value); // modified
            }; // modified
            pref.setSummaryProvider(provider); // modified
        } // modified

        /** modified: 预设值显示原条目，自定义值显示带单位的摘要。 */
        private void bindNumericSummary(int keyRes, int customSummaryRes) { // modified
            ListPreference pref = findPreference(getString(keyRes)); // modified
            if (pref == null) { // modified
                return; // modified
            } // modified
            Preference.SummaryProvider<ListPreference> provider = p -> { // modified
                String value = p.getValue(); // modified
                CharSequence[] values = p.getEntryValues(); // modified
                CharSequence[] entries = p.getEntries(); // modified
                if (values != null && entries != null) { // modified
                    for (int i = 0; i < values.length && i < entries.length; i++) { // modified
                        if (values[i] != null && values[i].toString().equals(value)) { // modified
                            return entries[i]; // modified
                        } // modified
                    } // modified
                } // modified
                try { // modified
                    return getString(customSummaryRes, Integer.parseInt(value)); // modified
                } catch (Exception e) { // modified
                    return ""; // modified
                } // modified
            }; // modified
            pref.setSummaryProvider(provider); // modified
        } // modified

        @Override
        public void onResume() {
            super.onResume();
            // 【v4.2】自动联想开关由 AppConfig（"Settings" 文件）托管，
            // 每次可见时用真实配置校准一次，避免与界面显示分叉。
            Preference autoPref = findPreference(getString(R.string.auto_suggestion_key)); // modified
            if (autoPref != null) { // modified
                autoPref.setOnPreferenceChangeListener(null); // 避免 setChecked 回写 // modified
                syncAutoSuggestionState(autoPref); // modified
                autoPref.setOnPreferenceChangeListener(this); // modified
            }
            refreshActiveConfiguration(); // 从系统设置页返回时重新检测权限状态
        }

        private void syncAutoSuggestionState(Preference preference) {
            if (!(preference instanceof SwitchPreferenceCompat) || getActivity() == null) {
                return;
            }
            AppConfig config = AppConfig.get(getActivity());
            ((SwitchPreferenceCompat) preference).setChecked(!config.isAutoSuggestionDisabled());
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey(); // modified
            // modified: 主题切换。先 setValue 落盘，再交给 AppCompatDelegate 重建 Activity，
            // 避免 recreate 与框架 persistString 之间的时序竞态导致刚选的值丢失。
            if (getString(R.string.theme_mode_key).equals(key)) { // modified
                if (preference instanceof ListPreference) { // modified
                    String mode = String.valueOf(newValue); // modified
                    ((ListPreference) preference).setValue(mode); // modified
                    AppCompatDelegate.setDefaultNightMode(ThemeHelper.nightModeFor(mode)); // modified
                    // modified: 部分 AppCompat 版本只更新 configuration 不重建当前页面，显式补一次
                    if (getActivity() != null) { // modified
                        getActivity().recreate(); // modified
                    } // modified
                    Log.d(TAG, "theme mode changed: " + mode); // modified
                } // modified
                return false; // modified
            } // modified

            // modified: 「自定义…」只是打开输入框的入口，绝不能把哨兵值写进配置。
            if (isNumericPreference(key) && CUSTOM_VALUE.equals(String.valueOf(newValue))) { // modified
                if (preference instanceof ListPreference) { // modified
                    showCustomValueDialog((ListPreference) preference, key); // modified
                } // modified
                return false; // modified
            } // modified

            // modified: 其余字符串型下拉项交给框架正常写入；布尔项仍走自动联想开关逻辑。
            if (!(newValue instanceof Boolean)) { // modified
                Log.d(TAG, "list preference changed: " + key + " = " + newValue); // modified
                return true; // modified
            } // modified
            // modified: 【v4.3 独立复审】总开关原只在 SMSReceiver 入口生效：用户关掉后，
            // 已由 setAlarmClock 排好的下一轮仍会照常响，「一键暂停」名不副实。
            // AlertDispatcher.stop() 幂等且完全不读本配置，可直接在这里调用，
            // 不存在与框架 persistBoolean 的时序竞态。
            if (getString(R.string.alert_enabled_key).equals(key) // modified
                    && !((Boolean) newValue)) { // modified
                Context context = getContext(); // modified
                if (context != null) { // modified
                    AlertDispatcher.stop(context.getApplicationContext()); // modified
                } // modified
                Log.d(TAG, "master switch off, cancelled pending alert session"); // modified
                return true; // modified
            } // modified
            boolean enabled = (Boolean) newValue;
            // modified: 【v4.3 独立复审】原实现把「任何 Boolean 偏好」都当成自动联想开关。
            // 该开关存在独立的 "Settings" preference 文件（persistent=false），
            // 一旦以后给别的 SwitchPreference 挂上同一个 listener，就会被静默写进它的键，
            // 表现为「改了提醒开关，自动联想却跟着变了」。这里按 key 精确限定。
            if (preference instanceof SwitchPreferenceCompat // modified
                    && getString(R.string.auto_suggestion_key).equals(key) // modified
                    && getActivity() != null) { // modified
                AppConfig.get(getActivity()).setAutoSuggestionDisabled(!enabled);
                Log.d(TAG, "auto suggestion enabled: " + enabled);
            }
            return true;
        }

        /** modified: 三个支持手工输入的数值项。 */
        private boolean isNumericPreference(String key) { // modified
            return getString(R.string.ring_duration_key).equals(key) // modified
                    || getString(R.string.repeat_interval_key).equals(key) // modified
                    || getString(R.string.max_repeat_key).equals(key); // modified
        } // modified

        /** modified: 弹出数字输入框，范围与 AppConfig 的钳制上限完全一致。 */
        private void showCustomValueDialog(ListPreference pref, String key) { // modified
            Context context = getContext(); // modified
            if (context == null) { // modified
                return; // modified
            } // modified
            final int max; // modified
            final int messageRes; // modified
            if (getString(R.string.ring_duration_key).equals(key)) { // modified
                max = 120; // modified
                messageRes = R.string.custom_duration_message; // modified
            } else if (getString(R.string.repeat_interval_key).equals(key)) { // modified
                max = 60; // modified
                messageRes = R.string.custom_interval_message; // modified
            } else { // modified
                max = 30; // modified
                messageRes = R.string.custom_repeat_message; // modified
            } // modified
            final int min = 0; // modified

            EditText input = new EditText(context); // modified
            input.setInputType(InputType.TYPE_CLASS_NUMBER); // modified
            String current = pref.getValue(); // modified
            if (current != null && !CUSTOM_VALUE.equals(current)) { // modified
                input.setText(current); // modified
                input.setSelection(input.getText().length()); // modified
            } // modified
            int pad = (int) (20 * context.getResources().getDisplayMetrics().density); // modified
            FrameLayout holder = new FrameLayout(context); // modified
            holder.setPadding(pad, pad / 2, pad, 0); // modified
            holder.addView(input, new FrameLayout.LayoutParams( // modified
                    ViewGroup.LayoutParams.MATCH_PARENT, // modified
                    ViewGroup.LayoutParams.WRAP_CONTENT)); // modified

            // modified: v4.5.0 手动接管「确定」按钮。
            // 原实现用 setPositiveButton(..., listener)：Android 的按钮回调**先关闭对话框**
            // 再执行 listener，于是输入非法时只弹一个 Toast，对话框已经消失、原值也没改，
            // 用户得重新点开设置项再输一次。改成 setPositiveButton(null) + 自设 OnClickListener，
            // 非法输入就地标红并保留对话框。
            final AlertDialog dialog = new MaterialAlertDialogBuilder(context) // modified
                    .setTitle(R.string.custom_value_dialog_title) // modified
                    .setMessage(messageRes) // modified
                    .setView(holder) // modified
                    .setNegativeButton(android.R.string.cancel, null) // modified
                    .setPositiveButton(android.R.string.ok, null) // modified: 由 OnShowListener 接管
                    .create(); // modified

            dialog.setOnShowListener(ignored -> { // modified
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> { // modified
                    int number; // modified
                    try { // modified
                        number = Integer.parseInt(input.getText().toString().trim()); // modified
                    } catch (NumberFormatException e) { // modified
                        number = -1; // modified
                    } // modified
                    if (number < min || number > max) { // modified
                        String message = context.getString( // modified
                                R.string.custom_value_invalid, min, max); // modified
                        input.setError(message); // modified: 就地提示，不关闭对话框
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); // modified
                        return; // modified
                    } // modified
                    input.setError(null); // modified
                    // setValue 会 persistString 并触发自定义摘要 provider
                    pref.setValue(String.valueOf(number)); // modified
                    Log.d(TAG, "custom value saved: " + key + " = " + number); // modified
                    dialog.dismiss(); // modified: 只有成功才关闭
                }); // modified
            }); // modified
            showTracked(dialog); // modified: v4.5.0 统一登记
        } // modified

        /** 刷新「当前配置」「命中记录」「权限体检」三项摘要。 */
        private void refreshActiveConfiguration() {
            Context context = getContext();
            if (context == null) {
                return;
            }
            Preference configPref = findPreference(getString(R.string.sms_config_key));
            if (configPref != null) {
                bindActiveConfiguration(configPref, context);
            }
            Preference hitLogPref = findPreference(getString(R.string.hit_log_key));
            if (hitLogPref != null) {
                String log = HitLog.dump(context);
                String lastSkip = SmsTrace.lastSkip(context);
                // 【新增 v4.5.7】有「最近未触发原因」时优先显示它；
                // 只写「暂无命中记录」对用户判断根因毫无帮助。
                hitLogPref.setSummary(!TextUtils.isEmpty(lastSkip)
                        ? context.getString(R.string.sms_trace_last_skip, lastSkip)
                        : (TextUtils.isEmpty(log)
                                ? context.getString(R.string.hit_log_empty) : log));
            }
            // 权限体检摘要实时反映缺了几项
            Preference checkPref = findPreference(getString(R.string.permission_check_key));
            if (checkPref != null) {
                int missing = PermissionChecker.missingCount(context);
                checkPref.setSummary(missing == 0
                        ? context.getString(R.string.permission_check_ready)
                        : context.getString(R.string.permission_check_pending, missing));
            }
            // 【新增 v4.5.6】接收诊断摘要直接显示最近一次「未触发」原因，
            // 用户进设置页不用点开就能看到卡在哪一步。
            Preference tracePref = findPreference(getString(R.string.sms_trace_key));
            if (tracePref != null) {
                String lastSkip = SmsTrace.lastSkip(context);
                tracePref.setSummary(TextUtils.isEmpty(lastSkip)
                        ? context.getString(R.string.sms_trace_summary)
                        : context.getString(R.string.sms_trace_last_skip, lastSkip));
            }
            // 【新增 v4.6.0】摘要直接显示当前生效的铃声，用户不用点进去猜
            Preference tonePref = findPreference(getString(R.string.custom_ringtone_key)); // 新增
            if (tonePref != null) { // 新增
                tonePref.setSummary(CustomRingtone.hasCustom(context) // 新增
                        ? context.getString(R.string.custom_ringtone_summary_custom, // 新增
                                CustomRingtone.label(context)) // 新增
                        : context.getString(R.string.custom_ringtone_summary_default)); // 新增
            } // 新增
        }

        private void bindActiveConfiguration(Preference preference, Context context) {
            AppConfig config = AppConfig.get(context);
            String number = config.getContact();
            String personName = config.getContactName();
            Set<String> keywords = config.getKeywords();

            StringBuilder summary = new StringBuilder(context.getString(R.string.config_summary_header));
            summary.append(number.isEmpty() ? context.getString(R.string.not_configured) : number);
            summary.append(context.getString(R.string.contact_name_label));
            summary.append(personName.isEmpty() ? context.getString(R.string.not_configured) : personName);
            summary.append(context.getString(R.string.keywords_label));
            summary.append(keywords.isEmpty()
                    ? context.getString(R.string.not_configured)
                    : TextUtils.join(" ", keywords));
            preference.setSummary(summary.toString());
        }
    }
}
