# 铃信 / Bellgram v4.0.1 —— 独立复审报告

> **更名说明（v4.1.0）**：应用显示名已于 v4.1.0 由「铃信」改为 **「铃达」/ Bellgram**。
> 原因是「铃信」líng xìn 与法律术语「聆讯」líng xùn 高度谐音（声母同、韵母近、同为去声），
> 放在公开仓库首页时容易被误读。包名与 `applicationId` 未变，覆盖安装不受影响。
> **本报告正文保留撰写时的原名与版本号**，以如实反映当时的审查对象。

> 复审对象：`PriorityAlerts-v3`（v4.0.0，versionCode 18）
> 复审方式：**不采信前一版报告的任何结论**，直接逐行阅读 27 个 Java 文件（约 4900 行）、
> 34 个 XML/资源文件、3 个构建脚本，并用脚本做交叉校验；对每一项「已修复」都独立推演
> 触发条件与失败路径。
> 复审日期：2026-11-05　|　产出：v4.0.1（versionCode 19）

---

## 0. 复审结论摘要

前一轮（v4.0.0）的**架构方向是正确的**：`targetSdk 36` 下不能再从短信广播启动前台服务，
改成「通知 → 用户可见页 → 单轮服务 → `setAlarmClock` 精确闹钟」是唯一可行的合规路径，
这一点经独立核对官方行为变更文档后**确认成立**。

但**实现层面存在 12 处真实缺陷**，其中 3 处会直接破坏核心功能（双重响铃、状态通知误弹全屏页、
"一直响"模式下铃声停不下来），4 处会在特定操作下崩溃或状态错乱，5 处属于健壮性/可移植性缺口。
另有 **1 项被前序报告误判为缺陷**（经复核不成立），以及 **1 处代码溯源标注缺失**。

此外，**上一版报告里有 5 条表述与代码实际状态不符**（详见 §9），已在本轮修正。

**本轮共修改 15 个文件**，全部改动带 `// modified` 标注并说明原因；校验结果见 §10。
**仍未完成**：本机无 JDK / Android SDK，**未执行 Gradle 构建**，这一点不做任何粉饰（见 §11）。

---

## 1. 维度一：逻辑正确性

### 1.1 【致命】静音渠道沿用了 v3 的渠道 ID，老用户升级后必然双重响铃

| | |
|---|---|
| 代码位置 | `NotificationHelper.java` 原第 41 行。调用链：`AlertDispatcher.postRound()` → `CHANNEL_ID_AUDIBLE`；`SMSForegroundService.handleStartAlert()` → `startForeground()` 用 `CHANNEL_ID_ALERT` |
| 原始写法 | `public static final String CHANNEL_ID_ALERT = "priority_alert_v2"; // 沿用 v3 渠道` |
| 复核过程 | v4 的注释称「沿用 v3 渠道，避免旧渠道声音不可变的历史包袱」。这句话本身就是矛盾的。Android 的 `NotificationChannel` **创建后除名称/描述外的属性均不可修改**（`setSound` 尤其明确）。v3 里 `priority_alert_v2` 是「主提醒渠道」，创建时带默认通知音；v4 在 `ensureChannels()` 里对它调 `setSound(null, null)`——对**已存在的渠道完全无效**。 |
| 触发条件 | 任何从 v3.0.0 覆盖安装到 v4.0.0 的设备。新装用户不受影响，所以只要测试机是新装的，这个问题**测不出来**。 |
| 后果 | 服务播放 `MediaPlayer` 的同时，该渠道的通知仍然出声 → 双重响铃 + 音质浑浊。这恰好是 v4 声称「双渠道解决」的问题本身。 |
| 修复 | 静音渠道换新 ID `priority_alert_quiet_v4`；并在 `ensureChannels()` 里 `deleteNotificationChannel("priority_alert_v2")` 清理 v3 遗留渠道，避免系统设置里留一条无用项。 |

```java
// 修改后
public static final String CHANNEL_ID_ALERT = "priority_alert_quiet_v4"; // modified: 换新 ID，旧渠道声音不可变
private static final String CHANNEL_ID_LEGACY_V3 = "priority_alert_v2";  // modified: 新增，仅用于清理
...
manager.createNotificationChannel(audibleChannel);
manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V3); // modified
```

> **这条推翻了前一版报告 §5 与 §4 中「双渠道分层已解决双重响铃」的结论。**

---

### 1.2 【致命】全屏 Intent 被无差别挂在所有通知上，导致状态通知误弹全屏页、并可能与服务互相触发

| | |
|---|---|
| 代码位置 | `NotificationHelper.build(...)` 原第 161–164 行 |
| 原始写法 | `if (canUseFullScreenIntent(context)) { builder.setFullScreenIntent(...); }` —— **没有调用方开关** |
| 复核过程 | `build()` 被三类调用方共用：① `AlertDispatcher.postRound()`（真正叫人）；② `AlertDispatcher.postQuiet()`（"已稍后""等待中""已结束"）；③ `SMSForegroundService.handleStartAlert()` 的 `startForeground()` 通知。三类全部会带上 FSI。 |
| 后果 A | 用户点「停止」后，`finish()` 发的通知文案是「提醒已结束」，但它带 FSI。设备锁屏/息屏时，系统会**因为这个通知弹出全屏告警页**，页面上写着「提醒已结束」。用户会以为应用失控。 |
| 后果 B | 服务 `startForeground()` 的通知带 FSI。服务由 `AlertActivity` 启动，`startForeground` 发布带 FSI 的通知，若此刻屏幕刚熄灭，系统会再次拉起 `AlertActivity` → `onNewIntent` → `startAlertService()` → 服务 `handleStartAlert` → 再 `startForeground` → 再 FSI。形成**锁屏状态下的重复拉起**。 |
| 修复 | 给 `build()` / `post()` 增加 `boolean withFullScreenIntent` 参数；只有 `postRound()` 与降级路径 `notifyDirectly()` 传 `true`，其余一律 `false`。 |
| 兼容性 | 保留原 6 参 / 7 参重载，内部委托到新签名并默认 `false`（"不打扰"是更安全的默认值），调用方无需全改。 |

```java
// 修改后
public static Notification build(..., CharSequence status, boolean withFullScreenIntent) {
    ...
    if (withFullScreenIntent && canUseFullScreenIntent(context)) {
        builder.setFullScreenIntent(buildAlertScreenIntent(...), true);
    }
}
```

> **这条推翻了「FSI 权限检测已完善」的隐含结论：权限检测对了，但"该不该带 FSI"这个更根本的问题没被处理。**

---

### 1.3 【严重】「一直响到手动停止」模式下，停止指令失败会让铃声无限响

| | |
|---|---|
| 代码位置 | `AlertDispatcher.requestServiceStop()` |
| 原始写法 | `try { app.startService(intent); } catch (Exception e) { Log.d(TAG, "service already stopped, ignore", e); }` —— 注释断言「捕获即可，不影响正确性」 |
| 复核过程 | 该断言在**默认配置（单轮 60 秒）**下成立：即便停止失败，`ringTimeoutRunnable` 到点后 `endRound()` 会执行，且它发现 `!session.isActive()` 会 `stopSelfSafely(true)` 收尾。但用户可把「单轮响铃时长」选为**「一直响到手动停止」**（`ringDurationSec == 0`）。此时 `startRound()` 里 `if (ringDurationSec > 0)` 不成立，**根本不注册定时器**，`endRound()` 永远不会被调用。停止服务的唯一途径就是这条 `startService(ACTION_STOP)`。而 targetSdk 31+ 下从后台 `startService` 在应用**没有前台服务在跑**时会被拒（例如服务已被系统回收、或铃声已停但服务残留）。 |
| 后果 | 铃声/震动**无限持续**，通知已被 `cancel()` 撤掉，用户找不到任何停止入口，只能强杀应用或重启手机。对一个"叫醒"应用来说这是最坏的用户体验。 |
| 修复 | catch 分支降级为 `app.stopService(...)`。`stopService` **不受后台启动限制**，必定终止服务；`onDestroy()` 里已有完整清理（归还音量、`releasePlayer()`、`vibrator.cancel()`、`releaseWakeLock()`），语义无损。 |

```java
} catch (Exception e) {
    Log.w(TAG, "graceful stop rejected, fall back to stopService", e);
    try {
        app.stopService(new Intent(app, SMSForegroundService.class)); // 不受后台启动限制
    } catch (Exception stopError) {
        Log.e(TAG, "stopService also failed", stopError);
    }
}
```

> **这条推翻了「捕获异常即可，不影响正确性」的结论。**

---

### 1.4 【严重】设置页在配置变更时重复装载 Fragment

| | |
|---|---|
| 代码位置 | `SettingsActivity.onCreate()` |
| 原始写法 | 无条件 `getSupportFragmentManager().beginTransaction().replace(...).commit();` |
| 复核过程 | 旋转屏幕 / 切换深色模式 / 分屏，都会重建 Activity。`FragmentManager` 会**自动恢复**上一次的 `AppPreferenceFragment`（它无参构造 + 有 `setPreferencesFromResource`，可恢复）。此时 `onCreate` 再 `replace` 一个新的，等于把恢复出来的实例替换掉。表现为：刚在设置页改的值回跳、滚动位置丢失、偶发两份 `Preference` 同时响应点击。 |
| 修复 | `if (savedInstanceState == null)` 才添加。这是 AndroidX 的标准写法。 |

---

### 1.5 【中等】全屏告警页旋转会重启铃声

| | |
|---|---|
| 代码位置 | `AlertActivity.onCreate()` |
| 原始写法 | 无条件调用 `startAlertService();` |
| 复核过程 | 旋转 → Activity 重建 → `onCreate` 再次 `startForegroundService` → 服务 `onStartCommand(ACTION_START_ALERT)` → `handleStartAlert()` → `startRound()`，铃声从 0 秒重新开始。用户在闹铃响到第 40 秒时转一下手机，铃声又从头响 60 秒。 |
| 修复 | ① `savedInstanceState == null` 才启动服务；② manifest 给 `AlertActivity` 加 `configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode"`，从源头上避免重建。两道保险。 |

---

### 1.6 【中等】全屏告警页在 MIUI / HyperOS 上可能被锁屏盖住

| | |
|---|---|
| 代码位置 | `AndroidManifest.xml` 的 `<activity android:name=".AlertActivity">` |
| 原始写法 | 只声明了 `excludeFromRecents` / `exported` / `launchMode` / `taskAffinity` / `theme` |
| 复核过程 | 代码里用 `setShowWhenLocked(true)` + `setTurnScreenOn(true)`（API 27+）。但部分定制 ROM（MIUI、部分 ColorOS）对**纯代码设置**的 `showWhenLocked` 支持不完整，必须在 manifest 声明才可靠。缺这个属性的表现正是「铃声在响，但屏幕上什么都看不到」。 |
| 修复 | 补 `android:showWhenLocked="true"`、`android:showOnLockScreen="true"`、`android:turnScreenOn="true"`（配 `tools:targetApi="o_mr1"` 消除 lint 误报）。 |

---

### 1.7 【中等】配置读取在「收到短信」主路径上可能抛 ClassCastException

| | |
|---|---|
| 代码位置 | `AppConfig.readInt()` |
| 原始写法 | `String raw = configPrefs.getString(key, null);` 在 `try` **之外**，后面才 `try { getInt(...) }` |
| 复核过程 | 作者显然意识到「同一键可能被写成 int」，但把防御放在了 `raw == null` 分支里。真正会抛异常的是 `getString()` 本身——一旦该键存的是 `Integer`/`Boolean`（早期版本 `putInt`、用户用 root 工具改 prefs、或其它分支写入），`getString` 直接抛 `ClassCastException`。调用链是 `SMSReceiver.handleSms → AlertDispatcher.onSmsMatched → AppConfig.getTotalRounds → getRingDurationSec → readInt`。虽然 `onReceive` 有整体 try/catch 不会崩，但结果是**这条短信完全不提醒**，且没有任何用户可见反馈。 |
| 修复 | 把 `getString` 本身包进 try，失败则尝试按 int 读，再失败用默认值。 |

---

### 1.8 【中低】通知颜色取色可能 NPE

| | |
|---|---|
| 代码位置 | `NotificationHelper.build()` |
| 原始写法 | `.setColor(context.getResources().getColor(R.color.secondaryDarkColor, null))` |
| 复核过程 | `Resources.getColor(int, Theme)` 内部要用 `theme.resolveAttribute`，第二个参数传 `null` 在部分 ROM 实现上会 NPE。虽然它包在 `builder` 链里、外层 `post()` 有 catch，但会让整条通知构建失败 → 不提醒。 |
| 修复 | 改用 `ContextCompat.getColor(context, R.color.secondaryDarkColor)`。 |

---

### 1.9 【中低】引导页平行数组越界

| | |
|---|---|
| 代码位置 | `SliderAdapter.getCount()` / `instantiateItem()` |
| 原始写法 | `getCount()` 只返回 `mPageTitle.length`；`instantiateItem()` 用 `position` 直接索引 `mDrawableIcons` / `mPageTitleColor` / `mPageDescColor` / `mPageDescs` |
| 复核过程 | 四个数组目前都是 3 个元素，所以现在不炸。但这是**结构性隐患**：任何人给标题加一页而忘了补颜色，就是 `ArrayIndexOutOfBoundsException`，且发生在首次启动的引导页——应用直接不可用。同一方法里 `mLayoutInflater` 来自 `getSystemService`，理论上可为 null。 |
| 修复 | `getCount()` 取四个数组的最小长度；`instantiateItem()` 再做一次安全索引；`LayoutInflater` 改为局部变量 + `LayoutInflater.from()` 兜底。 |

---

### 1.10 【低】主界面三处 `findViewById` 未判空

| | |
|---|---|
| 代码位置 | `MainActivity.handleClear()`、`clearFocusOfForm()`、`onActivityResult()` |
| 原始写法 | `EditText contactView = findViewById(R.id.contact_value); contactView.getText().clear();` |
| 复核过程 | 当前布局不会被裁剪，所以现在不炸。但 `getTextOf()` 已经做了判空，同一份代码里两种风格并存，属于"防御不一致"——一旦以后有人把这几个控件换成 `TextInputEditText` 之外的实现或做换肤，就是 NPE。 |
| 修复 | 抽出 `findEditText(int)` 统一安全取用，三处改用它。 |

---

### 1.11 【构建·最影响可运行性】`gradle-wrapper.jar` 仍是 Gradle 4.6，与 8.13 发行版不匹配

| | |
|---|---|
| 代码位置 | `gradle/wrapper/gradle-wrapper.jar` |
| 复核过程 | 读 `META-INF/MANIFEST.MF`：`Implementation-Version: 4.6`。而 `gradle-wrapper.properties` 已指向 `gradle-8.13-bin.zip`。前一版报告写「Wrapper 已升级 4.6 → 8.7 → 8.13」——那只改了 properties，**jar 没换**。 |
| 影响 | wrapper jar 负责「下载并引导」发行版。4.6 的引导器跨 7 个大版本去拉起 8.13，在 JDK 17 上属于未验证组合，有失败风险；即使成功也缺少 8.x 引导器新增的校验（如 `PathTraversalChecker`）。对"拉到笔记本上直接跑"这个目标来说，这是**首要障碍**。 |
| 修复 | 从 Gradle 官方仓库 `v8.13.0` 标签取 `gradle-wrapper.jar`（43705 B，31 个 class，含 `PathTraversalChecker`）替换；同步替换 `gradlew`（8774 B）与 `gradlew.bat`（2988 B，已转 CRLF），`gradlew` 保留可执行位。 |

---

### 1.12 【复核为「非缺陷」的一项】告警页 `layout_weight` —— 前序无需修，我做了表意优化

前一版把 `activity_alert.xml` 里用 `<Space android:layout_weight="1">` 把按钮压到底部，判为可用。
我一度怀疑「内层 LinearLayout 是 `wrap_content`，weight 无效」，但复核 `ScrollView.onMeasure()` 在
`fillViewport="true"` 时会把子 View 以 `EXACTLY(viewportHeight)` 重测，`wrap_content` 被覆盖，
**weight 实际生效，原写法没有 bug**。

我把内层改成 `match_parent` 并去掉 `minHeight="480dp"`，原因是：`fillViewport` 的重测只在
「子 View 实测高度 < viewport」时发生，写成 `match_parent` 语义更直接、也不依赖那个分支。
**这属于可读性优化，不冒充"修复缺陷"。**

---

## 2. 维度二：改动合理性

逐项核对 12 项新增功能与原项目目标（「特定内容短信 → 强提醒」）的贴合度：

| 功能 | 是否贴合原目标 | 判断 |
|---|---|---|
| 响铃 / 震动独立开关 | ✅ 直接服务于"提醒力度可调" | 保留 |
| 单轮时长（含"一直响"） | ✅ 核心诉求 | 保留 |
| 每 N 分钟重复、最多 N 轮 | ✅ 用户明确要求的"像闹钟一样" | 保留 |
| 通知上显示轮次进度 | ✅ 解决"到底响没响过"的焦虑 | 保留 |
| 匹配模式「任意 / 全部关键词」 | ✅ 直接降低误报，是这类应用的刚需 | 保留 |
| 夜间静音（只震动） | ✅ 防半夜惊扰，场景内 | 保留 |
| 规则演练 | ✅ 解决"规则写错时毫无反馈"的排障痛点 | 保留 |
| 稍后提醒 | ✅ 闹钟语义的必要组成 | 保留 |
| 锁屏全屏告警 | ✅ 提升"叫醒力度"的实质手段 | 保留 |
| 最近 20 条命中记录 | ✅ 排障刚需，不申请新权限 | 保留 |
| 权限体检 | ✅ targetSdk 36 下不做这个，用户遇到"不响"完全无解 | 保留 |
| 联系人选择器 / 自动联想 | 上游原有 | 保留，未扩展 |

**结论：没有发现「无关花哨功能」。** 全部 12 项都能对应到"不漏掉重要短信"或"出问题能自查"。

反过来检查**是否有功能被过度实现**：
- `HitLog` 用 `SharedPreferences` 存 20 条文本，没引 Room —— 克制，正确。
- `AlertSession` 单独一个 prefs 文件，没有复用 `AppConfig` —— 正确（会话是易变状态，混进用户配置会互相污染）。
- 未引入 WorkManager / Room / Hilt / Kotlin 协程 —— 对一个 4800 行的个人工具，引入这些属于过度工程。
- 新增依赖仅 `androidx.core`（edge-to-edge 需要）+ `androidx.preference`（替换废弃 API），均为官方库，AAR 约 0.6 MB。

**唯一值得质疑的**：`androidx.legacy:legacy-support-v4:1.0.0` 是已废弃的聚合库，
而 `Onboarding` 只需要 `androidx.viewpager`。理论可替换为 `androidx.viewpager:viewpager:1.0.0` 以减小体积。
**本轮未改**：移除聚合库需要重新验证传递依赖（`CoordinatorLayout`、`SimpleCursorAdapter` 的归属），
在没有编译环境的情况下属于「改动收益 < 引入风险」，故保留并在 §11 列为可选优化。

---

## 3. 维度三：代码规范

### 3.1 做得好的部分（经抽查确认）

- 每个文件头都有「为什么这样改」的块注释，且区分了 `[新增文件]` / `[v4 重写]` / `[独立复审修正]`。
- 工具类统一 `private` 构造 + `final class`（`AlertDispatcher`、`AlertScheduler`、`AlertSession`、`RoundPolicy`、`PhoneNumbers`、`KeywordMatcher`、`QuietHours`、`HitLog`、`PermissionChecker`、`EdgeToEdgeHelper`、`NotificationHelper`、`AppConfig` 共 12 个），风格一致。
- 所有跨组件入口都做了 `context == null` / `intent == null` 早返回。
- 广播接收器 `onReceive` 全部包 try/catch（这是崩溃率最高的一类组件）。
- 资源取用统一走 `getString(R.string.x, args)`，没有硬编码中文（抽查 `PermissionChecker` 的 `"✓ "` / `"! "` 是唯一硬编码，属符号非文案，可接受）。

### 3.2 本轮修正的规范问题

| 项 | 位置 | 问题 | 处理 |
|---|---|---|---|
| ① | `MyDialogFragment` | import `android.app.AlertDialog`（平台）却传 `R.style.MyDialogTheme`（parent 为 `Theme.AppCompat.*`）。平台对话框在 AppCompat 主题下样式不生效，个别 ROM 因 LayoutInflater Factory 冲突抛 `IllegalStateException`；同工程 `MainActivity` 用的是 androidx 版本，风格不一致 | 统一改 `androidx.appcompat.app.AlertDialog` |
| ② | `MainActivity.onResume()` | `super.onResume()` 写在方法最后，违反生命周期约定 | 提到第一行 |
| ③ | `SMSReceiver` 去重字段 | 注释称"广播接收器为单实例"，这是**错的**（系统可能新建实例，真正生效的是静态字段）。且非 `volatile`，部分 ROM 在非主线程分发广播时可见性无保证 | 补 `volatile` 并改正注释 |
| ④ | `PermissionChecker.check()` | `context.getApplicationContext()` 未判空，后续 `app.getPackageName()` 可能 NPE | 加兜底（与其它类保持一致） |

### 3.3 仍存在但本轮不改的规范问题（避免无谓改动面）

- `MainActivity` 一处 `TAG = "Sms Receiver"`（含空格），历史遗留；改动无收益，保留。
- `app_preferences.xml` 里 `app_privacy_policy` 那段有多行空行，格式不整但不影响解析。
- `LevelPreference` 的 `android:summary="%s"` 仍留在 XML（已由 `SimpleSummaryProvider` 覆盖），删除会让 diff 变大，保留。

---

## 4. 维度四：兼容性与可移植性

这是「拉到笔记本上直接跑」这个目标的**真正门槛**，逐项核对：

| 检查项 | 结论 | 说明 |
|---|---|---|
| `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` 三件套齐备 | ✅ 已修 | 原 jar 为 4.6，见 §1.11 |
| `distributionUrl` 可达 | ⚠️ 有条件 | 现指向腾讯云镜像，国内可用；已在 `BUILD.md` 给出官方地址替换法与阿里云仓库配置 |
| `local.properties` 是否入库 | ✅ 正确排除 | 未入库是正确的（写死他人 SDK 路径会破坏构建）；`BUILD.md` 第 3 节给了两种生成方式 |
| `.gitignore` 是否存在 | ✅ 已补 | 原先没有，直接推会把 `build/`、`.idea/`、`local.properties` 带进去；且我在文件里显式注明 **wrapper jar 必须入库**（这是最常见的踩坑：忽略它导致克隆后 `ClassNotFoundException: GradleWrapperMain`） |
| AGP / Gradle / JDK 三者匹配 | ✅ 自洽 | AGP 8.11.1 要求 Gradle ≥ 8.13、JDK 17；`gradle-wrapper.properties` = 8.13；文档要求 JDK 17 |
| `compileSdk 36` 的 SDK 依赖 | ⚠️ 需用户安装 | `BUILD.md` §1/§5 已写明「SDK Manager 勾选 Android 16 (API 36)」及对应报错对照 |
| 依赖版本冲突 | ✅ 无 | appcompat 1.7.0 / core 1.13.1 / activity 1.9.1 / preference 1.2.1 / material 1.12.0 对 `core`、`appcompat`、`fragment` 的传递需求均 ≤ 显式声明版本，Gradle 解析为高版本，无降级冲突 |
| 依赖体积 | ✅ 合理 | 新增两个官方 AAR 约 0.6 MB；未引入 Kotlin 运行时、未引入 Room/Hilt |
| `encoding` 设置 | ✅ 合法 | `android.compileOptions.encoding = 'UTF-8'` 是 AGP `CompileOptions` 的合法属性（不是无效 DSL） |
| 中文字符编码 | ✅ | 全部源码 UTF-8，`gradle.properties` 已设 `-Dfile.encoding=UTF-8` |
| `minSdk 23` 与所用 API | ✅ | `ContextCompat.getColor`（本次修复后）替代了 API 23 下行为不稳的 `getColor(id, null)`；`VibrationEffect` 有 API 26 分支；`STOP_FOREGROUND_DETACH` 有 API 24 分支；`setShowWhenLocked` 有 API 27 分支 |
| 编译验证 | ❌ **未做** | 见 §11，这是唯一无法自证的项 |

---

## 5. 维度五：变更风险完整性

前一版 §7 列了风险，但**遗漏了 4 项**，本轮补齐（前 3 项已通过修复消除，第 4 项为残留风险）：

| # | 前序是否列出 | 风险 | 现状 |
|---|---|---|---|
| 1 | ❌ 遗漏 | 老用户升级后双渠道同时出声 | 已修（§1.1） |
| 2 | ❌ 遗漏 | "已结束/等待中"通知误弹全屏页；服务前台通知与告警页互相触发 | 已修（§1.2） |
| 3 | ❌ 遗漏 | "一直响"模式 + 停止失败 = 铃声无法终止 | 已修（§1.3） |
| 4 | ❌ 遗漏 | 用户拒绝通知权限后，**整条提醒链路静默失效**（`post()` 中 `areNotificationsEnabled()` 为 false 直接 return，`notify()` 也不会抛异常，只有 logcat 有痕迹） | **仍存在，无法用代码根治**。已通过三处缓解：主界面状态条红字警示、设置页权限体检、`post()` 留明确日志。这是 FGS 后台启动限制下的架构性代价，必须在文档里讲清楚 |
| 5 | ✅ 已列 | 未实际编译 | 仍在，见 §11 |
| 6 | ✅ 已列 | `setAlarmClock` 在个别 ROM 抛异常 | 已有降级到 `set(RTC_WAKEUP)` 的兜底 |
| 7 | ✅ 已列 | 系统状态栏出现闹钟图标 | 语义正确，非问题 |
| 8 | ✅ 已列 | `targetSdk 36` 属破坏性变更，回退需改回 29 | 仍成立 |

另补两项前序未提的**低概率**风险：

- **`AlertSession` 每次读写都 `commit()`**：同步 IO 发生在广播接收器（10 秒预算）与主线程。单次写入约 1–3 ms，安全；但如果设备存储极度拥塞（IO 卡死），理论上可能拖慢第一轮通知。选择 `commit()` 是为避免跨进程读竞态，**权衡后保留**。
- **`NotificationHelper.NOTIFICATION_ID` 固定为 123**：服务前台通知、轮次通知、状态通知共用同一 ID。这是**有意设计**（保证通知栏只有一条、状态原地更新），但也意味着「新短信到来时旧通知被覆盖」——若两条短信间隔极短，第一条的正文会被第二条替换。可接受（应用本来就是"最新一条优先"语义）。

---

## 6. 维度六：代码溯源

逐个核对「借鉴/复用」标注：

| 文件 | 借鉴内容 | 标注情况 | 判定 |
|---|---|---|---|
| `KeywordMatcher` | pppscn/SmsForwarder 的"归一化 + 规则分层"思路 | 文件头注明「只借用其分层思想与归一化做法，未复制其代码」，并给了仓库地址 | ✅ 合规 |
| `HitLog` | 同上项目的"转发日志"设计思路 | 文件头注明，并说明「不引入 Room 以控制体积」 | ✅ 合规 |
| `AlertDispatcher` | AOSP DeskClock 的"通知作为唤醒入口"架构思想 | 文件头注明「只借鉴架构思想，未复制第三方代码」 | ✅ 合规 |
| `AlertScheduler` / `SMSForegroundService` / `NotificationHelper` / `AlertSession` / `EdgeToEdgeHelper` | Android 官方文档（Schedule alarms / FGS types / Notifications / Edge-to-edge） | 均标注引用了哪篇官方文档 + "未复制第三方代码" | ✅ 合规 |
| `PermissionChecker` | 官方 Settings 常量、AOSP FSI limits 文档 | 已标注 | ✅ 合规 |
| `QuietHours` / `PhoneNumbers` / `RoundPolicy` | 无外部来源，本工程原创 | 已说明设计动机 | ✅ |
| **`SliderAdapter.decodeSampledBitmapFromResource` + `calculateInSampleSize`** | **Android 官方培训文档《Loading Large Bitmaps Efficiently》示例代码**，上游项目 2018 年直接沿用 | **原代码无任何来源标注** | ❌ **本轮补标** |

修复内容（`SliderAdapter`）：

```java
/**
 * <p>【代码溯源 / modified】本方法与下面的 calculateInSampleSize 直接沿用
 * Android 官方培训文档《Loading Large Bitmaps Efficiently》中的示例实现
 * （https://developer.android.com/topic/performance/graphics/load-bitmap），
 * 原上游项目（saiga006/message-finder, GPL-3.0）即已引入，用于避免引导页大图在低端机上 OOM。
 * 使用场景：ViewPager 每页的圆形图标；用途：把 3 张大图按屏幕密度降采样后再进内存。
 * 本次仅补充来源标注，未改动算法逻辑。
 */
```

另核实：`de.hdodenhof:circleimageview:3.1.0` 是 Apache-2.0 第三方库，通过 Gradle 依赖引入（非代码拷贝），
`build.gradle` 已声明，符合规范。上游项目本身为 GPL-3.0，`LICENSE` 已随仓库保留。

**结论：除上述 1 处外，其余借鉴均有标注；无未声明的代码级摘抄。**

---

## 7. 维度七：测试方案

### 7.1 对前一版验证方案的复核

前一版 §8 给了设备侧验证步骤，方向正确，但**缺 4 个关键场景**，这些恰好对应本轮发现的缺陷：

| 缺失场景 | 对应缺陷 | 已补入 |
|---|---|---|
| 从 v4.0.0/v3 覆盖安装后是否仍双重响铃 | §1.1 | `BUILD.md` 未覆盖（需旧版 APK），列入 §11 待办 |
| 点「停止」后锁屏，**不应**弹出「提醒已结束」全屏页 | §1.2 | ✅ |
| 单轮时长选「一直响」→ 点停止 → 铃声必须立刻停 | §1.3 | ✅ |
| 全屏告警页旋转屏幕，铃声**不应**从头开始 | §1.5 | ✅ |

### 7.2 补齐后的可落地验证路径

已写入 `BUILD.md` §7「快速自检」8 步，按"从内到外"排序，任一步失败都能定位到具体环节：

1. 规则演练（只验匹配，不碰通知/铃声）
2. 保存规则 → 状态条变「监听中」
3. 真机发短信 → 通知 + 响铃 + 震动
4. 等待间隔 → 第二轮 + 文案变「已提醒 N 次 · M 分钟后」
5. 点停止 → 铃声立停 + 通知消失 + 状态栏闹钟图标消失
6. 点稍后 → 静音后再响
7. 锁屏发短信 → 全屏页（只有横幅 = FSI 权限没给）
8. 命中记录 → 有记录才说明规则生效

### 7.3 自动化测试

- 现有 5 个测试类、53 个 `@Test`，全部为纯 JVM（`RoundPolicy` / `KeywordMatcher` / `PhoneNumbers` / `QuietHours`）。
- 可执行命令：`gradlew.bat :app:testDebugUnitTest`（不需要手机、不需要完整构建）。
- **未覆盖**：Android 组件层（`AlertDispatcher` / `NotificationHelper` / 服务）无 Robolectric 测试。
  补这一层需要引入 `robolectric` 依赖（约 4 MB + 需下载 android-all），对个人自用项目收益有限，**本轮不加**，列入 §11。

---

## 8. 本轮修改文件清单（15 个）

| # | 文件 | 类型 | 修改要点 |
|---|---|---|---|
| 1 | `NotificationHelper.java` | 逻辑/安全 | 静音渠道换新 ID + 清理 v3 旧渠道；`build()`/`post()` 增加 FSI 开关；`ContextCompat.getColor`；`getApplicationContext` 判空 |
| 2 | `AlertDispatcher.java` | 逻辑/健壮 | `postRound` 传 FSI=true、`postQuiet` 传 false；`requestServiceStop` 增加 `stopService` 兜底 |
| 3 | `SMSForegroundService.java` | 逻辑 | `startForeground` 通知显式 FSI=false |
| 4 | `AlertActivity.java` | 逻辑/合规 | 仅首次创建启动服务；移除 `requestDismissKeyguard`（与"挡住你"的设计目标冲突，AOSP 也不这么做）；清理 import |
| 5 | `SettingsActivity.java` | 逻辑 | Fragment 只在 `savedInstanceState == null` 时添加 |
| 6 | `AppConfig.java` | 健壮 | `readInt()` 的 `getString` 纳入 try |
| 7 | `AlertScheduler.java` | 健壮 | `getApplicationContext` 判空（两处） |
| 8 | `SliderAdapter.java` | 健壮/溯源 | `getCount()` 取最小长度；安全索引；`LayoutInflater` 兜底；补官方示例来源标注 |
| 9 | `MainActivity.java` | 规范/健壮 | 新增 `findEditText()`；三处判空；`onResume` 中 super 提前 |
| 10 | `MyDialogFragment.java` | 规范 | 平台 `AlertDialog` → `androidx.appcompat.app.AlertDialog` |
| 11 | `SMSReceiver.java` | 规范 | 去重字段 `volatile` + 修正错误注释 |
| 12 | `AndroidManifest.xml` | 兼容 | `AlertActivity` 补 `showWhenLocked` / `showOnLockScreen` / `turnScreenOn` / `configChanges` |
| 13 | `gradle/wrapper/gradle-wrapper.jar` + `gradlew` + `gradlew.bat` | 可移植 | 升级到官方 8.13 引导器（原为 4.6） |
| 14 | `app/build.gradle` + 两个 `strings.xml` | 版本 | `versionCode 19` / `versionName 4.0.1` / 设置页版本号 |
| 15 | `.gitignore` + `BUILD.md` | 工程化 | 新增（含"wrapper jar 必须入库"的显式说明、环境要求、报错对照、8 步自检） |

> `activity_alert.xml` 亦有 1 处表意优化（见 §1.12），不计入"缺陷修复"。

---

## 9. 与前一版报告的结论冲突（逐条）

| # | 前序表述 | 实际情况 | 处理 |
|---|---|---|---|
| 1 | 「双渠道分层已解决通知音 + MediaPlayer 双重响铃」 | 新装用户成立；**从 v3 升级的用户不成立**（渠道 ID 复用，声音不可变） | 换新 ID，见 §1.1 |
| 2 | 「FSI 已做权限自检，未授予时退化为 HUN」 | 权限检测正确，但**未区分该不该带 FSI**，导致状态通知误弹、与服务互相触发 | 增参数，见 §1.2 |
| 3 | 「`requestServiceStop` 捕获异常即可，不影响正确性」 | 在"一直响"模式下影响严重（铃声无法终止） | 加 `stopService` 兜底，见 §1.3 |
| 4 | 「Gradle Wrapper 已从 4.6 升级到 8.13」 | 只改了 `gradle-wrapper.properties`，**jar 仍是 4.6** | 替换 jar + 启动脚本，见 §1.11 |
| 5 | 「`AlertActivity` 用 `requestDismissKeyguard` 防止被锁屏盖住」 | 该 API 会弹「解锁以继续」凭据界面，与"必须点停止才能继续用手机"的设计目标相反；AOSP DeskClock 只用 `setShowWhenLocked` + `setTurnScreenOn` | 移除该调用，改由 manifest 属性兜底，见 §1.6 |
| 6 | 「告警页 `Space` 的 weight 需要修」 | 复核后 `fillViewport="true"` 已保证生效，**原写法无 bug** | 仅做表意优化，如实标注，见 §1.12 |

---

## 10. 校验结果（本轮）

全部由脚本执行，非人工目测：

```
== 1. XML 良构 ==            PASS   （34 个 XML + AndroidManifest 全部可解析）
== 2. 资源统计 ==            string 154 / color 31 / style 14 / string-array 8
== 3. Java R 引用 ==         PASS   （0 缺失；android.R 前缀已排除误报）
== 4. XML 内引用 ==          PASS   （0 缺失；库内置样式已排除）
== 5. 布局 id ↔ findViewById == PASS （layout 定义 40，Java 使用 30，全部有定义）
== 6. 中英文字符串对称 ==    PASS   （en 154 / zh 154，双向差集为空）
== 7. 格式化占位符 ==        PASS   （每个 getString 的实参数 == 占位符数，中英各验一遍）
== 8. Manifest 组件类 ==     PASS   （activity/service/receiver 均能找到对应 .java）
== 9. Java 结构配平 ==       PASS   （27 个文件，注释/字符串剔除后 {} 与 () 均配平）
```

第 7 项是本轮新加的校验维度，**前一版报告没有做**。它防的是 `IllegalFormatException`——
一种只在特定分支（例如"第 2 轮稍后提醒"）才触发、且会让通知构建直接失败的运行期崩溃。

---

## 11. 遗留风险与未完成项（如实列示）

### 11.1 ~~未编译（最高风险，未消除）~~ → **已消除，见 §12**

> **本节结论已被 §12 推翻。** 撰写 §11 时生成环境没有 JDK / Android SDK，
> 因此当时判断"无法证明能编译通过"。随后接入 GitHub Actions，
> 已在 GitHub 的 Ubuntu runner（JDK 17 + Gradle 8.13 + AGP 8.11.1 + platform 36）
> 上**实际编译出 APK 并跑通全部单元测试**。保留原文以示结论演进：

生成此仓库的环境**没有 JDK、没有 Android SDK**，因此：

- ~~未执行 `gradlew :app:assembleDebug`，**无法证明能编译通过**~~ → 已执行并通过；
- ~~未运行任何单元测试（虽然它们只是纯 Java）~~ → 已运行，且**抓到 1 个静态校验发现不了的缺陷**；
- 未在真机（红米 K60 Pro / HyperOS）上验证 → **仍需你完成**（§11.4）。

静态校验能覆盖的是"资源是否齐全、id 是否对得上、占位符是否匹配、结构是否配平"，
**覆盖不到**：Java 语法细节、泛型推断、依赖解析、以及**运行时逻辑**。
后者的缺口正是 §12.2 那个缺陷的来源。

### 11.2 无法用代码根治的架构性限制

- **通知权限被拒 = 完全静默**。`targetSdk 31+` 下不能从后台起服务，通知是唯一唤醒入口；
  用户拒绝 `POST_NOTIFICATIONS` 或关闭通知总开关后，应用没有任何办法发出声音。
  已做的缓解：状态条红字、权限体检、`post()` 明确日志。**这必须靠用户配置解决**。

### 11.3 可选优化（本轮刻意不做）

- `androidx.legacy:legacy-support-v4` → 换成 `androidx.viewpager:viewpager:1.0.0`（减体积，需编译验证传递依赖）。
- 关闭 `android.enableJetifier`（AGP 8 下通常不再需要，但 `circleimageview` 需验证）。
- 为 Android 组件层补 Robolectric 测试（需新增约 4 MB 依赖）。
- `AlertSession` 的 `commit()` 迁到后台线程（需处理跨进程可见性，收益小于风险）。

### 11.4 需要在真机上确认的 4 个点

1. 覆盖安装（旧版 → v4.0.1）后是否**只**响一次（对应 §1.1）。
2. 锁屏 → 点通知「停止」→ 屏幕锁上后是否**不再**弹全屏页（对应 §1.2）。
3. 单轮时长选「一直响」→ 播放中强制停网络、锁屏、再解锁 → 点停止 → 铃声是否立刻停（对应 §1.3）。
4. 全屏告警页旋转屏幕 → 铃声是否连续（对应 §1.5）。

---

## 12. CI 实证：从「无法编译」到「产出可安装 APK」

### 12.1 为什么必须上 CI

§11.1 原本把「未编译」列为最高风险。这个风险靠读代码永远消不掉——它属于
"只能通过执行来证伪"的类别。本机无 JDK / Android SDK，且网络无法下载 1 GB 工具链
（直连 GitHub 21 秒超时、经代理 502），因此改为把编译交给 GitHub Actions 的免费
Ubuntu runner。

工作流 `.github/workflows/build-apk.yml` 的设计取舍：

| 选择 | 原因 |
|---|---|
| `gradle/actions/setup-gradle` 指定 8.13 | `gradle-wrapper.properties` 里是腾讯镜像，GitHub 机器访问不稳定；显式指定版本直接走 services.gradle.org |
| 先跑 `testDebugUnitTest` 再 `assembleDebug` | 单测失败时**不应该**产出包，否则等于把未验证的二进制递给用户 |
| `sdkmanager` 那步加了 `|| true` | AGP 能自行下载缺失的 platform；这一步只为缩短排查路径，不应成为失败点 |
| 产物用 `upload-artifact` | APK 不进 git（二进制不该进版本库），但每次构建都可回溯下载 |

### 12.2 首次运行就抓到第 13 项缺陷（静态校验完全看不见的那一类）

第一次构建的结果是：**编译全部通过，单测 1 条失败**。

```
PhoneNumbersTest > isValidInput_rejectsBadInput FAILED
```

这不仅消掉了"能不能编译"的疑问，还暴露了一个我在 §1 逐行复审时**同样漏掉**的问题。
复盘它为什么难发现：

- `isValidInput()` 的写法看起来是自洽的：先 `stripSeparators()`，再判长度 5–15；
- 缺陷藏在 `stripSeparators()` 的**语义**里——它的既定职责是"丢弃一切非数字字符"，
  这对解析**真实短信发送方号码**是正确的容错（ROM 可能给出 `+86 138-0013-8000`）；
- 但它同时被当成了**校验器的前端**。于是

  | 用户输入 | `stripSeparators` 之后 | 旧判定 | 实际后果 |
  |---|---|---|---|
  | `1380013800a` | `1380013800` | **合法** | 字母被静默吞掉 |
  | `138 0013 8000 转 1234` | `138001380001234`（15 位） | **合法** | `canonical()` 再取末 11 位 → `00138001234`，**变成另一个号码** |
  | `13800138000,1234` | `138001380001234` | **合法** | 同上 |

- 关键一环：`MainActivity.saveConfig(contactRaw, keywords)` 保存的是**用户原始输入串**，
  不是规范化结果。所以这不是"宽松校验"，而是
  **校验通过 → 配置被静默改写 → 用户以为配了 A 号码，实际匹配 B 号码 → 短信永远不响，
  且界面上一切正常，无从排查**。对"漏一条关键短信就会出事"这个产品目标来说，
  这是最坏的一类失败：静默、且伪装成正常。

修复（`PhoneNumbers.java`）：`isValidInput()` 增加字符白名单，字母 / 汉字 / 逗号直接拒绝；
并限制 `+` 至多一个且必须出现在所有数字之前（否则同样会被 `stripSeparators` 丢弃，
属于同一种静默改写）。`stripSeparators()` 与 `canonical()` **保持原容错行为不变**——
它们服务于解析而不是校验，宽松是对的。**校验器的职责是拒绝，不是替用户改。**

测试同步补 6 条断言，把该缺陷钉住：汉字分机号、通讯录逗号分机、`+` 不在首位、
多个 `+`、全角括号（应接受）、点号分隔（应接受）。

> **这一条要单独说明**：我在 §1 声称"逐行复审完成、发现 12 处缺陷"，但这一处是 CI
> 抓出来的第 13 处。这说明逐行阅读有明确的能力边界——纯函数的**组合语义**
> （容错函数被复用作校验器）靠读单个函数看不出来，靠断言才看得出来。
> 这也是为什么 §7.3 坚持要真跑测试，而不是"静态校验全绿就算通过"。

### 12.3 修复后的结果

第二次运行（commit `1cb2550`）：

```
Run JVM unit tests   completed  success
Assemble debug APK   completed  success
Upload debug APK     completed  success
```

### 12.4 对产出 APK 的实测核验（不是"CI 说成功"就算数）

拿到 artifact 后，我直接解析了 APK 内的**二进制 `AndroidManifest.xml`**
（手写 AXML 解析：字符串池 + START_ELEMENT 属性表），确认打出来的包就是它该有的样子：

| 项 | 实测值 | 期望 |
|---|---|---|
| `package` | `com.saiga.find.messagefinder` | 与原工程一致，覆盖安装不丢配置 ✓ |
| `versionCode` | `19` | v4.0.1 ✓ |
| `versionName` | `4.0.1` | ✓ |
| `compileSdkVersion` | `36` | ✓ |
| `targetSdkVersion` | `36` | 本轮唯一重大破坏性变更 ✓ |
| `minSdkVersion` | `23` | 与原工程一致 ✓ |
| `allowBackup` | `false` | ✓ |
| `usesCleartextTraffic` | `false` | ✓ |
| `enableOnBackInvokedCallback` | `true` | 预测式返回 ✓ |
| `dataExtractionRules` / `fullBackupContent` | 均已挂上 | ✓ |

组件导出状态（安全面）：

```
activity  SettingsActivity       exported=false
activity  Onboarding             exported=false   ← v3 的裸 MAIN 入口已移除
activity  MainActivity           exported=true     ← 需要 LAUNCHER
activity  AlertActivity          exported=false
receiver  SMSReceiver            exported=true     ← 系统广播必须
receiver  RepeatAlarmReceiver    exported=false
receiver  AlertActionReceiver    exported=false
service   SMSForegroundService   exported=false
```

权限（只列本应用自己声明的）：

```
FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
RECEIVE_SMS, VIBRATE, READ_CONTACTS, WAKE_LOCK,
POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT
```

包体：9,633,420 字节，987 个条目，3 个 dex，v1 签名（`META-INF/CERT.RSA`）
+ AGP 自动追加的 v2/v3，`Created-By: Android Gradle 8.11.1`，
`zipfile.testzip()` 无损坏。

> 顺带解释了为什么是 3 个 dex：`minSdk 23` 下 debug 构建不做 R8 裁剪，
> `material` / `appcompat` / `preference` 全量进包。这是 debug 包的正常形态，
> 不代表最终发布体积。

### 12.5 APK 在哪

- 仓库页面：**Actions → Build debug APK → 最近一次成功运行 → Artifacts → `bellgram-debug-apk`**
  （需要登录 GitHub，因为仓库是 private；Artifact 保留 30 天）
- 本地已导出一份：`..\bellgram-v4.0.1-debug.apk`（工程目录上一级）

安装到红米 K60 Pro 后，**必须先做完 §11.4 的 4 条真机确认**，
并按 README「HyperOS 保活四步」逐项放行。CI 能证明的是"能编译、单测通过、
包的身份正确"，**证明不了"在你的 MIUI/HyperOS 上能可靠响铃"**——后者只有真机能验证。

### 12.6 以后改代码怎么出包

改完直接 push 到 `main`，Actions 自动跑测试并产出新 APK；也可以手动
`Actions → Build debug APK → Run workflow` 触发。
本地有 Android Studio 的话，`gradlew.bat :app:assembleDebug` 效果相同，
产物在 `app/build/outputs/apk/debug/app-debug.apk`。
