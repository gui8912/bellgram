# 铃达 / Bellgram v4.3.0 独立复审报告（REVIEW_REPORT_V8）

> **复核基线**：`main@0a222a6`（v4.2.0 发布提交）
> **复核对象**：工作区中尚未提交的 v4.3.0 改动（44 个已跟踪文件修改 + 21 个新文件），
> 以及本轮复核自己引入的修复。
> **立场**：**不默认认可 v4.2.0 全量复审的结论，也不默认认可 v4.3.0 开发过程中「静态检查已通过」的判断。**
> 三项一致性脚本只验证「资源引用是否存在」，无法发现配置契约错配、脏值兜底、状态机误判、
> 版本控制层面的文件损坏。本轮以逐行阅读 + 交叉验证 + 反例构造为主，脚本只作为辅证。
>
> **结论摘要**：未发现 P0 编译阻断；发现并修复 **4 项确定性缺陷**、完成 **1 项可复现性加固**；
> 排除 **8 项误报**；另有 **7 项已确认但不修**，逐条给出理由并转入文档 / 真机验收清单。

---

## 1. 前置调研小结：成熟提醒类软件的设置项对照

本轮用户先后要求「参考手机操作系统关于闹钟的功能设置」「参考 SmsForwarder 的功能设置」「借鉴其他成熟提醒软件」。
调研对象与取舍如下（只比较**设置项的组织方式和语义**，不复制任何第三方代码）：

| 来源 | 值得借鉴的设置项 | 本项目是否落地 | 决策理由 |
|---|---|---|---|
| Google Clock / AOSP DeskClock | 响铃时长（Silence after） | **已落地**（v3 起有，v4.3 补自定义输入） | 与「未处理就一直吵」直接相关 |
| 同上 | 贪睡时长（Snooze length） | **已落地**（v4.3 新增 `snooze_minutes`） | 原先写死 5 分钟，用户明确要求可配 |
| 同上 | 渐强音量（Gradually increase volume） | **已落地**（v4.3 新增，默认关） | 直接回应「会不会太吵」 |
| 同上 | 音量按钮行为（无操作 / 稍后 / 停止） | **已落地**（v4.3 新增，默认无操作） | 系统闹钟的通行交互，只在全屏告警页生效 |
| 同上 | 闹钟音量百分比 | **已落地并改进**（`0/40/60/80/100`，默认 80） | 用户原话「60%、80% 下拉列表这样来设置」；额外加「跟随系统」 |
| 同上 | 本地铃声选择、铃声库 | **不落地** | 需新增存储/媒体权限与文件选择器，偏离「自用、零新权限」原则 |
| 同上 | 就寝时间 / 就寝提醒 | **不落地** | 与短信触发无关；本项目已有夜间静音时段覆盖相近诉求 |
| MIUI / HyperOS 时钟 | 忽略电池优化、锁屏显示、响铃时翻面静音 | 部分落地 | 电池白名单已在权限体检；「翻面静音」需传感器常驻监听，收益低 |
| Alarm Clock Xtreme | 数学题才能停止、逐步增强音量、防误关 | 仅借鉴「逐步增强」 | 本场景是「别漏掉关键短信」，加摩擦反而可能让用户错过 |
| **SmsForwarder** | 转发功能总开关 | **已落地**（`alert_enabled`） | 一键暂停，语义清晰、零权限 |
| 同上 | 过滤规则 / 排除关键词 | **已落地**（`exclude_keywords`，逗号或顿号分隔） | 营销、验证码广告类短信最有效的降噪手段 |
| 同上 | 转发日志 | 不新增 | 本项目已有「命中记录」，语义重合 |
| 同上 | 正则匹配、SIM 卡槽选择、多通道转发、远程控制 | **明确不落地** | 需要网络权限 / 规则引擎 / 后端，会把「本地关键词提醒」变成联网转发工具，偏离原定位 |
| 短信铃铛 | 锁屏全屏告警、匹配模式、夜间静音 | v3–v4 已落地 | 保持既有行为，本轮不动 |

**取舍原则**：只引入「不新增权限、不新增依赖、不改变提醒主链路」的设置项。
本轮新增依赖数 = **0**，新增权限数 = **0**。

---

## 2. 项目理解与本轮范围

原始工程是 2018 年的 `PriorityAlerts / message-finder`，定位是「关键词匹配短信并提醒」。
经过 v3–v4.2 迭代，现在的实际定位是：

> 在红米 K60 Pro / HyperOS 上，把**含特定内容的短信**变成**闹钟级提醒**：
> 锁屏整页告警 + 响铃震动 + 未处理则每 5 分钟重复，直到用户点「停止」。

主链路（v4 架构，本轮**完全未改动**）：

```
SMS_RECEIVED → 号码/关键词过滤 → 带闹铃音的高优先级通知（全屏 Intent）
  → AlertActivity（用户可见，此处启动 FGS 才合法）→ SMSForegroundService 单轮播放
  → AlarmManager.setAlarmClock() 排下一轮 → RepeatAlarmReceiver → 回到通知
```

本轮用户的 5 项诉求与落点：

| # | 用户诉求 | 落点 |
|---|---|---|
| 1 | 用提供的新图做应用图标 | 全套 legacy / round / adaptive 图标 + 品牌图 + 暗色引导页背景 |
| 2 | 美化前端界面 | 主界面重排、语义色板、9 个矢量图标、设置页分组与图标 |
| 3 | 明亮 / 暗色两套主题 | DayNight 父主题 + `values-night` + `drawable-night` + `BellgramApp` 冷启动应用 |
| 4 | 「最大音量」太吵，改成 60%/80% 下拉，可自动选 | `VolumePolicy` 档位，默认 80%，`0 = 跟随系统`；另加渐强音量 |
| 5 | 响铃时长 / 重复间隔 / 最多次数保留默认值、可手工设置 | 默认值一率不动，三项加「自定义…」输入 + 范围校验；另加稍后提醒时长、音量键行为 |

**明确保持不变的部分**：通知驱动架构、`setAlarmClock` 语义、两个通知渠道 ID、
固定签名 keystore、权限清单、`minSdk 23 / targetSdk 36`、`ring_duration=60 / repeat_interval=5 / max_repeat=3` 默认值。

---

## 3. 独立复核：方法与发现

### 3.1 复核方法

1. 重跑三项 CI 静态检查，但**不以「通过」作为结论**。
2. 逐行阅读本轮 12 个改动的 Java 文件 + 3 个新增 Java 文件，重点走查：
   配置读写契约、脏值兜底、状态机分支、异步回调生命周期、主题切换与 Activity 重建。
3. 交叉验证四类「脚本查不到」的契约：
   - `AppConfig` 硬编码键 ↔ `app_preferences.xml` 的 `@string/*_key` **字面值**；
   - `arrays.xml` 的 `entryValues` ↔ `VolumePolicy` / `AppConfig` 的白名单与钳制范围；
   - `getString(res, args...)` 的占位符数量 ↔ 中英两份 strings 文案；
   - AndroidX Preference 默认 SharedPreferences 文件 ↔ `AppConfig` 读取的文件。
4. 构造反例：未知 action、哨兵值 `custom` 被写入、脏配置、长按按键、并发投递、进程被杀。
5. 对文件做**字节级**检查（NUL / 行尾 / 二进制判定），这是前几轮复审的盲区。

### 3.2 确定性缺陷清单（全部已修）

| # | 级别 | 文件 | 现象 | 根因 | 修法 |
|---|---|---|---|---|---|
| **D1** | **P1** | `SMSReceiver.java` | git 把 Java 源码判为二进制：`Bin 8226 -> 8942 bytes`，diff 完全不可读，`.gitattributes` 的行尾规范化对它也失效 | 第 174 行注释里写 `'\0'` 时被写成了**真实的 NUL 字节 (0x00)**，而非两个 ASCII 字符。v4.2 生成报告时踩过同类坑，但没有回头检查源码本身 | 字节级替换为字面 `\0`；全仓扫描确认除铃声 mp3（正常二进制）外无其它 NUL。提交后该文件恢复为文本 diff |
| **D2** | **P2** | `SettingsActivity.java` | 「一键暂停全部提醒」只拦新短信；用户关掉总开关后，已由 `setAlarmClock` 排好的下一轮**仍会照常响** | `isAlertEnabled()` 只在 `SMSReceiver.handleSms()` 入口读取，没有触碰已存在的会话 | 给 `alert_enabled` 挂 `OnPreferenceChangeListener`，关闭时调用幂等的 `AlertDispatcher.stop()`：取消闹钟、结束会话、撤通知、停服务。该方法完全不读本配置，无 persist 时序竞态 |
| **D3** | **P2** | `SettingsActivity.java` | 自动联想开关（存在独立 `Settings` preference 文件、`persistent=false`）与其它布尔偏好共用一条分支 | `onPreferenceChange` 把「任何 `Boolean` newValue」都当成自动联想，一旦以后给别的 SwitchPreference 挂同一 listener，就会静默写错键 | 按 `R.string.auto_suggestion_key` 精确限定 key |
| **D4** | **P2** | `AlertActivity.java` | ① `else` 兜底把任何未知音量键动作都当成「停止」；② 长按音量键连续回调，会反复执行 snooze/stop + `finishSafely()` | 分支写成 `if (snooze) ... else stop`，且未处理 `KeyEvent.getRepeatCount()` | 改为显式 `ACTION_SNOOZE` / `ACTION_STOP` 两个分支，未知值放行给系统；`repeatCount > 0` 直接消费，不重复调度 |
| **A1** | 加固 | `tools/gen_icons_v430.py` | 图标生成脚本写死本机剪贴板绝对路径 `C:\Users\guiker\.workbuddy\clipboard-images\...`，换机器或分享仓库后**无法复现**整套图标 | 一次性开发脚本直接入库，未做路径解耦 | 用户原图作为 `docs/app-icon-source.jpg` 入库；脚本默认读仓库内素材，也支持命令行传入自定义图 |

> D1 值得单独说明：它不会让 `javac` 一定失败（NUL 落在注释里时，javac 的词法分析会跳过），
> 所以它能在 v4.2.0 的 CI 里「带病通过」。但代价是该文件的所有后续 diff 都变成不可审阅的二进制，
> 自动标注脚本也拿不到行级 diff —— 属于**版本控制层面的实质损坏**，不是可忽略的洁癖问题。

### 3.3 误报排除（复核中一度怀疑、最终证伪）

| # | 怀疑 | 证伪依据 |
|---|---|---|
| M1 | 粗检脚本报 `SMSForegroundService.java` 大括号 `43/42` 不平衡 | 脚本先删 `//` 注释再替换字符串，把 `"android.resource://..."` 里的 `//` 当注释删掉了整行。改用逐字符状态机（先识别字符串再识别注释）重扫 **33 个 Java 文件，全部配平** |
| M2 | `git diff` 仍显示 `SMSReceiver` 为 `Bin` | 这是拿 **index 里含 NUL 的 v4.2 基线**在比；工作区文件已无 NUL。新提交落地后自然恢复文本 diff |
| M3 | `AppConfig` 硬编码键与 XML 的 `@string/*_key` 可能不一致，导致新设置静默失效 | 逐一核对 14 个键：`theme_mode` / `alarm_volume_level` / `crescendo_enabled` / `snooze_minutes` / `volume_key_action` / `alert_enabled` / `exclude_keywords` / 原有 7 项，**全部一致** |
| M4 | AndroidX Preference 写入的文件与 `AppConfig.get()` 读的不是同一个 | 两者都是 `PreferenceManager.getDefaultSharedPreferences()` → `<包名>_preferences`；`auto_suggestion` 单独用 `"Settings"` 文件且有 `persistent=false`，不冲突 |
| M5 | `custom` 哨兵会被写进配置，让 `readInt()` 解析失败 | `onPreferenceChange` 拦截并 `return false`；`ListPreferenceDialogFragmentCompat` 只在 `callChangeListener()` 返回 `true` 时才 `setValue()`。脏配置万一写入，`readInt()` 也会回退默认值 |
| M6 | `getString(R.string.custom_value_invalid, min, max)` 占位符数量不符会崩 | 中英两份都是 `%1$d`/`%2$d`，实参加 2 个；三个摘要与排除词摘要都是单占位符，实参加 1 个 |
| M7 | `theme_mode` 的 `android:summary="%s"` 与 `SimpleSummaryProvider` 冲突 | AndroidX 绑定时 `SummaryProvider.provideSummary()` 覆盖 `getSummary()`，`%s` 只是冗余，不会显示字面量 |
| M8 | 主题切换时 `setValue()` 会再次触发 `onPreferenceChange` 造成递归 | `ListPreference.setValue()` 走 `persistString()` + `notifyChanged()`，不回调 `callChangeListener()`；只有用户在对话框里点选才会 |

### 3.4 已确认但不修（含理由）

| # | 现象 | 不修理由 |
|---|---|---|
| N1 | **音量档位不改变通知的「第一声」**：第一声由 `CHANNEL_ID_AUDIBLE` 自带闹铃音、按系统当前 `STREAM_ALARM` 音量播放，档位在前台服务接手后才生效 | 若在短信广播阶段就改系统音量，**亮屏只出悬浮通知、FGS 根本没启动**时，被改写的系统音量将失去还原入口 —— 这正是 v4.2 用 `AlarmVolumeGuard` 修掉的 P0。为覆盖第一声而重新引入该风险不划算。已写入 README / BUILD，并给出「跟随系统 + 自行调系统闹钟音量」的替代方案 |
| N2 | 强制暗色但系统处于亮色时，冷启动可能闪一帧亮色预览窗口 | 预览窗口由系统在 `Application.onCreate()` **之前**绘制，资源限定符跟随系统配置而非 `AppCompatDelegate`。要彻底消除需引入 API 31+ SplashScreen API 或改启动窗口主题，收益低且抬高 minSdk 兼容成本 |
| N3 | 切换主题会重建设置页，正在打开的输入对话框被关闭 | `AppCompatDelegate.setDefaultNightMode()` 的标准行为；已保存的值不受影响 |
| N4 | 三个数值项的 `android:summary="%s"` 与自定义 `SummaryProvider` 共存 | 见 M7，冗余但无害；删除它属于纯清理，不改是为了保持 diff 最小 |
| N5 | `MyDialogTheme` 用 AppCompat DayNight 父主题搭配平台 `AlertDialog.Builder` | v4.3 只是把 `Light.Dialog.Alert` 换成 `DayNight.Dialog.Alert`，语义未变；这是既有结构，本轮不扩大改动面 |
| N6 | 排除关键词只在新短信到达时判定，不影响已开始的会话 | 过滤规则的正常语义；已开始的会话由「停止 / 稍后提醒」管理 |
| N7 | 不落地 SmsForwarder 的正则、SIM 选择、网络转发、远程控制 | 见 §1 取舍原则：会引入网络权限与后端依赖，把「本地关键词提醒」变成联网转发工具 |

---

## 4. 变更总览

| 分组 | 新增 | 修改 | 关键内容 |
|---|---|---|---|
| 音量策略 | `VolumePolicy.java` | `AppConfig.java`、`SMSForegroundService.java` | 档位白名单、目标音量换算、渐强播放、只在确实写入系统音量时登记守护 |
| 双主题 | `ThemeHelper.java`、`BellgramApp.java`、`values-night/*`、`drawable-night/*`、`values/bools.xml` | `styles.xml`、`values/colors.xml`、`EdgeToEdgeHelper.java`、`AndroidManifest.xml` | DayNight 父主题、语义色板、冷启动应用、系统栏图标明暗；`AlertActivity` 去掉 `uiMode` 拦截以允许跟随主题刷新 |
| 设置页 | — | `app_preferences.xml`、`values/arrays.xml`、`values-zh-rCN/arrays.xml`、`strings.xml`（中英）、`SettingsActivity.java` | 3 个分组、9 个新设置项、3 项自定义输入、主题即时切换、摘要 provider |
| 过滤 | — | `SMSReceiver.java`、`KeywordMatcher.java`、`KeywordMatcherTest.java` | 总开关、排除词 `findExclusion()`、2 条新单测 |
| 告警交互 | — | `AlertActivity.java`、`AlertActionReceiver.java`、`BootReceiver.java` | 可配稍后提醒时长、音量键行为、重启后音量还原 |
| 图标与界面 | 9 个矢量图标、`bg_accent_line.xml`、`docs/app-icon-source.jpg`、全套 `mipmap-*` 替换 | `activity_main.xml`、`page_layout.xml`、`bg_alert_card.xml`、`ic_baseline_settings_24.xml`、`docs/app-icon.png`、`drawable/app_logo.webp` | 用户指定图标、主界面品牌行、语义色替换 |
| 构建与工具 | `tools/*_v430.py`、`VolumePolicyTest.java` | `app/build.gradle`、`.gitattributes`（本轮加固） | `versionCode 24 / versionName 4.3.0`；零新依赖、零新权限 |
| 文档 | `REVIEW_REPORT_V8.md` | `README.md`、`BUILD.md` | 功能表、项目结构、已知限制、版本表、自检清单 |

---

## 5. 分点变更详情（改前 → 改后 → 理由 → 风险）

### 5.1 音量：从「一律最大」到「可选档位」

| 位置 | 改前 | 改后 | 理由 | 风险 |
|---|---|---|---|---|
| `AppConfig.ring_enabled_on` 文案 | "…at maximum volume" | "…at the chosen volume" | 界面不能再承诺已被移除的行为 | 无 |
| `SMSForegroundService.startAlarmPlayback()` | `setStreamVolume(STREAM_ALARM, getStreamMaxVolume(), 0)`，无条件 | 只有 `!isVolumeFixed() && VolumePolicy.shouldChangeSystemVolume(level)` 才写；目标值 `Math.round(max * level/100)` 且至少 1 格 | 用户明确说「会不会太吵了」，要求 60%/80% 下拉 | 档位 0 时**绝不**写系统音量，也绝不置 `volumeBoosted=true`，否则 `AlarmVolumeGuard` 会误恢复 |
| `AlarmVolumeGuard.remember()` 调用点 | 提升音量前无条件调用 | 与「确实写入系统音量」放在同一分支内 | 跟随系统档下不产生残留标记 | 已在 `BellgramApp` / `BootReceiver` 补 `restoreIfStale()` 兜底 |
| 固定音量设备 | 无处理 | `targetPlayerVolume = level/100f`，用 `MediaPlayer.setVolume()` 近似 | 这类设备根本不允许改系统流 | 百分比与真机音量条不是严格一一对应，已写入已知限制 |
| 渐强音量 | 无 | `onPrepared()` 后 10 步 × 500 ms，从 25% 升到目标；只调播放器音量 | 系统时钟的成熟做法，深夜不炸耳 | 只在本轮内生效；`ringWindowOpen=false` 或 `mediaPlayer==null` 时自动停，`cancelRingTimeout()` 会清回调 |

关键片段（`VolumePolicy.java`，纯 Java 可单测）：

```java
public static boolean shouldChangeSystemVolume(int level) {
    return sanitize(level) > FOLLOW_SYSTEM;
}

public static int targetStreamIndex(int maxVolume, int level) {
    int safe = sanitize(level);
    if (maxVolume <= 0 || safe <= FOLLOW_SYSTEM) {
        return 0;
    }
    long index = Math.round(maxVolume * (safe / 100.0d));
    return (int) Math.max(1, Math.min(index, maxVolume));
}
```

### 5.2 双主题

| 位置 | 改前 | 改后 | 理由 | 风险 |
|---|---|---|---|---|
| `styles.xml` | `Theme.MaterialComponents.Light.*.Bridge` / `Theme.AppCompat.Light.Dialog.Alert` | `Theme.MaterialComponents.DayNight.*` / `Theme.AppCompat.DayNight.Dialog.Alert` | 亮暗两套主题的前提 | `MyDialogTheme` 见 N5 |
| `colors.xml` | 硬编码色值散落；`primaryTextColor` 指向 `@color/app_logo_background` | 新增 `accent_color` / `text_primary` / `card_stroke` 等语义色；`primaryTextColor` 固定白色 | 暗色下按钮文字曾被一起变深，对比度不足 | 需 `values-night/colors.xml` 覆盖全部同名键（已核：37 个 color，night 无孤立键） |
| `BellgramApp` | 不存在 | `Application.onCreate()` 里 `ThemeHelper.applySavedMode()` + `restoreIfStale()` | 主题必须在任何 Activity 的 `super.onCreate()` 前生效 | 见 N2 |
| `AndroidManifest` | 无 `android:name`；`AlertActivity.configChanges` 含 `uiMode` | 声明 `.BellgramApp`；移除 `uiMode` | 让告警页能随主题刷新 | 主题切换会重建告警页，但服务独立存活且 `handleStartAlert` 有本轮幂等，不会重播 |
| `EdgeToEdgeHelper` | 只处理 insets | 依 `@bool/light_system_bars` 设置状态栏 / 导航栏图标明暗 | 暗底上深色图标看不见 | `values/bools.xml=true`、`values-night=false`，`recreate()` 后会重读 |
| 启动窗口 | `splash_drawable` → `background_gradient` | 新增 `drawable-night/background_gradient.xml` 同名覆盖 | 避免暗色冷启动大面积白底 | 见 N2 |

### 5.3 设置页

| 位置 | 改前 | 改后 | 理由 | 风险 |
|---|---|---|---|---|
| 分组 | 平铺 | 权限体检 / 外观 / 提醒方式 / 重复与稍后 / 实验功能 / 用户数据 | 设置项从 10 个增到 19 个，不分组找不到 | 无 |
| 数值三项 | 只能选预设 | 预设不变 + `custom` 哨兵 + `MaterialAlertDialogBuilder` 数字输入 | 用户要求「最好还可以手动设置」 | 范围与 `AppConfig` 钳制严格一致：响铃 0–120、间隔 0–60、次数 0–30；越界拒绝并提示 |
| 摘要 | 官方 `SimpleSummaryProvider` | 数值项改自定义 provider，预设显示原条目、任意值显示「自定义：N 单位」 | 官方 provider 遇到不在 `entryValues` 里的值会返回 null，摘要空白 | 见 M6，占位符已核 |
| 主题切换 | 无 | `setValue()` → `setDefaultNightMode()` → `recreate()`，返回 `false` | 避免框架 persist 与重建的时序竞态丢值 | 见 M8 / N3 |
| 布尔项分支 | 任何 `Boolean` 都写自动联想 | 按 key 精确限定 | **D3** | 无 |
| 总开关 | 无 | 关闭时 `AlertDispatcher.stop()` | **D2** | `stop()` 幂等，无会话时是空操作 |

### 5.4 过滤与告警交互

| 位置 | 改前 | 改后 | 理由 | 风险 |
|---|---|---|---|---|
| `SMSReceiver` | 号码 → 关键词 → 去重 | 最前面加总开关；关键词命中后加排除词判定 | SmsForwarder 的「先命中再排除」 | 排除词为空时返回 `Collections.emptySet()`，`findExclusion()` 短路 |
| `KeywordMatcher` | 只有 `findMatch` | 增 `findExclusion()`，复用归一化解析 | 支持顿号 / 逗号 / 全角半角 / 零宽字符 | 新增 2 条单测 |
| 稍后提醒 | 三处写死 `FALLBACK_SNOOZE_MINUTES = 5` | 三处统一读 `AppConfig.getSnoozeMinutes()`，钳到 `[1,30]` | 用户要求可配 | `RoundPolicy.safeDelayMinutes()` 保留 `configured > 0` 的值，不会被改回 5 |
| `AlertActivity.onKeyDown` | 无 | 三档行为 + 长按去重 + 显式分支 | 系统时钟借鉴 / **D4** | `ACTION_NONE` 时 `super.onKeyDown()`，音量键仍正常调音量 |
| `BootReceiver` | 只恢复会话 | 补 `AlarmVolumeGuard.restoreIfStale()` | 重启后用户还没打开 App 的空档 | 无 |

### 5.5 图标与界面

| 位置 | 改前 | 改后 |
|---|---|---|
| 图标 | v4.1.1 靛蓝铃铛 + 声波 | 用户指定图：蓝色渐变圆角方底 + 白色对话气泡 + 蓝色铃铛 + 左下尾巴；裁掉白边后按 5 档密度输出 legacy(48dp) / round / adaptive background(108dp) / adaptive foreground |
| 自适应前景 | 可能裁切气泡 | 只保留气泡 + 铃铛的透明图层，圆形/方圆形 mask 下都不被切 |
| `activity_main.xml` | 单列旧布局 | 新图标 + 应用名横向品牌行、强调短线、卡片语义色；**保留全部原有 id**，Java 侧 `findViewById` 与 `EdgeToEdgeHelper` 不受影响 |
| `page_layout.xml` | `app:civ_border_color="#33FFFFFF"` 写死 | 改 `@color/onboarding_image_border`，暗色下由 night 覆盖 |
| 矢量图标 | 少数几个 | 新增 9 个 24dp Material 风格图标，`fillColor="@color/icon_tint"` 跟随主题 |

---

## 6. 交付物与源码索引

本轮是**增量版本**，不是从零重写：v4.2.0 的全部 35 个源码/配置文件已由
[`AUDIT_REPORT_V7.md`](AUDIT_REPORT_V7.md) §6 全量拼接；本轮改动与完整源码均以
`gui8912/bellgram` 的 `main` 分支为准，可直接 `git clone` 后用 [BUILD.md](BUILD.md) 构建。

本轮**新文件**（21 个 + 本报告与素材）：

```
app/src/main/java/.../BellgramApp.java          ThemeHelper.java        VolumePolicy.java
app/src/main/res/values-night/colors.xml       values-night/bools.xml
app/src/main/res/values/bools.xml
app/src/main/res/drawable-night/background_gradient.xml  onboarding.webp
app/src/main/res/drawable/bg_accent_line.xml  ic_bell_24.xml  ic_volume_24.xml
  ic_palette_24.xml  ic_snooze_24.xml  ic_repeat_24.xml  ic_vibrate_24.xml
  ic_moon_24.xml  ic_tune_24.xml  ic_shield_24.xml
app/src/test/java/.../VolumePolicyTest.java
tools/gen_icons_v430.py  gen_vector_icons_v430.py  patch_*_v430.py
docs/app-icon-source.jpg
REVIEW_REPORT_V8.md
```

**修改标注约定**：所有新增 / 修改的 Java 代码行带 `// modified`，XML 在合法位置带
`<!-- modified -->`（开始标签的属性之间不能插注释，aapt2 会解析失败），
Python 工具脚本带 `# modified` 或文件头说明。`tools/mark_modified.py` 基于
`git diff --unified=0` 自动补标，本轮补 3 行；本轮新增的 3 个 Java 文件为全文手工标注。
未标注的主要是纯注释行、XML 属性、结构性括号行与 Java `import`，与 v4.2.0 的报告约定一致。

**版本**：`versionCode 24` / `versionName "4.3.0"`；签名 keystore 未变，可直接覆盖安装 v4.1.0+。

---

## 7. 风险提示

1. **唯一真实编译验证在 CI**。生成本仓库的环境没有 JDK / Android SDK / adb，本地只能做静态检查。
   `Theme.MaterialComponents.DayNight.*`、`PreferenceThemeOverlay.v14.Material`、
   `Preference.SummaryProvider<T>` 等 API 的存在性依赖 `material:1.12.0` 与 AndroidX Preference 版本，
   需以 `assembleDebug` 结果为准。
2. **签名一致性未在本报告中预先断言**。需用 v4.3.0 APK 与 v4.2.0 APK 的
   `META-INF/CERT.RSA` 提取 X.509 证书本体比对；**不能只比 `CERT.RSA` 文件哈希**
   （PKCS#7 的签名值随 APK 内容变化，文件哈希不同是正常的）。
3. **系统闹钟音量是持久化设置**。任何「写入但没还原」的路径都会改动用户设备；
   `AlarmVolumeGuard` 在 `BellgramApp` / `MainActivity` / `Service` / `BootReceiver` 四处兜底，
   但仍需真机验证「强杀进程后音量是否还原」。
4. **`0 = 跟随系统` 与通知渠道的关系**见 N1：第一声不受档位控制，已明确写入 README。
5. **主题切换会重建页面**：`AlertActivity` 去掉 `uiMode` 拦截后可能重建，虽有本轮幂等保护，
   仍建议真机验证「响铃过程中系统自动切深色」不会导致重复播放。
6. **总开关关闭会取消当前会话**（D2 的修复行为），属于**行为变更**：
   用户若在响铃时关闭总开关，铃声会立刻停止。这是刻意设计，但需在发行说明里写明。
7. **明文 GitHub token**：此前对话中出现过明文 token，仍建议到
   `https://github.com/settings/tokens` 撤销并改用短期凭据。
8. **未落地项**见 §3.4 N1–N7，均不是待修 bug，而是有意的设计边界。

---

## 8. 验证方案

### 8.1 本地静态（本轮已全部执行）

```bash
python tools/check_view_types.py      # findViewById 类型不匹配 = 0
python tools/check_res_refs.py        # Java / XML 资源引用缺失 = 0
python tools/check_page_ids.py        # 布局里找不到的 id = 0
python tools/mark_modified.py         # 补 // modified 标注
```

辅证结果：

| 检查 | 结果 |
|---|---|
| 三项一致性脚本 | 全部 OK |
| XML 良构性（全部 res XML） | `parse failures: []` |
| Java 括号 / 字符串状态机配平 | 33 个文件，`syntax balance issues: []` |
| 资源统计 | `array 16, bool 1, color 37, string 190, style 13, drawable 34, layout 8, xml 3, menu 1, raw 2, mipmap 2, id 41` |
| 中英 strings 键集合 / 顺序 / 占位符 | EN 190 = ZH 190，格式化串 `%1$d`/`%2$d`/`%1$s` 与实参数量一致 |
| `arrays.xml` entries ↔ values | 16 组键、每组长度一致；中英 `*_values` 完全相同 |
| `values-night/colors.xml` 孤立键 | 0 |
| 源码 NUL 字节全仓扫描 | 仅 `res/raw/priority_ringtone.mp3`（正常二进制）；Java/XML 为 0 |
| 单元测试 | 新增 `VolumePolicyTest` 5 条 + `KeywordMatcherTest` 2 条排除词用例 |

> 出现过的两次「异常」均已定位：粗检脚本的括号误报（M1）、git diff 的 Bin 判定（D1/M2）。

### 8.2 CI（推送后必须确认）

- `:app:testDebugUnitTest` 全绿；
- 三项一致性脚本在 workflow 中执行且退出码为 0；
- `:app:assembleDebug` 成功并上传 APK artifact；
- 下载 artifact 后校验：`versionCode 24`、`versionName 4.3.0`、关键类
  （`BellgramApp` / `ThemeHelper` / `VolumePolicy` / `BootReceiver`）存在、资源里无「铃信」残留、
  X.509 证书本体与 v4.2.0 一致、sha256 记录。

### 8.3 真机验收清单（15 条，红米 K60 Pro / HyperOS）

1. 覆盖安装 v4.2.0 → v4.3.0，号码 / 关键词 / 提醒策略 / 命中记录全部保留。
2. 桌面图标、最近任务、设置页品牌图均为新图标，圆形图标无白角残留。
3. 设置页切「明亮 / 暗色」立即换色；杀进程冷启动后主题保持，系统栏图标可辨。
4. 全屏告警页在两种主题下文字与按钮对比度足够。
5. 音量档位依次设 0 / 40 / 60 / 80 / 100 各触发一次；停止后到系统「声音 → 闹钟音量」核对未被永久改大。
6. 档位 0（跟随系统）时，系统音量条全程无变化。
7. 开启渐强音量：第一声明显轻、约 5 秒内升到位；关闭后一响即到位。
8. 响铃时长 / 重复间隔 / 最多次数选预设与「自定义…」合法值、越界值；越界被拒绝，合法值退出设置页再进入仍保留。
9. 自定义值显示为「自定义：N 单位」，三个摘要不空白、不显示 `%s`。
10. 稍后提醒设为 1 分钟，点「稍后提醒」约 1 分钟后重响（不是固定 5 分钟）。
11. 音量键设为「停止」，锁屏全屏页按音量键立刻停；设为「稍后」会重新排队；设为「不拦截」只调音量。
12. 长按音量键不产生重复提醒或多次关闭页面。
13. 正在响铃或已挂好下一轮时关闭总开关：立刻停铃、撤通知、状态栏闹钟图标消失；再发短信完全不提醒。
14. 排除词「退订」：同时含「到账」与「退订」的短信被忽略，只含「到账」的仍提醒；排除词摘要正确。
15. 重启手机 / 强杀进程后：未处理会话能续上、系统闹钟音量被还原、无重复响铃。

---

## 9. 复核结论

v4.3.0 的新功能**方向正确、契约对齐、默认值未回退**，可以进入 CI 编译与发布。
独立复核额外发现并修复了 4 项静态检查覆盖不到的缺陷，其中 **D1（源码 NUL 字节）**
是前几轮全量复审的盲区，说明「CI 编译通过」并不等于「仓库状态健康」。

**不建议在本轮继续扩大改动面**：N1–N7 均有明确取舍理由，尤其 N1 若强行修会重新引入
v4.2 已经修掉的 P0。剩余不确定性全部收敛到 CI 编译结果与 §8.3 的 15 条真机验收。

---

## 10. 发布收尾的产物级独立复核（v4.3.0 APK）

> §1–§9 复核的是**源码**；本节复核的是**实际交付的二进制**。动机：CI `conclusion=success`
> 只证明编译与单测通过，不证明产物身份正确；既有校验脚本只搜 `versionName` 字符串，
> 没有验证决定能否覆盖安装的整数 `versionCode`。本节不采信二者的结论。

### 10.1 方法

| 手段 | 复核什么 |
|---|---|
| 自写 AXML 二进制解析（字符串池 + `START_ELEMENT` + 属性数组） | `versionCode` / `versionName` / `minSdk` / `targetSdk` / `compileSdk`、application `name` / `icon`、完整权限集合 |
| Pillow 逐像素比对 | APK 内 20 个图标资源 ↔ 仓库 `mipmap-*` 源图 |
| `resources.arsc` / DEX 字节检索 | 8 项新增中文文案、9 个新增方法名是否真的编译进包 |
| `openssl pkcs7 -print_certs` + `x509 -outform DER` / `-pubkey` | 与 v4.2.0 的 X.509 证书本体与公钥逐字节比对 |
| Release asset 回下载 / 二次 CI 产物解压 | 上传后字节一致；纯文档提交后产物是否漂移 |

工具：`certcheck/independent_check_v430.py`（本地辅助脚本，不在仓库内）。

### 10.2 实证结果

| 检查 | 结果 |
|---|---|
| `versionCode` | `24`（v4.2.0 为 `23`，递增 → 可覆盖安装） |
| `versionName` / `targetSdk` / `minSdk` / `compileSdk` | `4.3.0` / `36` / `23` / `36` |
| application | `.BellgramApp`，`android:icon` 为有效资源引用 |
| 权限集合 | 与 v4.2.0 **完全相同**（10 项，零新增权限） |
| 图标 | 5 密度 × 4 类资源 = 20 个，像素与仓库源图**逐一一致** |
| 新增文案 / 方法 | 8 / 9 全部命中 |
| 签名 | X.509 DER sha256 `65ce208f…` 与公钥均与 v4.2.0 相同 |
| APK | 9,877,778 B，sha256 `71b5e068d448b10e91adfef6a969d635fdb31ae427b129c229b52a31ce9d442c` |
| Release | tag `v4.3.0`（id `435951243`），asset id `715414331`，回下载 sha256 一致 |
| CI | run `42135947260`（`449eb81`）与 `42141210658`（`3f1c3b5`）均 success |

结论：**INDEPENDENT RESULT: PASS**。

### 10.3 排除的 3 项误报（第一轮独立脚本自身的缺陷）

1. **「`icon @mipmap/app_logo` 缺失」**：AXML 中 `android:icon` 是资源引用 ID（整数），
   不是字面字符串；改为校验非零引用 ID。
2. **「20 个图标有 16 个 DIFF」**：比对时按固定顺序取第一个同名文件，实际每次都拿
   `mipmap-mdpi` 的 48×48 去比 72 / 96 / 144 / 192；改为按 APK 条目里的密度后缀映射后全部 SAME。
3. **「总是明亮 / Crescendo 文案缺失」**：词表是预设的，真实文案是「主题」「渐强音量」；
   改为从 `values-zh-rCN/strings.xml` 动态读取 8 个新增键，全部命中。

> 这 3 项若直接写进缺陷清单，就会把**核查工具的错误**当成产品缺陷。先证伪工具，
> 再下结论——这与 §3.2 剔除源码误报是同一条纪律。

### 10.4 构建可复现性（变更风险实证）

纯注释 / 配置提交 `3f1c3b5` 触发的 CI run `42141210658` 产物 artifact `13467257990`
解压后的 APK 为 9,877,778 B、sha256 仍为 `71b5e068…442c`，与已发布包**字节完全相同**。
即：aapt 丢弃 XML 注释，本次文档修正不改变二进制，Release 与最新 `main` 无产物漂移。
注意 artifact 的 `digest` 会因 zip 元数据漂移（v4.2.0 两次构建 size 相同但 digest 不同），
**不能用 digest 判断 APK 是否变化**，必须解压后比 APK 自身 sha256。

### 10.5 本轮新发现并处理的 4 个非功能问题

| 问题 | 处理 |
|---|---|
| `mipmap-anydpi-v26` 两个 XML 注释仍写「v4.1.1 靛蓝渐变」 | 更正为 v4.3.0 新图标描述 |
| 9 个矢量图标路径缺少第三方来源标注（核查维度 6） | 生成脚本 docstring 与每个 drawable 注释均标注 Google Material Design Icons / Apache-2.0；仓库内无第三方 Java/Kotlin 源码复用 |
| `.gitattributes` 里 `*.mp3 binary` 重复声明 | 去掉重复项 |
| 发布辅助脚本 socket 超时 120 s，而 8.7 MB 产物实测下载 115–204 s | 放宽到 300 s；下载时 zip 与 APK 同时落盘，避免重复慢下载 |

前 3 项随 `3f1c3b5` 推送；第 4 项是本地发布工具，不在仓库内。
另定位并修正发布链路两处端点差异：Actions artifact zip 端点必须
`Accept: application/vnd.github+json`（沿用 release asset 的
`application/octet-stream` 会返回 415），且 302 到存储端需剥掉 `Authorization`。

### 10.6 对 7 个核查维度的回答（结论速览）

| 维度 | 结论 |
|---|---|
| 1 逻辑正确性 | v4.3.0 的 4 项缺陷已修（§3 / §5），产物级复核 PASS；N1–N7 为有取舍的设计边界，非缺陷 |
| 2 改动合理性 | 新设置全部围绕「别那么吵、但别漏」，参考系统时钟与 SmsForwarder；未引入网络转发 / 正则规则 / SIM 选择 / 远程控制 |
| 3 代码规范 | 命名与注释统一，`// modified` 已补齐，本轮补上第三方来源标注；无新增依赖 |
| 4 兼容性与可移植性 | AGP 8.11.1 / Gradle 8.13 / JDK 17 / API 36 固定，签名 keystore 入库；图标原图入库且生成脚本改用仓库相对路径（无本机绝对路径）；连续两次 CI success、构建可复现 |
| 5 变更风险 | 「档位不改变通知第一声」「强制暗色冷启动闪一帧」「通知权限被拒 = 静默」等已写入 README / Release；本轮新增识别「大产物下载超时」运维风险并已修 |
| 6 代码溯源 | 唯一复用的第三方素材是 9 个 Material 图标路径，本轮补齐 Apache-2.0 来源标注；未复用任何第三方 Java/Kotlin 源码 |
| 7 测试方案 | CI 单测 + 三项静态检查 + `BUILD.md` §7 的 15 条真机清单 + 本节产物级二进制复核；真机部分待用户执行 |

### 10.7 仍未闭环

- 早期对话中出现过明文 GitHub token，建议到 token 设置页撤销并重建最小权限 token。
- 本机无 adb / JDK / Android SDK：锁屏全屏、定时重复、重启恢复、强杀后音量还原
  仍依赖 §8.3 的 15 条真机验收。这是**环境限制**，不是代码结论。

---

## 11. 第二轮独立复审（源码层，v4.3.1）

> §10 复核的是**交付产物**，源码层只有结论速览。本轮按要求**逐文件重读** v4.3.0
> 全部新增/改动链路，不采信前两轮判断，重点查静态脚本与二进制解析都覆盖不到的
> **跨文件契约**与**运行态时序**。

### 11.1 重读范围

| 层次 | 文件 |
|---|---|
| 配置契约 | `AppConfig`（`readInt` 类型兜底 / 新键 / 钳制）、`app_preferences.xml`、中英 `arrays.xml`、`VolumePolicy` |
| 设置交互 | `SettingsActivity`（主题切换、`custom` 哨兵、自定义数值、摘要 Provider、总开关联动） |
| 提醒链路 | `SMSReceiver`（总开关→号码→关键词→排除词→去重）、`AlertDispatcher`（stop / snooze / finish）、`AlertScheduler`、`BootReceiver` |
| 播放与音量 | `SMSForegroundService`（档位 / 渐强 / 恢复 / 收尾）、`AlarmVolumeGuard` |
| 界面与主题 | `AlertActivity`（重建 / 轮次 / 音量键）、`ThemeHelper`、`BellgramApp`、`styles.xml`、`values-night/colors.xml` |
| 资源层 | drawable 硬编码色值、`splash_drawable`、夜间变体、遗留图标引用 |

交叉验证：Java 括号/字符串状态机扫描、XML 良构与注释合法性、三项静态检查、
中英资源键与数组配对、`findViewById` 与布局 id 比对。

### 11.2 七个维度的复核结论

| 维度 | 结论 |
|---|---|
| 1 逻辑正确性 | 主链路与状态机无 P0；新发现 2 项边界缺陷（§11.3），均为「特定时序下行为错误」而非崩溃 |
| 2 改动合理性 | 新功能全部围绕「别那么吵、但别漏」；未引入网络转发、正则规则、SIM 选择、远程控制 |
| 3 代码规范 | 命名/注释统一；本轮更正 `AlarmVolumeGuard` 过时注释；`// modified` 覆盖新增逻辑行 |
| 4 兼容性与可移植性 | 构建链固定、签名入库、无本机绝对路径、图标原图入库；`readInt` 同时兼容 String/Int 存储 |
| 5 变更风险 | 新增 2 条真机验收（§11.6）；N 类边界全部列明理由（§11.5） |
| 6 代码溯源 | 唯一复用素材为 9 个 Material 图标路径（已标注 Apache-2.0）；其余均为自写，注释中注明参考但不复制 |
| 7 测试方案 | 静态检查 + 单测 + 产物二进制复核 + 真机清单；本轮改动的两条时序缺陷已写成可执行的验收步骤 |

### 11.3 本轮新发现并修复的 2 项缺陷

#### D1（P1 边界）主题重建后「已启动轮次」归零，同轮通知会重播已结束的提醒

**触发路径**：告警页显示中 → 系统深色模式随日出日落切换 / 用户改主题 → `manifest` 的
`configChanges` 已移除 `uiMode`，Activity 重建 → `lastStartedRound` 随实例归零 →
服务此时已 `endRound` 停止 → 用户点同一轮通知 → `onNewIntent` 判定
`incomingRound(1) != lastStartedRound(-1)` 成立 → 重新启动服务，**重播已经结束的第 1 轮**。

**改前**
```java
if (savedInstanceState == null) {
    lastStartedRound = AlertSession.get(this).getRound();
    startAlertService();
}
```

**改后**
```java
if (savedInstanceState == null) {
    lastStartedRound = AlertSession.get(this).getRound();
    startAlertService();
} else {
    lastStartedRound = savedInstanceState.getInt(STATE_LAST_STARTED_ROUND, -1);
}

@Override
protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putInt(STATE_LAST_STARTED_ROUND, lastStartedRound);
}
```

**理由**：`savedInstanceState == null` 只解决了「重建时不要主动启动服务」，没有解决
「重建后如何仍知道这轮启动过」。补上状态恢复后，重建前后对同一轮的认知一致。

**风险**：极低。仅新增一个 Bundle 键；老版本恢复的 Bundle 无该键时回退 `-1`，退化为原行为。

#### D2（P2）音量守护恢复失败也清标记，用户原始音量永久丢失

**触发路径**：进程被杀 → 标记里存着原始音量 → 下次启动 `restoreIfStale()` →
`AudioManager` 为空或 `setStreamVolume()` 抛异常 → 原实现在 `finally` 里**无条件清除标记** →
原始音量信息永久消失，之后会把「被钉高的当前值」当成新的原始音量恢复回去。

**改前**
```java
} finally {
    // 无论恢复成功与否都清标记，避免每次启动都写一次系统设置
    p.edit().remove(KEY_SAVED).commit();
}
```

**改后**
```java
boolean restored = false;
try {
    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (am != null) {
        am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0);
        restored = true;
    }
} catch (Exception e) {
    Log.w(TAG, "restore stale alarm volume failed", e);
}
if (restored) {
    p.edit().remove(KEY_SAVED).commit();
}
```

**理由**：保留标记的代价只是「下次启动再试一次」，远小于丢失用户的音量设置。

**风险**：极低。若设备持续无法写系统音量（例如权限被策略管控），标记会一直保留，
每次启动多一次失败的 `setStreamVolume` 调用，不产生写操作以外的副作用。

**另**：`AlarmVolumeGuard` 类注释原写「在 MainActivity.onCreate 与 SMSForegroundService
都调用」，与 v4.3 实际入口（`BellgramApp` / `SMSForegroundService` / `BootReceiver`）不符，已更正。

### 11.4 排除的 6 项误报（复核过程中曾怀疑、验证后不成立）

1. **「排除词不支持中文逗号」**：`normalize()` 把 U+FF0C（，）落在 `FF01-FF5E` 区间，
   减 `0xFEE0` 得 `,`；顿号 U+3001、句号 U+3002 另有单独分支。中文逗号/顿号/句号都能分隔。
2. **「自定义 15 分钟会被改回 5 分钟」**：`snooze` 用 `safeDelay = max(1, 15)`，
   `scheduleNext` 只做 `max(1, delay)`，无上限钳制；`RoundPolicy.safeDelayMinutes`
   仅在「重复间隔为 0」时提供兜底，不覆盖用户主动选的 15。
3. **「ListPreference 存 String 会让 `readInt` 抛 ClassCastException」**：`readInt` 先
   `getString` 再 `parseInt`，并对「键被写成整型/布尔」做了二次兜底。
4. **「主题切换会重播当前轮」**：`onCreate` 已用 `savedInstanceState == null` 挡住主动启动；
   本轮进一步补上「已启动轮次」的恢复，属加固而非新增阻断。
5. **「`android:summary=\"%s\"` 会显示字面 %s」**：7 个 `ListPreference` 全部设置了
   `SummaryProvider`（5 个官方 `SimpleSummaryProvider` + 3 个自定义），会覆盖 XML 文本。
6. **「遗留图标在暗色下不可读」**：`ic_block_24dp` / `ic_contact_phone_black_24dp` 的
   `android:tint` 会覆盖 `fillColor`；通知栏小图标还会被系统强制着色，暗色下仍可辨。

### 11.5 已确认不修（保留设计边界）

| 项 | 理由 |
|---|---|
| `android:summary="%s"` 冗余 | Provider 已覆盖，删除无功能收益，改 XML 反而增加回归面 |
| `cancelRingTimeout()` 用 `removeCallbacksAndMessages(null)` | 该 Handler 目前只挂「响满时长」与「渐强」两类消息，清理偏宽但无实际影响 |
| 会话已停止时全屏 Intent 仍可能拉起告警页 | 页面内容为空但按钮可用，点「停止」幂等关闭；自动 finish 会在「刚停止又闪一下」时更突兀 |
| 双重 `recreate()`（`setDefaultNightMode` + 显式） | 两次都从持久化值恢复，值不丢；配置已生效时第二次通常为 no-op |
| 音量档位不改变通知「第一声」 | 短信广播阶段改系统音量会失去还原入口（v4.2 修掉的 P0），已在 README / Release 标注 |
| 强制暗色 + 系统亮色时冷启动闪一帧 | 系统预览窗口绘制早于 `Application.onCreate()`，属平台限制 |

### 11.6 版本与验证

- `versionCode 24 → 25`，`versionName 4.3.1`，`current_app_version` 同步为 `4.3.1`。
- 改动面：2 个 Java 文件（2 处逻辑 + 1 处注释）与 6 处版本/文档
  （`app/build.gradle`、中英 `strings.xml`、`README.md`、`BUILD.md`、本报告）。
- 三项静态检查、XML 良构、中英资源配对、Java 状态机扫描全部重跑。
- CI run `42142620837`（sha `a07c7bf`）：单测 + 三项静态检查 + `assembleDebug` 全部 success。
- 产物 `bellgram-4.3.1.apk`：9,877,880 B，sha256 `3a5a5f18…f61b`；自写 AXML 解析确认
  `versionCode 25` / `versionName 4.3.1` / `targetSdk 36` / `minSdk 23`，权限集合与
  v4.3.0 完全相同，DEX 含 `last_started_round` 与 `restore stale alarm volume failed`
  两处修复标记，X.509 证书与公钥与 v4.3.0 一致（可覆盖安装）。
- 新增 2 条真机验收（已写入 `BUILD.md` §7）：
  1. 告警页显示中切换主题（或系统深色模式变化）触发重建后，再点同一条通知，**不应**重播已结束的轮次；
  2. 用 100% 档位响铃中强杀进程，重启后系统闹钟音量应回到原值；若首次恢复失败，下次启动仍会再试。
