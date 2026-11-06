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

import android.content.Context; // modified
import android.os.Bundle;
import android.text.InputType; // modified
import android.text.TextUtils;
import android.util.Log;
import android.view.ViewGroup; // modified
import android.widget.EditText; // modified
import android.widget.FrameLayout; // modified
import android.widget.Toast;

import androidx.annotation.Nullable;
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

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) { // modified: AndroidX 标准回调
            setPreferencesFromResource(R.xml.app_preferences, rootKey);
            bindListSummaries();
            bindInteractivePreferences(); // modified

            Preference autoSuggestionPref = findPreference(getString(R.string.auto_suggestion_key));
            if (autoSuggestionPref != null) {
                autoSuggestionPref.setOnPreferenceChangeListener(this);
                syncAutoSuggestionState(autoSuggestionPref);
            }

            Preference clearLogPref = findPreference(getString(R.string.clear_hit_log_key));
            if (clearLogPref != null) {
                clearLogPref.setOnPreferenceClickListener(preference -> {
                    Context context = getContext();
                    if (context == null) {
                        return true;
                    }
                    HitLog.clear(context);
                    Toast.makeText(context, R.string.hit_log_cleared, Toast.LENGTH_SHORT).show();
                    refreshActiveConfiguration();
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
            refreshActiveConfiguration();
        }

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

            new MaterialAlertDialogBuilder(context) // modified
                    .setTitle(R.string.custom_value_dialog_title) // modified
                    .setMessage(messageRes) // modified
                    .setView(holder) // modified
                    .setNegativeButton(android.R.string.cancel, null) // modified
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> { // modified
                        int number; // modified
                        try { // modified
                            number = Integer.parseInt(input.getText().toString().trim()); // modified
                        } catch (NumberFormatException e) { // modified
                            number = -1; // modified
                        } // modified
                        if (number < min || number > max) { // modified
                            Toast.makeText(context, context.getString( // modified
                                    R.string.custom_value_invalid, min, max), // modified
                                    Toast.LENGTH_SHORT).show(); // modified
                            return; // modified
                        } // modified
                        // setValue 会 persistString 并触发自定义摘要 provider
                        pref.setValue(String.valueOf(number)); // modified
                        Log.d(TAG, "custom value saved: " + key + " = " + number); // modified
                    }) // modified
                    .show(); // modified
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
                hitLogPref.setSummary(TextUtils.isEmpty(log)
                        ? context.getString(R.string.hit_log_empty) : log);
            }
            // 权限体检摘要实时反映缺了几项
            Preference checkPref = findPreference(getString(R.string.permission_check_key));
            if (checkPref != null) {
                int missing = PermissionChecker.missingCount(context);
                checkPref.setSummary(missing == 0
                        ? context.getString(R.string.permission_check_ready)
                        : context.getString(R.string.permission_check_pending, missing));
            }
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
