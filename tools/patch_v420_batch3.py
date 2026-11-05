# -*- coding: utf-8 -*-
"""v4.2.0 批次 3：界面、权限与合规细节。"""
import io, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "saiga", "find", "messagefinder")
RES = os.path.join(ROOT, "app", "src", "main", "res")
changes = []


def rp(rel):
    return os.path.join(ROOT, rel)


def read(p):
    with io.open(os.path.join(JAVA, p), encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with io.open(os.path.join(JAVA, p), "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def rep(name, s, old, new, tag, count=1):
    n = s.count(old)
    assert n == count, "%s :: %s 期望 %d 处，实际 %d 处" % (name, tag, count, n)
    changes.append((name, tag))
    return s.replace(old, new, count)


def edit_text(path, old, new, tag, count=1):
    with io.open(path, encoding="utf-8") as f:
        s = f.read()
    n = s.count(old)
    assert n == count, "%s :: %s 期望 %d 处，实际 %d 处" % (os.path.basename(path), tag, count, n)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(s.replace(old, new, count))
    changes.append((os.path.basename(path), tag))


# ============================================================ AppConfig：引导完成标记
n = "AppConfig.java"
s = read(n)
s = rep(n, s,
'''    private static final String FEATURE_PREF_NAME = "Settings";
    private static final String KEY_AUTO_SUGGESTION_DISABLED = "autoSuggDisabled";''',
'''    private static final String FEATURE_PREF_NAME = "Settings";
    private static final String KEY_AUTO_SUGGESTION_DISABLED = "autoSuggDisabled";
    /** modified: 【v4.2】引导完成的持久化标记，不依赖 PackageManager 组件禁用是否成功。 */
    private static final String KEY_ONBOARDING_DONE = "onboarding_done_v4";''', "引导标记键")

s = rep(n, s,
'''    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }''',
'''    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }

    /** @return 用户是否已经走完首次引导 */
    public boolean isOnboardingDone() { // modified: v4.2 新增
        return safeGetBoolean(featurePrefs, KEY_ONBOARDING_DONE, false);
    }

    /**
     * 标记引导已完成。
     *
     * <p>modified: 【v4.2 独立复审】原实现只靠 setComponentEnabledSetting(DISABLED)
     * 判定引导是否结束，而该调用在受管设备 / 工作资料 / 部分 ROM 上会失败且被静默吞掉。
     * 一旦失败，MainActivity 每次冷启动都会再次拉起引导页，用户卡在「完成 -> 又回引导页」。
     * 这里补一个独立的持久化标记作为主判定，组件禁用降级为加固手段。
     */
    public void setOnboardingDone(boolean done) { // modified: v4.2 新增
        featurePrefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).commit();
    }''', "引导完成读写")
write(n, s)

# ============================================================ Onboarding
n = "Onboarding.java"
s = read(n)
s = rep(n, s,
'''        setTheme(R.style.OnboardingTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_onboarding);

        slideView''',
'''        setTheme(R.style.OnboardingTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_onboarding);
        // modified: 【v4.2 独立复审】本页是唯一没做 edge-to-edge 的 Activity。
        // targetSdk 35+ 内容默认绘制到系统导航栏之下，而 button_bar 只有 16dp 底部 padding，
        // 三键导航 / 手势导航会盖住「完成」按钮 —— 这是首次安装必经路径，
        // 被遮挡等于用户根本进不了主界面。与 MainActivity / SettingsActivity 对齐补上 insets。
        EdgeToEdgeHelper.apply(this, R.id.onboarding_root);
        // modified: 进程被杀时可能残留被抬高的系统闹钟音量，任意组件启动都尝试还原一次
        AlarmVolumeGuard.restoreIfStale(this);

        slideView''', "引导页边到边与音量恢复")

s = rep(n, s,
'''        try {
            Intent launchMain = new Intent()
                    .setComponent(new ComponentName(sourceView.getContext(), MainActivity.class));
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(Onboarding.this);
            startActivity(launchMain, options.toBundle());
        } catch (Exception e) {
            // 动画资源异常时退化为普通跳转，不能因此卡在引导页
            Log.e(TAG, "launch main activity with transition failed", e);
            startActivity(new Intent(this, MainActivity.class));
        }
        try {
            // 禁用自身，避免用户再次进入引导页
            getPackageManager().setComponentEnabledSetting(''',
'''        // modified: 【v4.2】先落盘「已完成」，再尝试禁用组件。
        // 组件禁用失败时这个标记仍能保证 MainActivity 不再把用户送回引导页。
        AppConfig.get(this).setOnboardingDone(true);
        try {
            Intent launchMain = new Intent()
                    .setComponent(new ComponentName(sourceView.getContext(), MainActivity.class));
            launchMain.putExtra(MainActivity.EXTRA_FROM_ONBOARDING, true); // modified: 标记来源
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(Onboarding.this);
            startActivity(launchMain, options.toBundle());
        } catch (Exception e) {
            // 动画资源异常时退化为普通跳转，不能因此卡在引导页
            Log.e(TAG, "launch main activity with transition failed", e);
            Intent plain = new Intent(this, MainActivity.class);
            plain.putExtra(MainActivity.EXTRA_FROM_ONBOARDING, true); // modified: 标记来源
            startActivity(plain);
        }
        try {
            // 加固：禁用自身。失败也不影响（主判定已交给持久化标记）
            getPackageManager().setComponentEnabledSetting(''', "引导完成持久化标记")
write(n, s)

# ============================================================ MainActivity
n = "MainActivity.java"
s = read(n)

s = rep(n, s,
'''    private static final String TAG = "Sms Receiver";''',
'''    private static final String TAG = "Sms Receiver";

    /** modified: 【v4.2】引导页跳过来时携带，用于抑制「刚结束引导就抢焦点弹权限框」。 */
    public static final String EXTRA_FROM_ONBOARDING = "from_onboarding_v4";''', "引导来源 extra")

s = rep(n, s,
'''    /** 引导页是否在本进程启动时展示过；为 true 时不在主界面抢焦点弹权限框。 */
    private static boolean showOnboarding = false;''',
'''    /** modified: 【v4.2】原为 static，且唯一置 true 的分支会立刻 finish 返回，恒为 false。
     *  改为实例字段并由引导页 Intent 显式传入，注释宣称的保护才真正生效。 */
    private boolean fromOnboarding = false;''', "引导标志改实例字段")

s = rep(n, s,
'''        setTheme(R.style.MyAppTheme);
        super.onCreate(savedInstanceState);
        restoredFromState = savedInstanceState != null;

        // 结构控制：首次安装时进入引导页，引导完成后禁用 Onboarding 组件
        if (isOnboardingEnabled()) {
            Log.d(TAG, "onboarding enabled, launch it");
            showOnboarding = true;
            startActivity(new Intent(this, Onboarding.class));
            finish();
            return; // modified: 提前返回，避免继续初始化一个已 finish 的界面
        }
        showOnboarding = false;
''',
'''        setTheme(R.style.MyAppTheme);
        super.onCreate(savedInstanceState);
        restoredFromState = savedInstanceState != null;
        fromOnboarding = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FROM_ONBOARDING, false); // modified: 读取来源
        if (savedInstanceState != null) {
            // modified: 【v4.2】旋转重建后这三个 UI 标志原本归零，
            // 会再次弹「是否要选择联系人」对话框、重复询问用户。
            isContactPicked = savedInstanceState.getBoolean("state_contact_picked", false);
            contactPickerAsked = savedInstanceState.getBoolean("state_picker_asked", false);
            isAutoSuggestionDisabled = savedInstanceState.getBoolean("state_suggestion_disabled", false);
        }
        // modified: 进程被强杀后，上次提醒可能把系统闹钟音量永久改成最大，先还原
        AlarmVolumeGuard.restoreIfStale(this);

        // 结构控制：首次安装时进入引导页，引导完成后不再出现
        if (isOnboardingEnabled()) {
            Log.d(TAG, "onboarding enabled, launch it");
            startActivity(new Intent(this, Onboarding.class));
            finish();
            return; // modified: 提前返回，避免继续初始化一个已 finish 的界面
        }
''', "onCreate 状态恢复与来源")

s = rep(n, s,
'''    @Override
    public boolean onCreateOptionsMenu(Menu menu) {''',
'''    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) { // modified: v4.2 新增
        super.onSaveInstanceState(outState);
        outState.putBoolean("state_contact_picked", isContactPicked);
        outState.putBoolean("state_picker_asked", contactPickerAsked);
        outState.putBoolean("state_suggestion_disabled", isAutoSuggestionDisabled);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {''', "保存 UI 状态")

s = rep(n, s,
'''    private boolean isOnboardingEnabled() { // modified: 抽出方法，逻辑更直观
        int enabled = getPackageManager()
                .getComponentEnabledSetting(new ComponentName(this, Onboarding.class));
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }''',
'''    private boolean isOnboardingEnabled() { // modified: 抽出方法，逻辑更直观
        // modified: 【v4.2】持久化标记为主判定：setComponentEnabledSetting 在某些
        // 受管设备 / ROM 上会失败并只打日志，原实现因此每次冷启动都重新进引导页。
        if (AppConfig.get(this).isOnboardingDone()) {
            return false;
        }
        int enabled = getPackageManager()
                .getComponentEnabledSetting(new ComponentName(this, Onboarding.class));
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }''', "引导判定改用持久化标记")

s = rep(n, s,
'''    private void showSnackbar(int stringResId) {
        if (userMessageLayout != null) {''',
'''    private void showSnackbar(int stringResId) {
        // modified: 【v4.2】同文件其它弹窗都有 isFinishing 校验，唯独 Snackbar 没有。
        // 已 finish 的 Activity 上 userMessageLayout 仍非 null，会继续执行后续保存逻辑。
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (userMessageLayout != null) {''', "Snackbar 生命周期")

s = rep(n, s,
'''    private void showPermissionSnackbar(int messageResId) {
        if (userMessageLayout == null) {''',
'''    private void showPermissionSnackbar(int messageResId) {
        // modified: 【v4.2】同上，避免界面销毁后仍展示/持有 Snackbar
        if (isFinishing() || isDestroyed() || userMessageLayout == null) {''', "权限 Snackbar 生命周期")

s = rep(n, s,
'''        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERM_NOTIFICATIONS_REQUEST) { // modified: 新增通知权限回调
            if (!granted) {
                showSnackbar(R.string.notif_permission_denied);
            }
            updateStatusUi();
            return;
        }''',
'''        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // modified: 【v4.2】点「启用」发起权限请求后立刻按返回键退出，
        // 授权结果仍会投递到已 finish 的实例；原实现会继续读取界面并写入配置。
        if (isFinishing() || isDestroyed()) {
            Log.d(TAG, "permission result arrived after activity finished, ignored");
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERM_NOTIFICATIONS_REQUEST) { // modified: 新增通知权限回调
            if (!granted) {
                // modified: 【v4.2 独立复审】通知是主链路唯一唤醒入口，被永久拒绝后
                // 应用内原本没有任何恢复入口（短信/联系人权限分支都有「去设置」Action）。
                // 永久拒绝时给一条带设置页入口的长驻 Snackbar。
                if (shouldExplainDenied(Manifest.permission.POST_NOTIFICATIONS)) {
                    showPermissionSnackbar(R.string.notif_permission_denied);
                } else {
                    showSnackbar(R.string.notif_permission_denied);
                }
            }
            updateStatusUi();
            return;
        }''', "通知权限恢复入口与生命周期")

s = rep(n, s,
'''                List<String> keywords = KeywordMatcher.parseKeywords(getTextOf(R.id.message_value));
                if (keywords.isEmpty()) {
                    showSnackbar(R.string.keyword_empty);
                    return;
                }
                saveConfig(contactRaw, keywords); // modified: 从界面重新取值，修复 NPE 与脏数据''',
'''                // modified: 【v4.2 独立复审】handleTrigger() 里做了号码格式校验，
                // 但权限回调这条分支直接 saveConfig，绕过了校验。请求权限期间用户
                // 若改动输入框，脏号码会被原样写进配置，规则静默失效。
                if (!TextUtils.isEmpty(contactRaw) && !PhoneNumbers.isValidInput(contactRaw)) {
                    showSnackbar(R.string.input_format_error);
                    return;
                }
                List<String> keywords = KeywordMatcher.parseKeywords(getTextOf(R.id.message_value));
                if (keywords.isEmpty()) {
                    showSnackbar(R.string.keyword_empty);
                    return;
                }
                saveConfig(contactRaw, keywords); // modified: 从界面重新取值，修复 NPE 与脏数据''', "权限回调补号码校验")

s = rep(n, s,
'''        if (showOnboarding || restoredFromState) { // modified: 生命周期校验''',
'''        if (fromOnboarding || restoredFromState) { // modified: 生命周期校验''', "引导标志引用")

s = rep(n, s,
'''    void setUserConfig(boolean disabled) {
        appConfig.setAutoSuggestionDisabled(disabled);
    }''',
'''    void setUserConfig(boolean disabled) {
        appConfig.setAutoSuggestionDisabled(disabled);
        // modified: 【v4.2】「不再显示」按钮本意是不再打扰，但 autoSuggDisabled=true
        // 恰好是「每次聚焦号码框都弹『是否选择联系人』」的触发条件，语义反噬。
        // 同时把会话内「已问过」置位，让这次选择真正生效。
        contactPickerAsked = true;
    }''', "不再显示语义修正")
write(n, s)

# ============================================================ SettingsActivity
n = "SettingsActivity.java"
s = read(n)
s = rep(n, s,
'''        @Override
        public void onResume() {
            super.onResume();
            refreshActiveConfiguration(); // 从系统设置页返回时重新检测权限状态
        }''',
'''        @Override
        public void onResume() {
            super.onResume();
            // modified: 【v4.2】自动联想开关由 AppConfig（"Settings" 文件）托管，
            // 而 syncAutoSuggestionState 原先只在 onCreatePreferences 调用一次；
            // 通过 MyDialogFragment 改过开关、或旋转后 Fragment 被复用，
            // 界面会显示旧的默认值。每次可见时用真实配置校准一次。
            Preference autoPref = findPreference(getString(R.string.auto_suggestion_key));
            if (autoPref != null) {
                autoPref.setOnPreferenceChangeListener(null); // 避免 setChecked 回写
                syncAutoSuggestionState(autoPref);
                autoPref.setOnPreferenceChangeListener(this);
            }
            refreshActiveConfiguration(); // 从系统设置页返回时重新检测权限状态
        }''', "设置页可见时校准开关")
write(n, s)

# ============================================================ 资源与清单
edit_text(rp("app/src/main/res/layout/screen_onboarding.xml"),
'''<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"''',
'''<!-- modified: 【v4.2】给根布局加 id，供 EdgeToEdgeHelper 施加系统栏 insets。 -->
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/onboarding_root"
    android:layout_width="match_parent"''',
"引导页根布局 id")

for lang in ("values", "values-zh-rCN"):
    edit_text(rp("app/src/main/res/%s/strings.xml" % lang),
'''<string name="privacy_url">http://unchartedtech.work/privacy-policy</string>''',
'''<string name="privacy_url">https://unchartedtech.work/privacy-policy</string> <!-- modified: usesCleartextTraffic=false 下 http 明文外链会被拦截 -->''',
"隐私链接改 https (%s)" % lang)

edit_text(rp("app/src/main/res/xml/app_preferences.xml"),
'''        <SwitchPreferenceCompat android:title="@string/auto_suggestion_feature"
            android:defaultValue="false"
            android:enabled="true"''',
'''        <!-- modified: 【v4.2】该开关由 AppConfig 单一托管。
             原来 AndroidX 框架还会自动把 autosuggestion_key 写进默认 prefs，
             与 "Settings" 文件里的 autoSuggDisabled 形成两套可分裂的状态。 -->
        <SwitchPreferenceCompat android:title="@string/auto_suggestion_feature"
            android:defaultValue="false"
            android:persistent="false"
            android:enabled="true"''', "自动联想单一数据源")

edit_text(rp("app/src/main/AndroidManifest.xml"),
'''        <!-- modified: 全屏告警页。除了代码里的 setShowWhenLocked/setTurnScreenOn，
             这里再补 manifest 属性——MIUI / HyperOS 上有 ROM 会忽略纯代码设置，
             导致锁屏拉起时页面被锁屏盖住、铃声在响却看不到任何界面。
             configChanges：避免旋转屏幕重建页面（重建会再次启动播放服务，铃声从头开始）。 -->''',
'''        <!-- modified: 全屏告警页。除了代码里的 setShowWhenLocked/setTurnScreenOn，
             这里再补 manifest 属性——MIUI / HyperOS 上有 ROM 会忽略纯代码设置，
             导致锁屏拉起时页面被锁屏盖住、铃声在响却看不到任何界面。
             v4.2：移除已废弃且语义无关的 android:showOnLockScreen（它实际是
             "为所有用户显示"的旧名，不控制锁屏展示），保留真正生效的两个属性。
             configChanges：避免旋转屏幕重建页面（重建会再次启动播放服务，铃声从头开始）。 -->''',
"清单注释修正")

# PhoneNumbers 中已不再使用的常量
n = "PhoneNumbers.java"
s = read(n)
s = rep(n, s,
'''    /** 参与“后缀匹配”的最短有效长度，低于该长度只允许完全相等，避免 "123" 这类短号误命中。 */
    private static final int MIN_SUFFIX_LENGTH = 7;
''',
'''    // modified: 【v4.2】移除 MIN_SUFFIX_LENGTH（原 7 位门槛把 10086 / 95588 挡在门外）。
    // 现在由 matches() 用「较短侧 >= MIN_INPUT_LENGTH 且长度差 <= 4」统一约束。
''', "移除废弃常量")
write(n, s)

print("批次 3 完成，共 %d 处" % len(changes))
for name, tag in changes:
    print("  %-34s %s" % (name, tag))
