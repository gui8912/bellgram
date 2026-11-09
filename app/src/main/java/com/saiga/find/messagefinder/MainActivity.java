package com.saiga.find.messagefinder;

import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList; // 新增 v4.5.5：关键词标签的删除图标着色
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.transition.Slide;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FilterQueryProvider;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip; // 新增 v4.5.5
import com.google.android.material.chip.ChipGroup; // 新增 v4.5.5
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList; // 新增 v4.5.5
import java.util.Collections; // 新增 v4.5.5
import java.util.List;
import java.util.Set;

import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;

/**
 * 主界面：录入“号码（可选）+ 关键词（必填）”，保存为触发优先提醒的规则。
 *
 * <p>本次修改的要点（均以 // modified 标注）：
 * <ul>
 *   <li>onCreate 在引导页分支直接 return，不再继续执行已 finish 界面的初始化；</li>
 *   <li>号码长度限制由“最多 10 位”改为 5-15 位，修复中国大陆 11 位手机号无法录入的问题；</li>
 *   <li>关键词解析交给 {@link KeywordMatcher}，兼容全角逗号/空格；</li>
 *   <li>通讯录游标使用 try-with-resources 关闭，修复 Cursor 泄漏；</li>
 *   <li>权限回调时从界面重新读取输入，避免字段为 null 导致 NPE；</li>
 *   <li>Handler / 延迟弹窗做生命周期校验，避免界面销毁后回调崩溃。</li>
 *   <li>modified: v4.5.2 首屏删掉简介、红米四步提示与底部装饰图标后，
 *       原「点底部清单打开权限体检」的入口迁到 status_text，功能不丢。</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener, TextWatcher {

    private static final String TAG = "Sms Receiver";

    /** modified: 【v4.2】引导页跳过来时携带，用于抑制「刚结束引导就抢焦点弹权限框」。 */
    public static final String EXTRA_FROM_ONBOARDING = "from_onboarding_v4"; // modified

    private static final String SMS_PERMISSION = Manifest.permission.RECEIVE_SMS;
    private static final String READ_CONTACTS_PERMISSION = Manifest.permission.READ_CONTACTS;

    /** 打开系统通讯录选择器的请求码。 */
    private static final int RESULT_PICK_CONTACT = 125;
    /** 短信权限请求码。 */
    private static final int PERM_SMS_REQUEST = 123;
    /** 联系人权限请求码。 */
    private static final int PERM_CONTACTS_REQUEST = 124;
    /** modified: Android 13+ 通知运行时权限请求码。 */
    private static final int PERM_NOTIFICATIONS_REQUEST = 126;
    /** 自动联想授权提示框延迟出现的毫秒数（让界面先稳定下来）。 */
    private static final long CONTACT_DIALOG_DELAY_MS = 1500L;
    /** 跳转系统设置页前的延迟毫秒数（等待 Snackbar 收起）。 */
    private static final long NAVIGATE_SETTINGS_DELAY_MS = 250L;

    private Button trigButton;
    // modified: v4.4.0 【删除】原 clearButton 字段。去向：「清除配置」入口已迁入应用设置页，
    // 由 SettingsActivity 弹确认框后调用 AppConfig.clear()，首页不再持有该按钮。
    private CoordinatorLayout userMessageLayout;
    private TextInputLayout contactNumberLayout;
    /** [新增] v4.5.5 关键词输入框容器，其 endIcon 是「关键词库」入口。 */
    private TextInputLayout messageInputLayout; // 新增

    private SimpleCursorAdapter contactAdapter;
    private Snackbar smsPermSnackbar;

    private AppConfig appConfig;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 自动联想是否已被用户关闭（用于决定“是否弹通讯录选择器”）。 */
    private boolean isAutoSuggestionDisabled = false;
    /** modified: 【v4.2】原为 static，且唯一置 true 的分支会立刻 finish 返回，恒为 false。
     *  改为实例字段并由引导页 Intent 显式传入，注释宣称的保护才真正生效。 */
    private boolean fromOnboarding = false; // modified
    /** 用户是否已经从通讯录选过联系人（避免反复弹“要不要选择联系人”）。 */
    private boolean isContactPicked = false;
    /** 本次会话是否已经问过“要不要从通讯录选择”，避免每次聚焦都弹一次。 */
    private boolean contactPickerAsked = false; // modified: 修复反复弹框打扰用户
    /** 界面是否来自配置变更重建，用于避免重复弹权限说明框。 */
    private boolean restoredFromState = false;
    /**
     * modified: v4.5.0 本次编辑中从通讯录选到的姓名，等点「启动」时再落盘。
     *
     * <p>原实现是「选完立刻保存姓名」，于是用户选了张三、回头把号码改成了别人的，
     * 号码已经更新、`contact_name` 还留着张三 —— 通知与告警页会显示「张三 (13800000000)」，
     * 而实际发短信的是另一个人。现在姓名跟着「这次保存的号码」一起提交，
     * 号码变了又没重新选人，就明确清掉旧姓名（宁可少显示，也不显示错的）。
     */
    private String pendingContactName = ""; // modified
    /**
     * [新增] v4.5.5 最近一次由 {@code syncKeywordInputWithConfig()} 回填到首页输入框的文本。
     * 只用来在「配置被清空」时判断框里的内容是不是我们填的、用户没动过；
     * 用户自己输入的内容永远不会被自动清掉。
     */
    private String restoredKeywordText = ""; // 新增

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先切主题再 super.onCreate：保证窗口背景不是启动图
        setTheme(R.style.MyAppTheme);
        super.onCreate(savedInstanceState);
        restoredFromState = savedInstanceState != null;
        fromOnboarding = getIntent() != null // modified
                && getIntent().getBooleanExtra(EXTRA_FROM_ONBOARDING, false); // modified: 读取来源
        if (savedInstanceState != null) { // modified
            // modified: 【v4.2】旋转重建后这三个 UI 标志原本归零，
            // 会再次弹「是否要选择联系人」对话框、重复询问用户。
            isContactPicked = savedInstanceState.getBoolean("state_contact_picked", false); // modified
            contactPickerAsked = savedInstanceState.getBoolean("state_picker_asked", false); // modified
            isAutoSuggestionDisabled = savedInstanceState.getBoolean("state_suggestion_disabled", false); // modified
        }
        // modified: 进程被强杀后，上次提醒可能把系统闹钟音量永久改成最大，先还原
        AlarmVolumeGuard.restoreIfStale(this); // modified

        // 结构控制：首次安装时进入引导页，引导完成后不再出现
        if (isOnboardingEnabled()) {
            Log.d(TAG, "onboarding enabled, launch it");
            startActivity(new Intent(this, Onboarding.class));
            finish();
            return; // modified: 提前返回，避免继续初始化一个已 finish 的界面
        }

        // 从引导页进入主界面时播放一次滑入动画
        slideAnimation();
        setContentView(R.layout.activity_main);
        // modified: targetSdk 35+ 强制 edge-to-edge，内容必须避开系统栏与挖孔
        EdgeToEdgeHelper.apply(this, R.id.user_message_layout);
        // modified: targetSdk 33+ 的 POST_NOTIFICATIONS 属于运行时权限，不申请则通知完全发不出去
        ensureNotificationPermission();
        // 新增 v4.6.3：用户打开应用时把常驻守护拉起来。
        // 这是最可靠的一次启动时机——此刻应用在前台，Android 12+ 的后台启动限制不适用。
        // 若通知权限被拒，服务内部会自己收尾，不会留下半死的前台服务。
        GuardService.start(this);
        bindStatusPermissionEntry(); // modified: v4.5.2 原底部清单已删，点击能力迁到状态条

        appConfig = AppConfig.get(this); // modified: 用实例替代原来的 static SharedPreferences

        trigButton = findViewById(R.id.trigger);
        userMessageLayout = findViewById(R.id.user_message_layout);

        // 监听号码输入：格式不合法时禁用“启动”按钮并给出错误提示
        contactNumberLayout = findViewById(R.id.contact_input_layout);
        EditText contactEditText = contactNumberLayout == null ? null : contactNumberLayout.getEditText();
        if (contactEditText != null) {
            contactEditText.addTextChangedListener(this);
        } else {
            Log.w(TAG, "contact input layout without edit text, validation disabled");
        }

        final AutoCompleteTextView contactTextView = findViewById(R.id.contact_value);
        if (contactTextView == null) { // modified: v4.5.0 布局被裁剪/换肤替换控件时不再 NPE
            Log.w(TAG, "contact input missing, contact picker and auto suggestion disabled"); // modified
        } else { // modified
            bindContactInput(contactTextView); // modified: 抽出方法，缩短 onCreate
        } // modified

        trigButton.setOnClickListener(this);
        // 二次开发新增：主界面直接提供「提醒方式」入口
        View reminderSettings = findViewById(R.id.reminder_settings);
        if (reminderSettings != null) {
            reminderSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    try {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    } catch (Exception e) {
                        Log.e(TAG, "open settings failed", e);
                    }
                }
            });
        }
        // 二次开发新增：「规则演练」入口（借鉴短信铃铛）
        View practiceButton = findViewById(R.id.practice_rule);
        if (practiceButton != null) {
            practiceButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showPracticeDialog();
                }
            });
        }
        bindKeywordLibraryEntry(); // 新增 v4.5.5：关键词框右侧图标 -> 全量编辑对话框
        // 状态条统一在 onResume 中刷新（onCreate 之后必然执行，避免重复调用）
    }

    /**
     * modified: v4.5.0 抽出。号码输入框的焦点提示与通讯录联想都挂在这里，
     * 原先是 onCreate 里近 50 行内联代码，且没有对 findViewById 结果判空。
     *
     * @param contactTextView 号码输入框，调用方已判空
     */
    private void bindContactInput(@NonNull AutoCompleteTextView contactTextView) { // modified
        // 焦点变化时，按状态决定是否提示“从通讯录选择联系人”
        contactTextView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (!hasFocus) {
                    return;
                }
                boolean shouldAsk = (isAutoSuggestionDisabled || appConfig.isAutoSuggestionDisabled())
                        && !isContactPicked && !contactPickerAsked; // modified: 会话内只问一次
                if (!shouldAsk) {
                    return;
                }
                contactPickerAsked = true;
                showContactPickerDialog();
            }
        });

        // 自动联想开启时才初始化游标适配器（默认关闭）
        if (!appConfig.isAutoSuggestionDisabled()) {
            setupContactAdapter();
            if (contactAdapter != null) {
                contactTextView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        TextView contactTitle = view.findViewById(R.id.contact_title);
                        if (contactTitle != null) {
                            // modified: v4.5.0 先记在本次编辑里，点「启动」时随号码一起落盘
                            pendingContactName = contactTitle.getText().toString(); // modified
                            Log.d(TAG, "selected phone contact " + pendingContactName); // modified
                        }
                    }
                });
                contactAdapter.setCursorToStringConverter(new SimpleCursorAdapter.CursorToStringConverter() {
                    @Override
                    public CharSequence convertToString(Cursor cursor) {
                        if (cursor == null) {
                            return "";
                        }
                        int index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                        if (index < 0) {
                            return "";
                        }
                        // 去掉空格、横线与国际区号，统一成国内习惯的 11 位号码
                        return PhoneNumbers.canonical(cursor.getString(index)); // modified: 替换原 spliceNumber
                    }
                });
                contactTextView.setAdapter(contactAdapter);
            }
            if (checkReadContactPermission()) {
                setFilter();
            }
        }
    } // modified

    /**
     * Android 13(API 33)+ 通知权限必须在运行时申请。
     *
     * <p>targetSdk 36 下如果漏掉这一步，{@code NotificationManagerCompat.notify()} 会抛
     * SecurityException，结果就是「短信匹配上了，但一条通知都没有」——这是升级 targetSdk
     * 后最常见的静默失败。同时提前建好通知渠道，避免第一条短信到达时才创建渠道造成延迟。
     */
    private void ensureNotificationPermission() { // modified: 新增
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "request POST_NOTIFICATIONS permission");
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERM_NOTIFICATIONS_REQUEST);
            }
        }
        NotificationHelper.ensureChannels(this);
    }

    /**
     * modified: v4.5.2 【修改】原 bindPermissionChecklist() 绑定的是底部
     * 「红米 / HyperOS 必做四步」TextView（原 permission_tip 控件），该控件已按
     * 首屏精简要求删除，故改绑状态条 R.id.status_text。
     *
     * <p>不直接删掉这个方法，是为了保住「在首页点一下就能做权限体检」这条最短路径：
     * 状态条本来就会显示「还有 N 项权限未授予」警示，点它进体检最自然。
     * 完整的权限体检入口仍在设置页 permission_check_key，此处只是快捷方式。
     * updateStatusUi() 只改文字/颜色/背景，不会覆盖这里注册的点击监听。
     *
     * <p>【删除】原先的 tip.setClickable(true)：setOnClickListener 内部已会把
     * View 置为 clickable，这行是冗余操作。
     */
    private void bindStatusPermissionEntry() { // modified: v4.5.2 由 bindPermissionChecklist 改名
        View status = findViewById(R.id.status_text);
        if (status != null) {
            status.setOnClickListener(view -> {
                // 【新增 v4.5.6】最近有短信被跳过时，点状态条直接看接收诊断
                // （「收到关键词短信却没反应」时这就是答案所在）；否则保持权限体检入口。
                if (!TextUtils.isEmpty(SmsTrace.lastSkip(MainActivity.this))) {
                    // modified: v4.6.4 清空后立即刷新状态条，否则「最近未触发」那行仍挂着，
                    // 用户回到首页会认为清空没生效
                    SmsTrace.showDialog(MainActivity.this, this::updateStatusUi);
                } else {
                    PermissionChecker.showDialog(MainActivity.this);
                }
            });
        }
    }

    /**
     * 刷新顶部状态条：让用户一眼看出「现在到底有没有在监听」。
     *
     * <p>原界面只有按钮文案（“启动！”/“已启动！”）暗示状态，重启应用后无法判断
     * 之前保存的规则是否仍然生效——这是这类“后台静默工作”的应用最常见的困惑点。
     */
    private void updateStatusUi() { // modified: 新增
        TextView statusText = findViewById(R.id.status_text);
        if (statusText == null || appConfig == null) {
            return;
        }
        Set<String> keywords = appConfig.getKeywords();
        String modeSummary = getString(R.string.status_mode, buildReminderModeSummary()); // modified: 追加提醒策略
        if (keywords.isEmpty()) {
            statusText.setText(getString(R.string.status_idle) + "\n" + modeSummary);
            statusText.setTextColor(getColor(R.color.status_idle_text));
            statusText.setBackgroundResource(R.drawable.bg_status_idle);
        } else {
            statusText.setText(getString(R.string.status_listening, TextUtils.join(" / ", keywords))
                    + "\n" + modeSummary);
            statusText.setTextColor(getColor(R.color.status_active_text));
            statusText.setBackgroundResource(R.drawable.bg_status_active);
        }
        // modified: 权限不齐全时，状态条直接给出警示，避免用户以为「已经在监听」却收不到提醒
        int missing = PermissionChecker.missingCount(this);
        // 【新增 v4.5.6】「最近一条短信为什么没触发」比权限条数更具体，优先占同一行显示，
        // 刻意不新增一行，保持首屏「一页不用下拉」。点状态条可展开完整诊断。
        String lastSkip = SmsTrace.lastSkip(this);
        if (!TextUtils.isEmpty(lastSkip)) {
            String brief = lastSkip.length() > 46 ? lastSkip.substring(0, 46) + "…" : lastSkip;
            statusText.append("\n" + getString(R.string.status_last_skip, brief));
            statusText.setTextColor(getColor(R.color.alert_accent));
        } else if (missing > 0) {
            statusText.append("\n" + getString(R.string.status_permission_warning, missing));
            statusText.setTextColor(getColor(R.color.alert_accent));
        }
    }

    /**
     * 把“响铃 / 震动 / 重复策略”拼成一行人话，直接显示在状态条里。
     *
     * <p>为什么要显示：重复提醒是本轮新增的核心能力，但它的配置藏在设置页里。
     * 如果主界面看不到当前策略，用户会怀疑“到底会不会 5 分钟再响一次”，
     * 只能反复进设置页确认。
     *
     * @return 例如“闹钟铃声 · 震动 · 每 5 分钟重复（最多 4 次）”
     */
    private String buildReminderModeSummary() { // modified: 新增
        StringBuilder summary = new StringBuilder();
        // 先讲“什么算命中”，再讲“怎么提醒”，与设置页的顺序一致
        summary.append(getString(appConfig.isMatchAllEnabled()
                ? R.string.mode_match_all : R.string.mode_match_any));
        summary.append(" · ");
        summary.append(getString(appConfig.isRingEnabled()
                ? R.string.mode_ring_on : R.string.mode_ring_off));
        summary.append(" · ");
        summary.append(getString(appConfig.isVibrateEnabled()
                ? R.string.mode_vibrate_on : R.string.mode_vibrate_off));

        int durationSec = appConfig.getRingDurationSec();
        int intervalMin = appConfig.getRepeatIntervalMin();
        int maxRepeat = appConfig.getMaxRepeatCount();
        if (durationSec > 0 && intervalMin > 0) {
            summary.append(" · ");
            // “最多重复 N 次”= 首次 1 轮 + 重复 N 轮，展示时换算成总轮次更直观
            summary.append(maxRepeat <= 0
                    ? getString(R.string.mode_repeat_unlimited, intervalMin)
                    : getString(R.string.mode_repeat_limited, intervalMin, maxRepeat + 1));
        }

        // 夜间静音时段：让用户知道“半夜不会响”这件事是生效的
        String quietRange = QuietHours.describe(appConfig.getQuietStartHour(), appConfig.getQuietEndHour());
        if (!quietRange.isEmpty()) {
            summary.append(" · ").append(getString(R.string.mode_quiet, quietRange));
        }
        return summary.toString();
    }

    // ==================== 规则演练（借鉴短信铃铛） ====================

    /**
     * 打开「规则演练」弹窗：粘一段示例短信，立刻看到它会不会被拦住。
     *
     * <p>为什么这个功能值得做：本应用是“静默工作”的，规则写错时**没有任何反馈**——
     * 关键词写宽了天天误报，写严了永远不响，而用户只有在真的错过重要短信之后才会发现。
     * 演练把这份不确定性提前暴露出来，代价只有一个输入框。
     */
    private void showPracticeDialog() { // modified: 新增
        if (isFinishing() || isDestroyed()) {
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_practice, null, false);
        final TextView resultView = content.findViewById(R.id.practice_result);
        TextInputLayout inputLayout = content.findViewById(R.id.practice_input_layout);
        final EditText input = inputLayout == null ? null : inputLayout.getEditText();
        if (input != null) {
            // 边输边判：用户不用先找按钮，粘完就知道结果
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                    // 无需处理
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int before, int count) {
                    // 无需处理
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    evaluatePractice(editable == null ? "" : editable.toString(), resultView);
                }
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.practice_title)
                .setView(content)
                // 传入 null 后手动接管点击，避免“点一下弹窗就关掉、看不到结果”
                .setPositiveButton(R.string.practice_run, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        View runButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (runButton != null) {
            runButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    evaluatePractice(input == null ? "" : input.getText().toString(), resultView);
                }
            });
        }
        evaluatePractice("", resultView);
    }

    /**
     * 用当前规则对示例短信做一次匹配，并把结果写回弹窗。
     *
     * @param sample     示例短信内容
     * @param resultView 结果展示控件
     */
    private void evaluatePractice(String sample, TextView resultView) { // modified: 新增
        if (resultView == null || appConfig == null) {
            return;
        }
        Set<String> keywords = appConfig.getKeywords();
        if (keywords.isEmpty()) {
            resultView.setText(R.string.practice_no_keyword);
            resultView.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        if (TextUtils.isEmpty(sample) || TextUtils.isEmpty(sample.trim())) {
            resultView.setText(R.string.practice_no_input);
            resultView.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        // 与 SMSReceiver 使用同一个匹配入口、同一套模式，演练结果才可信
        String hit = KeywordMatcher.findMatch(keywords, sample, appConfig.isMatchAllEnabled());
        if (hit != null) {
            resultView.setText(getString(R.string.practice_hit, hit));
            resultView.setTextColor(getColor(R.color.status_active_text));
        } else {
            resultView.setText(R.string.practice_miss);
            resultView.setTextColor(getColor(R.color.alert_accent));
        }
    }

    /**
     * @return Onboarding 组件是否仍处于启用状态（即用户还没走完引导流程）
     */
    private boolean isOnboardingEnabled() { // modified: 抽出方法，逻辑更直观
        // modified: 【v4.2】持久化标记为主判定：setComponentEnabledSetting 在某些
        // 受管设备 / ROM 上会失败并只打日志，原实现因此每次冷启动都重新进引导页。
        if (AppConfig.get(this).isOnboardingDone()) { // modified
            return false; // modified
        }
        int enabled = getPackageManager()
                .getComponentEnabledSetting(new ComponentName(this, Onboarding.class));
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) { // modified: v4.2 新增
        super.onSaveInstanceState(outState); // modified
        outState.putBoolean("state_contact_picked", isContactPicked); // modified
        outState.putBoolean("state_picker_asked", contactPickerAsked); // modified
        outState.putBoolean("state_suggestion_disabled", isAutoSuggestionDisabled); // modified
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.settings_menu) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 读取系统通讯录选择器返回的联系人号码。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || requestCode != RESULT_PICK_CONTACT) {
            return;
        }
        if (data == null || data.getData() == null) { // modified: 空 data 保护
            Log.w(TAG, "contact picker returned no data");
            return;
        }
        Uri pickedItem = data.getData();
        // try-with-resources：保证 Cursor 一定被关闭（原实现泄漏游标）
        try (Cursor cursor = getContentResolver().query(pickedItem, null, null, null, null)) { // modified: 修复 Cursor 泄漏
            if (cursor == null || !cursor.moveToFirst()) {
                Log.w(TAG, "contact picker returned empty cursor");
                return;
            }
            int nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            if (numberIndex < 0) {
                Log.w(TAG, "contact cursor without number column");
                return;
            }
            String contactName = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
            String phoneNumber = cursor.getString(numberIndex);
            EditText contactView = findEditText(R.id.contact_value);
            // modified: 【独立复审修正】原实现未判空
            if (contactView != null) {
                contactView.setText(PhoneNumbers.canonical(phoneNumber)); // modified: 11 位国内号码
            } else {
                Log.w(TAG, "contact input view missing, picked number not applied");
            }
            // modified: v4.5.0 姓名先记在本次编辑里，等点「启动」再随号码一起落盘
            pendingContactName = contactName == null ? "" : contactName.trim(); // modified
            // 用户已成功选过联系人，后续不再打扰
            isContactPicked = true;
            contactPickerAsked = true;
        } catch (Exception e) {
            // 部分 ROM 的通讯录 Provider 会抛 SecurityException / IllegalArgumentException
            Log.e(TAG, "read picked contact failed", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume(); // modified: 【独立复审修正】原实现把 super.onResume() 放在最后，不符合生命周期约定
        // 让根布局先拿到焦点，避免横竖屏切换时输入法自动弹出
        View root = findViewById(R.id.ui_layout);
        if (root != null) {
            root.requestFocus();
        }
        syncKeywordInputWithConfig(); // 新增 v4.5.5：把当前全部关键词回填进输入框
        updateStatusUi(); // modified: 从设置页返回时同步状态
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.trigger) {
            handleTrigger();
        }
    }

    /** “启动”按钮：校验 -> 请求权限 -> 保存配置。 */
    private void handleTrigger() { // modified: 拆分职责，避免 onClick 过长
        String contactRaw = getTextOf(R.id.contact_value);
        String messageRaw = getTextOf(R.id.message_value);

        clearSoftKeyboardState();
        clearFocusOfForm();

        List<String> keywords = KeywordMatcher.parseKeywords(messageRaw); // modified: 全角分隔符兼容
        if (keywords.isEmpty()) {
            showSnackbar(R.string.keyword_empty);
            return;
        }
        if (!TextUtils.isEmpty(contactRaw) && !PhoneNumbers.isValidInput(contactRaw)) {
            // 原实现只在输入框里飘红，但仍允许保存，导致规则里存了脏号码
            showSnackbar(R.string.input_format_error); // modified: 非法号码直接拦下
            return;
        }
        if (!ensureSmsPermission()) {
            // 权限未授予：等 onRequestPermissionsResult 回调后再保存
            return;
        }
        saveConfig(contactRaw, keywords);
    }

    // modified: v4.4.0 【删除】原 handleClear()：清配置 + 清输入框 + 改按钮文案 + 刷新状态条。
    // 去向：清配置改由 SettingsActivity 的确认框回调执行（AppConfig.clear() + Toast +
    // refreshActiveConfiguration()）；首页状态条在 onResume() 里已有 updateStatusUi() 统一刷新。
    // 首页输入框里尚未保存的临时输入不再被连带清空，它本来就不属于「已保存的配置」。

    /** 读取输入框文本，永不返回 null。 */
    private String getTextOf(int viewId) { // modified: 统一空值处理
        EditText editText = findEditText(viewId);
        if (editText == null || editText.getText() == null) {
            return "";
        }
        return editText.getText().toString().trim();
    }

    /**
     * 安全地取输入框。
     *
     * <p>modified: 【独立复审修正】新增。原代码在 handleClear / clearFocusOfForm /
     * onActivityResult 里直接对 findViewById 的结果调用方法，没有判空——
     * 一旦布局被裁剪或控件被替换，就是一次 NPE 崩溃。
     */
    @Nullable
    private EditText findEditText(int viewId) {
        View view = findViewById(viewId);
        return view instanceof EditText ? (EditText) view : null;
    }

    private void clearFocusOfForm() {
        EditText contactView = findEditText(R.id.contact_value);
        EditText messageView = findEditText(R.id.message_value);
        if (contactView != null) {
            contactView.clearFocus();
        }
        if (messageView != null) {
            messageView.clearFocus();
        }
    }

    /**
     * 保存配置并给出 UI 反馈。
     *
     * <p>modified: v4.5.5 【新增】防静默覆盖：原本已有关键词，而这次输入与原有集合
     * 完全没有交集时，多半是「清空输入框后只填了一个新词」，按旧逻辑会一次性丢掉
     * 全部旧词。这里先弹「合并 / 替换」确认，由用户决定；有交集（正常增删改）直接保存。
     *
     * @param contact  号码，空串表示不限
     * @param keywords 关键词列表
     */
    private void saveConfig(String contact, List<String> keywords) {
        Set<String> previous = appConfig.getKeywords(); // 新增 v4.5.5
        if (!previous.isEmpty() && Collections.disjoint(previous, keywords)) { // 新增
            showKeywordOverwriteDialog(contact, keywords, previous); // 新增
            return; // 新增
        } // 新增
        applyConfigSave(contact, keywords); // 新增
    } // 新增

    /** 真正落盘与反馈；覆盖确认里用户已明确选过，这里不再重复判断。 */
    private void applyConfigSave(String contact, List<String> keywords) { // 新增 v4.5.5
        String previousContact = appConfig.getContact(); // modified: v4.5.0 用于判断号码是否变了
        appConfig.save(contact, keywords);
        // modified: v4.5.0 姓名与号码同步提交。
        // 原实现选完联系人立刻保存姓名，用户随后把号码改成别人的，姓名不会跟着变，
        // 通知里就会出现「张三 (13900000000)」这种错位信息。
        String normalizedContact = contact == null ? "" : contact.trim(); // modified
        if (!pendingContactName.isEmpty()) { // modified
            appConfig.saveContactName(pendingContactName); // modified
        } else if (!normalizedContact.equals(previousContact)) { // modified
            appConfig.saveContactName(""); // modified: 号码变了又没重新选人，清掉旧姓名
        } // modified
        pendingContactName = ""; // modified
        showSnackbar(R.string.config_saved);
        trigButton.setText(R.string.triggered);
        // modified: v4.4.0 【删除】原「清除按钮重设文案」调用（clear_button 字符串），
        // 首页已无清除按钮；保存成功后只需更新主按钮与状态条。
        updateStatusUi(); // modified
    } // 新增

    /** [新增] v4.5.5 绑定关键词框右侧图标：点一下打开全量编辑对话框。 */
    private void bindKeywordLibraryEntry() { // 新增
        messageInputLayout = findViewById(R.id.message_input_layout); // 新增
        if (messageInputLayout == null) { // 新增
            Log.w(TAG, "keyword input layout missing, library entry disabled"); // 新增
            return; // 新增
        } // 新增
        messageInputLayout.setEndIconOnClickListener(v -> showKeywordLibraryDialog()); // 新增
    } // 新增

    /**
     * [新增] v4.5.5 把已保存的关键词回填进首页输入框。
     *
     * <p>旧版输入框永远空白，但状态条却显示「监听中 · 关键词：A」，用户以为
     * 「我在加一个新词」，实际是全量替换 —— 这是本次 bug 的根因。
     * 只在输入框为空时回填，绝不覆盖用户正在编辑的内容；配置被清空时，
     * 只清掉「本逻辑填进去、用户没动过」的那段文本。
     */
    private void syncKeywordInputWithConfig() { // 新增
        if (appConfig == null) { // 新增
            return; // 新增
        } // 新增
        EditText view = findEditText(R.id.message_value); // 新增
        if (view == null) { // 新增
            return; // 新增
        } // 新增
        String current = view.getText().toString().trim(); // 新增
        Set<String> saved = appConfig.getKeywords(); // 新增
        if (saved.isEmpty()) { // 新增
            if (!restoredKeywordText.isEmpty() && current.equals(restoredKeywordText)) { // 新增
                view.setText(""); // 新增
            } // 新增
            restoredKeywordText = ""; // 新增
            return; // 新增
        } // 新增
        if (!current.isEmpty()) { // 新增
            return; // 用户正在编辑，绝不覆盖
        } // 新增
        String text = TextUtils.join(" ", saved); // 新增
        view.setText(text); // 新增
        restoredKeywordText = text; // 新增
    } // 新增

    /**
     * [新增] v4.5.5 「关键词库」对话框：一个可编辑文本框用空格分隔显示全部关键词，
     * 下方紧凑流式标签随文本实时刷新，每个标签的 × 点一下就把那个词从文本里摘掉。
     * 文本是唯一真相，标签只做可视化与快捷删除，不做逐行长列表。
     */
    private void showKeywordLibraryDialog() { // 新增
        if (isFinishing() || isDestroyed()) { // 新增
            return; // 新增
        } // 新增
        View content = getLayoutInflater().inflate(R.layout.dialog_keyword_library, null); // 新增
        final EditText editor = content.findViewById(R.id.keyword_editor); // 新增
        final ChipGroup chips = content.findViewById(R.id.keyword_chips); // 新增
        final TextView count = content.findViewById(R.id.keyword_count); // 新增
        String initial = TextUtils.join(" ", appConfig.getKeywords()); // 新增
        editor.setText(initial); // 新增
        editor.setSelection(initial.length()); // 新增
        editor.addTextChangedListener(new TextWatcher() { // 新增
            @Override // 新增
            public void beforeTextChanged(CharSequence s, int start, int c, int after) { } // 新增
            @Override // 新增
            public void onTextChanged(CharSequence s, int start, int before, int c) { } // 新增
            @Override // 新增
            public void afterTextChanged(Editable s) { // 新增
                refreshKeywordChips(editor, chips, count); // 新增
            } // 新增
        }); // 新增

        final AlertDialog dialog = new AlertDialog.Builder(this) // 新增
                .setTitle(R.string.keyword_library_title) // 新增
                .setView(content) // 新增
                .setNegativeButton(android.R.string.cancel, null) // 新增
                // 自接管确定按钮：校验不通过时不关闭对话框（沿用 v4.5.0 的写法）
                .setPositiveButton(R.string.keyword_library_save, null) // 新增
                .create(); // 新增
        dialog.setOnShowListener(d -> { // 新增
            Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE); // 新增
            if (ok == null) { // 新增
                return; // 新增
            } // 新增
            ok.setOnClickListener(v -> { // 新增
                List<String> parsed = KeywordMatcher.parseKeywords(editor.getText().toString()); // 新增
                if (parsed.isEmpty()) { // 新增
                    editor.setError(getString(R.string.keyword_empty)); // 新增
                    return; // 新增
                } // 新增
                appConfig.saveKeywords(parsed); // 新增：只写关键词键，不动号码
                String joined = TextUtils.join(" ", parsed); // 新增
                EditText main = findEditText(R.id.message_value); // 新增
                if (main != null) { // 新增
                    main.setText(joined); // 新增
                } // 新增
                restoredKeywordText = joined; // 新增
                updateStatusUi(); // 新增
                dialog.dismiss(); // 新增
                // 【修改 v4.5.6】原实现无条件把按钮改成「已启动！」，却绕过了
                // handleTrigger() 里的 RECEIVE_SMS 权限申请。用户只用关键词库保存时，
                // 系统不会把 SMS_RECEIVED 投递给未授权的应用，状态条却显示「监听中」——
                // 实测就是「收到关键词短信也完全没反应」。这里补权限校验与提示。
                boolean smsGranted = ContextCompat.checkSelfPermission(MainActivity.this,
                        SMS_PERMISSION) == PackageManager.PERMISSION_GRANTED; // 新增
                if (smsGranted) { // 新增
                    if (trigButton != null) { // 新增
                        trigButton.setText(R.string.triggered); // 新增
                    } // 新增
                    showSnackbar(getString(R.string.keyword_library_saved, parsed.size())); // 新增
                } else { // 新增
                    showSnackbar(R.string.keyword_library_need_sms_permission); // 新增
                    ensureSmsPermission(); // 新增：弹系统授权，回调会重新读取界面并保存
                } // 新增
            }); // 新增
        }); // 新增
        refreshKeywordChips(editor, chips, count); // 新增
        dialog.show(); // 新增
    } // 新增

    /** [新增] v4.5.5 按当前文本重建可删除标签与计数。 */
    private void refreshKeywordChips(EditText editor, ChipGroup group, TextView count) { // 新增
        if (editor == null || group == null || count == null) { // 新增
            return; // 新增
        } // 新增
        group.removeAllViews(); // 新增
        List<String> words = KeywordMatcher.parseKeywords(editor.getText().toString()); // 新增
        for (String word : words) { // 新增
            Chip chip = new Chip(group.getContext()); // 新增
            chip.setText(word); // 新增
            chip.setCloseIconResource(R.drawable.ic_keyword_remove); // 新增
            chip.setCloseIconTint(ColorStateList.valueOf( // 新增
                    ContextCompat.getColor(group.getContext(), R.color.alert_accent))); // 新增
            chip.setCloseIconVisible(true); // 新增
            chip.setOnCloseIconClickListener(v -> removeKeywordFromEditor(editor, word)); // 新增
            group.addView(chip); // 新增
        } // 新增
        count.setText(getString(R.string.keyword_library_count, words.size())); // 新增
    } // 新增

    /** [新增] v4.5.5 点标签 ×：从解析结果里摘掉这个词，再把文本重设回去。 */
    private void removeKeywordFromEditor(EditText editor, String target) { // 新增
        List<String> current = KeywordMatcher.parseKeywords(editor.getText().toString()); // 新增
        current.remove(target); // 新增
        String joined = TextUtils.join(" ", current); // 新增
        editor.setText(joined); // 新增
        editor.setSelection(joined.length()); // 新增
    } // 新增

    /** [新增] v4.5.5 首页整框重写且与旧词无交集时的「合并 / 替换」确认。 */
    private void showKeywordOverwriteDialog(String contact, List<String> keywords,
                                            Set<String> previous) { // 新增
        if (isFinishing() || isDestroyed()) { // 新增
            return; // 新增
        } // 新增
        String oldText = TextUtils.join(" ", previous); // 新增
        String newText = TextUtils.join(" ", keywords); // 新增
        new AlertDialog.Builder(this) // 新增
                .setTitle(R.string.keyword_overwrite_title) // 新增
                .setMessage(getString(R.string.keyword_overwrite_message, oldText, newText)) // 新增
                .setPositiveButton(R.string.keyword_overwrite_merge, (d, w) -> { // 新增
                    List<String> merged = new ArrayList<>(previous); // 新增
                    for (String k : keywords) { // 新增
                        if (merged.size() >= KeywordMatcher.MAX_KEYWORDS) { // 新增
                            break; // 新增
                        } // 新增
                        if (!merged.contains(k)) { // 新增
                            merged.add(k); // 新增
                        } // 新增
                    } // 新增
                    applyConfigSave(contact, merged); // 新增
                }) // 新增
                .setNegativeButton(R.string.keyword_overwrite_replace, // 新增
                        (d, w) -> applyConfigSave(contact, keywords)) // 新增
                .setNeutralButton(android.R.string.cancel, null) // 新增
                .show(); // 新增
    } // 新增

    private void showSnackbar(int stringResId) {
        showSnackbar(getString(stringResId)); // 新增 v4.5.5：委托给下面的通用重载
    }

    /**
     * [新增] v4.5.5 支持带参数的提示文案（例如「关键词已更新，共 N 条」）。
     * isFinishing 校验沿用原实现：已 finish 的 Activity 上 userMessageLayout
     * 仍可能非 null，不拦会继续执行后续保存逻辑。
     */
    private void showSnackbar(CharSequence message) { // 新增
        if (isFinishing() || isDestroyed()) { // 新增
            return; // 新增
        } // 新增
        if (userMessageLayout != null) { // 新增
            Snackbar.make(userMessageLayout, message, Snackbar.LENGTH_LONG).show(); // 新增
        } // 新增
    } // 新增

    /**
     * 检查短信接收权限；未授予时发起请求。
     *
     * @return true 表示已授予
     */
    private boolean ensureSmsPermission() {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), SMS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        Log.d(TAG, "request RECEIVE_SMS permission");
        requestPermissions(new String[]{SMS_PERMISSION}, PERM_SMS_REQUEST);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // modified: 【v4.2】点「启用」发起权限请求后立刻按返回键退出，
        // 授权结果仍会投递到已 finish 的实例；原实现会继续读取界面并写入配置。
        if (isFinishing() || isDestroyed()) { // modified
            Log.d(TAG, "permission result arrived after activity finished, ignored"); // modified
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERM_NOTIFICATIONS_REQUEST) { // modified: 新增通知权限回调
            if (!granted) {
                // modified: 【v4.2 独立复审】通知是主链路唯一唤醒入口，被永久拒绝后
                // 应用内原本没有任何恢复入口（短信/联系人权限分支都有「去设置」Action）。
                // 永久拒绝时给一条带设置页入口的长驻 Snackbar。
                if (shouldExplainDenied(Manifest.permission.POST_NOTIFICATIONS)) { // modified
                    showPermissionSnackbar(R.string.notif_permission_denied); // modified
                } else { // modified
                    showSnackbar(R.string.notif_permission_denied); // modified
                }
            }
            updateStatusUi();
            return;
        }

        if (requestCode == PERM_SMS_REQUEST) {
            if (granted) {
                // 权限授予后，重新读取界面输入再保存，避免使用可能已失效的成员变量
                String contactRaw = getTextOf(R.id.contact_value);
                // modified: 【v4.2 独立复审】handleTrigger() 里做了号码格式校验，
                // 但权限回调这条分支直接 saveConfig，绕过了校验。请求权限期间用户
                // 若改动输入框，脏号码会被原样写进配置，规则静默失效。
                if (!TextUtils.isEmpty(contactRaw) && !PhoneNumbers.isValidInput(contactRaw)) { // modified
                    showSnackbar(R.string.input_format_error); // modified
                    return;
                }
                List<String> keywords = KeywordMatcher.parseKeywords(getTextOf(R.id.message_value));
                if (keywords.isEmpty()) {
                    showSnackbar(R.string.keyword_empty);
                    return;
                }
                saveConfig(contactRaw, keywords); // modified: 从界面重新取值，修复 NPE 与脏数据
            } else if (shouldExplainDenied(SMS_PERMISSION)) {
                showPermissionSnackbar(R.string.enable_sms_perm);
            } else {
                showSnackbar(R.string.show_user_msg);
            }
            return;
        }

        if (requestCode == PERM_CONTACTS_REQUEST) {
            if (granted) {
                setFilter();
            } else if (shouldExplainDenied(READ_CONTACTS_PERMISSION)) {
                showPermissionSnackbar(R.string.enable_contact_perm);
            } else {
                showSnackbar(R.string.show_user_msg);
            }
        }
    }

    /**
     * 用户勾选“不再询问”并拒绝时返回 true，此时需要引导用户去系统设置页手动开启。
     */
    private boolean shouldExplainDenied(String permission) { // modified: 抽出重复的判断逻辑
        return !shouldShowRequestPermissionRationale(permission);
    }

    private void showPermissionSnackbar(int messageResId) {
        // modified: 【v4.2】同上，避免界面销毁后仍展示/持有 Snackbar
        if (isFinishing() || isDestroyed() || userMessageLayout == null) { // modified
            return;
        }
        smsPermSnackbar = Snackbar.make(userMessageLayout, messageResId, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.set_permissions, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        navigateToAppPermissions();
                    }
                })
                .setActionTextColor(getColor(R.color.primaryTextColor));
        smsPermSnackbar.show();
    }

    /** 一次性滑入动画（从引导页进入时使用）。 */
    private void slideAnimation() {
        Slide slide = new Slide();
        slide.setSlideEdge(Gravity.END);
        slide.setDuration(400);
        slide.setInterpolator(new DecelerateInterpolator());
        getWindow().setEnterTransition(slide);
        getWindow().setExitTransition(slide);
    }

    private void clearSoftKeyboardState() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focusedView = getCurrentFocus();
        if (inputMethodManager != null && focusedView != null) {
            inputMethodManager.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
    }

    /** 跳转本应用的系统权限设置页。 */
    private void navigateToAppPermissions() {
        if (smsPermSnackbar != null) {
            smsPermSnackbar.dismiss();
            smsPermSnackbar = null;
        }
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    // 打开后不进入返回栈，用户从设置页返回时直接回到桌面/本应用
                    intent.setFlags(FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_NO_HISTORY);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "open app settings failed", e);
                }
            }
        }, NAVIGATE_SETTINGS_DELAY_MS);
    }

    /** 创建“联系人 → 列表项”的游标适配器。 */
    private void setupContactAdapter() {
        String[] uiBindFrom = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        int[] uiBindTo = {R.id.contact_title, R.id.contact_number};
        contactAdapter = new SimpleCursorAdapter(this, R.layout.contacts_view, null,
                uiBindFrom, uiBindTo, 0);
    }

    /**
     * 检查联系人权限；未授予时延迟弹出“是否开启自动联想”的说明框。
     *
     * @return true 表示已授予
     */
    private boolean checkReadContactPermission() {
        if (ContextCompat.checkSelfPermission(getApplicationContext(), READ_CONTACTS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        // 引导页刚结束时不要立刻弹框抢焦点；配置变更重建时也不要重复弹
        if (fromOnboarding || restoredFromState) { // modified: 生命周期校验
            return false;
        }
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || isStateSaved()) {
                    Log.d(TAG, "activity not ready, skip permission dialog");
                    return;
                }
                try {
                    new MyDialogFragment().show(getSupportFragmentManager(), "AutoSuggestion");
                } catch (Exception e) {
                    // FragmentManager 已保存状态时会抛 IllegalStateException
                    Log.e(TAG, "show permission dialog failed", e);
                }
            }
        }, CONTACT_DIALOG_DELAY_MS);
        return false;
    }

    private boolean isStateSaved() {
        return getSupportFragmentManager().isStateSaved(); // modified: 防止 IllegalStateException
    }

    /** 由 {@link MyDialogFragment} 在用户点“设置”后调用。 */
    void requestContactPermission() {
        Log.d(TAG, "request READ_CONTACTS permission");
        requestPermissions(new String[]{READ_CONTACTS_PERMISSION}, PERM_CONTACTS_REQUEST);
    }

    /** 由 {@link MyDialogFragment} 调用：记录“不再提示自动联想”。 */
    void setUserConfig(boolean disabled) {
        appConfig.setAutoSuggestionDisabled(disabled);
        // modified: 【v4.2】「不再显示」按钮本意是不再打扰，但 autoSuggDisabled=true
        // 恰好是「每次聚焦号码框都弹『是否选择联系人』」的触发条件，语义反噬。
        // 同时把会话内「已问过」置位，让这次选择真正生效。
        contactPickerAsked = true; // modified
    }

    /** 由 {@link MyDialogFragment} 调用：记录用户本次选择（关闭说明框后改用通讯录选择器）。 */
    public void setPopup(boolean disabled) {
        isAutoSuggestionDisabled = disabled;
    }

    /** 附加到号码输入框的过滤查询（自动联想用）。 */
    private void setFilter() {
        if (contactAdapter == null) {
            // 未开启自动联想时 contactAdapter 为 null，此时收到权限回调不能继续
            Log.d(TAG, "contact adapter not ready, skip filter setup"); // modified: 修复 NPE
            return;
        }
        contactAdapter.setFilterQueryProvider(new FilterQueryProvider() {
            @Override
            public Cursor runQuery(CharSequence constraint) {
                if (constraint == null || constraint.toString().trim().isEmpty()) {
                    return null;
                }
                String[] projection = {
                        ContactsContract.CommonDataKinds.Phone._ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                };
                String selection = ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
                String[] selectionArgs = {"%" + constraint.toString().trim() + "%"};
                try {
                    return getContentResolver().query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            projection, selection, selectionArgs,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
                } catch (Exception e) {
                    Log.e(TAG, "query contacts failed", e);
                    return null;
                }
            }
        });
    }

    /** 显示“要不要从通讯录选择联系人”的对话框。 */
    private void showContactPickerDialog() { // modified: 从匿名内部类抽出，便于复用与生命周期保护
        if (isFinishing() || isDestroyed()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.MyDialogTheme);
        builder.setTitle(R.string.contact_picker_title);
        builder.setIcon(R.drawable.ic_contact_phone_black_24dp);
        builder.setMessage(R.string.contact_picker_message);
        builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                try {
                    Intent contactPicker = new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                    startActivityForResult(contactPicker, RESULT_PICK_CONTACT);
                } catch (Exception e) {
                    // 设备上没有任何通讯录应用时会抛 ActivityNotFoundException
                    Log.e(TAG, "no contact picker available", e);
                    showSnackbar(R.string.no_contact_app);
                }
            }
        });
        builder.setNegativeButton(R.string.no, null);
        builder.create().show();
    }

    private void setTriggerEnabled(boolean enabled) { // modified: 用透明度表示禁用，替代直接改背景色
        if (trigButton == null) {
            return;
        }
        trigButton.setEnabled(enabled);
        trigButton.setAlpha(enabled ? 1.0f : 0.5f);
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        // 无需处理
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (contactNumberLayout == null) {
            return;
        }
        String value = s == null ? "" : s.toString().trim();
        // 允许为空（表示不限号码）；非空时必须符合 5-15 位数字（可带首部 +）
        boolean invalid = !value.isEmpty() && !PhoneNumbers.isValidInput(value); // modified: 原正则会把 11 位手机号判为非法
        if (invalid) {
            contactNumberLayout.setError(getString(R.string.input_format_error));
            contactNumberLayout.setErrorEnabled(true);
            contactNumberLayout.setErrorIconDrawable(R.drawable.ic_block_24dp);
            contactNumberLayout.setErrorIconOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EditText editText = contactNumberLayout.getEditText();
                    if (editText != null && editText.getText() != null) {
                        editText.getText().clear();
                    }
                }
            });
            setTriggerEnabled(false);
        } else {
            contactNumberLayout.setErrorEnabled(false);
            contactNumberLayout.setHelperTextEnabled(true);
            setTriggerEnabled(true);
        }
    }

    @Override
    public void afterTextChanged(Editable editable) {
        // 无需处理
    }

    @Override
    protected void onDestroy() {
        // 移除所有延迟任务，避免界面销毁后回调仍然执行
        uiHandler.removeCallbacksAndMessages(null); // modified: 修复 Handler 泄漏与销毁后回调
        if (contactNumberLayout != null) {
            EditText editText = contactNumberLayout.getEditText();
            if (editText != null) {
                editText.removeTextChangedListener(this);
            }
        }
        super.onDestroy();
    }
}
