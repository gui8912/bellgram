# 铃信 / Bellgram v4.0.0 —— Android 16 合规专项审计与架构重构报告

> 报告日期：2026-11-05　|　工程目录：`PriorityAlerts-v3`　|　上游：saiga006/message-finder（GPL-3.0，原版 2.9.8）
> 本轮主题：**让应用符合当前最新的 Android 应用规范**（v3.0.0 → v4.0.0，versionCode 17 → 18）。
>
> **与 `AUDIT_REPORT.md`（v3 报告）的关系**：v3 的 19 项 P1 修复、界面重做、12 项功能仍然有效；
> 但其中 4 项结论被本报告**明确推翻**：① `targetSdk` 保持 29；② 前台服务声明 `shortService`；
> ③ `android.preference` 暂不迁移；④ 重复提醒用 Handler 长驻服务实现。
>
> **唯一重大破坏性变更**（已按你「必要时可重构整个架构」的授权实施完毕）：
> **`targetSdk 29 → 36`**。它使 v3 的主链路在 Android 12+ 上必然失效，因此必须同步做架构级重构。
> 利弊分析与备选方案见 **§3.3**，风险见 **§7**。若你评估后希望回退，改回 `app/build.gradle` 的
> `targetSdk 29` 即可让 v3 链路重新工作（但会重新不符合最新规范）。

---

## 1. 同类方案调研小结

### 1.1 规范基线（2026-11-05 联网核验，非记忆推测）

| 项目 | 当前最新要求 | 核验来源 |
|---|---|---|
| Google Play 目标 API | **自 2026-08-31 起，新应用与应用更新必须以 Android 16（API 36）或更高为目标**；现有应用须 ≥ API 35 才能继续分发给系统版本更高的新用户；延期申请截止 2026-11-01 | `developer.android.com/google/play/requirements/target-sdk`、`support.google.com/googleplay/android-developer/answer/11926878` |
| 构建工具链 | AGP **8.11** 是官方首个支持最高 API 36 的版本；最低/默认 Gradle **8.13**、JDK **17**、Build Tools 35.0.0 | `developer.android.com/build/releases/agp-8-11-0-release-notes` |
| 后台启动前台服务 | targetSdk ≥ 31 时后台不得启动 FGS；豁免清单**不含 `SMS_RECEIVED`**，但包含「从用户可见状态转换」「精确闹钟完成用户请求的动作」「用户与通知交互」「用户关闭电池优化」等 | `developer.android.com/develop/background-work/services/fgs/restrictions-bg-start` |
| 前台服务类型 | targetSdk ≥ 34 必须声明类型 + 对应权限；`shortService` 约 **3 分钟**超时，未及时 `stopSelf()` 会 `onTimeout` 甚至 ANR；`mediaPlayback` 无此短时限，配套 `FOREGROUND_SERVICE_MEDIA_PLAYBACK`（安装即授予） | `developer.android.com/develop/background-work/services/fg-service-types` |
| 全屏 Intent | Android 14+ 只有**通话 / 闹钟类**应用默认获得 `USE_FULL_SCREEN_INTENT`；须用 `NotificationManager.canUseFullScreenIntent()` 自检，用 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 引导用户手动开启 | AOSP `source.android.com/docs/core/permissions/fsi-limits`、`behavior-changes-14` |
| 通知运行时权限 | API 33+ 必须申请 `POST_NOTIFICATIONS`，否则 `notify()` 抛 `SecurityException`（静默失败） | 同上行为变更文档 |
| 边到边显示 | targetSdk 35+ 强制 edge-to-edge；Android 16 已移除 `windowOptOutEdgeToEdgeEnforcement` 临时退出开关 | Android 15/16 行为变更 |
| 设置页 | 平台 `android.preference.*` 自 API 28 废弃，官方要求迁移 `androidx.preference` | AndroidX Preference 迁移指南 |
| 返回键 | API 33+ 预测式返回手势，`enableOnBackInvokedCallback=true` 后 `onBackPressed()` 不再回调，须用 `OnBackPressedDispatcher` | Android 13 行为变更 |

### 1.2 同类项目与成熟方案借鉴

| 参考对象 | 借鉴点 | 本轮落地 |
|---|---|---|
| **AOSP DeskClock（系统闹钟）** | 全屏 Intent 承担唤醒、`setAlarmClock()` 承担重复、闹钟状态持久化；**绝不靠长驻服务等待 5 分钟** | 成为 v4 整体架构原型：通知 → 全屏页 → 单轮服务 → 系统闹钟 |
| **SmsForwarder**（pppscn，高星、活跃维护） | 长短信拼接、规则命中去重、命中先落状态再分发 | `AlertSession` 在发通知前先 `commit()` 会话状态，避免跨进程竞态 |
| **短信铃铛** | 全屏告警页、稍后提醒、规则演练、夜间静音 | v3 已借鉴，本轮全部保留并适配新架构 |
| Android 官方《Notifications / Full-screen intents》 | 高优先级通知 + `setFullScreenIntent` 是「需要立即注意」的唯一推荐唤醒入口 | 主链路改为通知驱动 |
| Android 官方《Schedule alarms》 | `setAlarmClock()` 属用户可见闹钟，**不受 Android 12+ `SCHEDULE_EXACT_ALARM` 权限限制**，且 Doze 下可准时唤醒 | 重复提醒改用 `setAlarmClock()`，遵循最小权限原则，不声明多余权限 |

### 1.3 可借鉴的优秀做法 / 功能改进方向 / 成熟实现思路

1. **把「唤醒」和「播放」拆开**：系统只保证通知与闹钟能被投递，不保证后台服务能活。v4 把通知/闹钟作为唯一可靠触发器，把铃声播放放进用户可见组件。
2. **状态与触发者解耦**：轮次、短信内容写进独立 `SharedPreferences`，进程被杀后闹钟接收器仍能还原完整上下文。
3. **双重调度**：发通知时乐观挂好下一轮闹钟（服务没起来也不断链），服务播完再用同一 `requestCode` 覆盖校正计时起点。
4. **权限自检前置**：把「能不能响」拆成 5 个可检测的开关，集中展示并给出一键跳转，而不是让用户猜。
5. **渠道声音分层**：带闹铃音渠道负责「第一声」，静音渠道负责服务接手后的通知，彻底规避双重响铃。

### 1.4 需要规避的坑（均在本轮代码中做了防御）

| 坑 | 后果 | 防御 |
|---|---|---|
| 误以为 `SMS_RECEIVED` 在 FGS 豁免清单里 | 升 targetSdk 后主链路完全不响 | 主链路彻底不依赖 FGS 后台启动 |
| 用 `shortService` 长驻等 5 分钟 | `onTimeout` / ANR / 应用被系统判违规 | 改 `mediaPlayback` + 单轮退出 |
| `setFullScreenIntent` 硬写不检测 | Android 14+ 无效，部分 ROM 抛异常 | `canUseFullScreenIntent()` 判断后决定是否设置 |
| 通知按钮用 `PendingIntent.getService` | 后台 `startService` 受限，点了没反应 | 改为 `getBroadcast` → `AlertActionReceiver` |
| 通知渠道静音 + 服务可能起不来 | 「匹配上了但一点声音都没有」 | 新增带闹铃音的 `priority_alert_audible_v4` 渠道 |
| 渠道声音创建后不可变 | 老用户设备上改不出新行为 | 用**新渠道 ID**，不修改旧渠道 |
| `apply()` 异步落盘 + 跨进程接收器 | 闹钟读到上一轮状态 | `AlertSession` 统一 `commit()` |
| 服务自己给自己发 `startService(STOP)` | `stopSelf()` 后又被拉起一次 | 提供 `finishFromService` / `snoozeFromService` 变体 |
| 国产 ROM（HyperOS）杀后台 | 闹钟不响 | 权限体检中单列「电池优化 = 无限制」并一键跳转 |

### 1.5 摘抄与引用说明

本轮**未复制任何第三方项目的代码片段**。借鉴的是架构思想与 Android 官方公开的 API 用法；
所有参考来源已在对应源文件头部注释标明场景与用途（如 `AlertScheduler.java` 标注了官方
《Schedule alarms》与 AOSP DeskClock 思路，`SettingsActivity.java` 标注了 AndroidX 迁移指南）。
工程仍为个人自用、不对外商用，符合 GPL-3.0 上游许可的使用前提。

---

## 2. 项目理解小结

| 维度 | 理解 |
|---|---|
| **定位** | 一个「重要短信叫醒器」，不是短信客户端、不是聊天工具、不联网、不上传数据。应用名「铃信 / Bellgram」 |
| **业务用途** | 普通短信提示音太轻，容易漏掉关键信息（到账、报警、验证码、工单等）。用户配置「号码（可选）+ 关键词（必填）」，命中后用**闹铃级**的方式提醒，并在未处理时像闹钟一样间隔重复提醒 |
| **目标设备** | 红米 K60 Pro / HyperOS，因此自启动、省电策略、锁屏通知、全屏通知权限是实际能否响铃的决定因素 |
| **输入** | 系统广播 `android.provider.Telephony.SMS_RECEIVED`（含长短信分包） |
| **输出** | 高优先级通知 + 锁屏全屏告警页 + 最大音量循环闹铃 + 循环震动；未处理则按间隔重复，直至用户点「停止」或达到次数上限 |
| **原有设计约束（本轮恪守）** | ① 关键词为空即不监听；② 号码为空即不限发送方；③ 默认「任意关键词命中」；④ 不改动存储键名，覆盖安装不丢配置；⑤ 所有原有控件 id、Activity/Service action 字符串保持兼容 |
| **运行原理（v3）** | 短信广播 → 匹配 → `startForegroundService` → 服务内 MediaPlayer + Vibrator + Handler 每 5 分钟再响 |
| **运行原理（v4）** | 短信广播 → 匹配 → `AlertDispatcher` 发带闹铃音的高优先级通知（全屏 Intent）→ 系统拉起 `AlertActivity`（用户可见）→ Activity 合法启动前台服务播放**这一轮** → 播完把下一轮交给 `setAlarmClock` → 服务退出；闹钟到点重复以上 |

**关键约束确认**：所有优化都没有改变「收到含关键词的短信 → 强力提醒 → 未处理就重复提醒」这一核心目标，
只是把实现方式换成 Android 12~16 允许的形式；用户可见的默认行为（响铃 60 秒、每 5 分钟一次、最多 4 轮）完全不变。

---

## 3. 审计问题汇总

### 3.1 优先级 1：逻辑错误、运行期 Bug、合规阻断（12 项）

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| V1 | `app/build.gradle` | `targetSdk 29`：2026-08-31 起不符合 Play 要求（需 36）；系统更高版本设备会提示「为旧版 Android 打造」，且 `targetSdk ≤ 34` 的应用在高版本系统上对新用户不可见 | 阻断 |
| V2 | `SMSReceiver.startAlertService` | **升 targetSdk 后主链路硬断**：`SMS_RECEIVED` 不在后台启动 FGS 的豁免清单里，必抛 `ForegroundServiceStartNotAllowedException`；v3 的 catch 只能弹一条通知，闹铃和重复提醒全失效 | 阻断 |
| V3 | `AndroidManifest.xml` | `foregroundServiceType="shortService"` 有约 3 分钟时限，而默认重复间隔是 5 分钟；超时触发 `onTimeout` 甚至 ANR，且缺 `FOREGROUND_SERVICE_SHORT_SERVICE` 配套说明 | 阻断 |
| V4 | `MainActivity` | 未申请 `POST_NOTIFICATIONS`；targetSdk 33+ 下 `notify()` 抛 `SecurityException`，表现为「匹配成功但一条通知都没有」 | 阻断 |
| V5 | `NotificationHelper` | `setFullScreenIntent` 无条件设置；Android 14+ 该权限可能已被撤销，全屏告警静默失效且用户无任何提示 | 高 |
| V6 | `SMSForegroundService` | 用 `Handler.postDelayed` 在服务里等 5 分钟：Doze 下不准时；服务一旦被系统回收，定时器随之消失，「再提醒一次」永远不来 | 高 |
| V7 | `NotificationHelper` | 主渠道 `setSound(null,null)` 静音；一旦服务起不来（V2 场景），整个提醒链路**完全无声**，与核心目标直接冲突 | 高 |
| V8 | `NotificationHelper` | 「停止 / 稍后提醒」用 `PendingIntent.getService`；从后台（通知按钮）`startService` 同样受 Android 12+ 限制，点击可能无响应 | 高 |
| V9 | `AndroidManifest.xml` | `Onboarding` 声明 `exported="true"` 且带 `MAIN` intent-filter，但只由 `MainActivity` 用显式 Intent 拉起；任何外部应用都能把它启动到前台，属不必要的组件暴露 | 中（安全） |
| V10 | `SMSForegroundService` | 服务 `endRound` 调 `AlertDispatcher.finish()`，后者又 `startService(STOP)` 给自己；`stopSelf()` 之后可能触发一次无意义的 `onCreate/onDestroy` | 中 |
| V11 | `AlertDispatcher`（新增链路） | 亮屏时用户不点通知 → 全屏页不弹 → 服务不启动 → **没有任何组件负责安排下一轮闹钟**，重复提醒断链 | 高 |
| V12 | `NotificationHelper` | 点击通知正文原来只打开 `MainActivity`；亮屏状态下不会进入强提醒链路，用户只能听到一声通知音 | 中 |

### 3.2 优先级 2：代码质量、废弃 API、维护成本（8 项）

| # | 问题 |
|---|---|
| V13 | `SettingsActivity` / `AppConfig` / `HitLog` 仍用平台 `android.preference.*`（API 28 废弃）+ `legacy-preference-v14` 依赖 |
| V14 | `settings_activity.xml` 用静态 `<fragment>` 标签装载，生命周期不受 FragmentManager 控制 |
| V15 | `ListPreference` 依赖旧框架 `android:summary="%s"` 的隐式行为，AndroidX 上可能摘要空白 |
| V16 | 三个页面未处理系统栏 insets，targetSdk 35+ 强制边到边后标题/底部按钮会被状态栏与手势条遮挡 |
| V17 | 缺 `dataExtractionRules` / `fullBackupContent`（Android 12+ 官方备份规则写法）、缺 `usesCleartextTraffic=false` |
| V18 | 未开启 `enableOnBackInvokedCallback`，`AlertActivity.onBackPressed()` 在 Android 13+ 不再被调用 → 返回键失效 |
| V19 | 轮次计算逻辑散落在 Service 内部且依赖 Context，无法在 JVM 上单元测试；四个组件各自判断「还有没有下一轮」，易出现分歧 |
| V20 | 权限 / 保活状态分散在系统各处，用户遇到「不响」没有任何自查入口 |

### 3.3 优先级 3：架构级重构方案与利弊分析（按你的要求单独列出）

**判断：必须做架构级重构。** 理由：「符合最新规范（targetSdk 36）」与「v3 架构（广播起 FGS + 服务常驻等待）」
在 Android 平台上是**互斥**的。任何局部补丁都只能靠系统豁免侥幸运行，且会在国产 ROM 上随机失效。

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A（采用）通知驱动 + 精确闹钟 + 单轮服务** | 广播只发通知 → 全屏页（用户可见）启动服务 → 播完交给 `setAlarmClock` → 服务退出 | 完全符合 Android 12~16；无长驻服务、无 3 分钟超时；Doze 下可靠；进程被杀仍会响；权限最小化 | 链路组件从 3 个增到 6 个；亮屏未点通知时只响通知音（但仍有下一轮）；`setAlarmClock` 会在状态栏显示闹钟图标 | ✅ 采用 |
| B 保留 targetSdk 29 | 不改架构，仅维持现状 | 改动为零 | 不符合本次任务目标；高版本系统会有兼容警告/分发限制；长期不可持续 | ❌ |
| C 用 `SYSTEM_ALERT_WINDOW` 悬浮窗豁免 | 申请悬浮窗权限以获得后台起 FGS 的豁免 | 可保留旧架构 | Android 15+ 还要求「当前有可见 overlay」；权限极重、易被应用市场判恶意、HyperOS 上弹窗授权繁琐 | ❌ |
| D 接 FCM 高优先级推送 | 服务器下发高优先级消息触发豁免 | 官方豁免路径 | 需联网、需服务端、与「离线个人自用」定位冲突，且消息可能被降级 | ❌ |
| E 保留长驻服务但改 `specialUse` 类型 | 声明特殊用途 FGS 类型绕过时限 | 改动小 | `specialUse` 需 Play 审核说明、语义不实；服务被杀后定时器仍会丢；不解决长驻耗电 | ❌ |

**破坏性修改清单（重点标注）**

1. `targetSdk 29 → 36`、`compileSdk 34 → 36`（运行期行为变更，需按 §8 全量验收）。
2. `SMSReceiver` 不再调用 `startForegroundService`（内部接口变更；对外行为不变）。
3. `SMSForegroundService` 从「常驻总控」变为「单轮播放器」；`ACTION_START_ALERT` 等 action 字符串**保持不变**，调用方仍兼容。
4. 前台服务类型 `shortService → mediaPlayback`。
5. 通知按钮的 PendingIntent 从 `getService` 改为 `getBroadcast`（覆盖安装后旧通知的动作会失效一轮，已在 §7 说明）。
6. 点击通知正文由「打开主界面」改为「打开全屏告警页」（更强的叫醒，但行为变化）。
7. `Onboarding` 改为 `exported=false` 且移除 intent-filter。
8. 新增 5 个 Java 类 + 2 个 XML 规则文件 + 2 个广播接收器；新增 `androidx.preference` / `androidx.core` / `androidx.activity` 三个官方稳定依赖（AAR 合计约 0.9 MB，无第三方/非官方库）。

---

## 4. 变更总览

### 4.1 合规基线（本轮主题）

| 项 | v3 | v4 |
|---|---|---|
| AGP / Gradle / JDK | 8.5.2 / 8.7 / 17 | **8.11.1 / 8.13 / 17** |
| compileSdk / targetSdk | 34 / 29 | **36 / 36** |
| versionCode / versionName | 17 / 3.0.0 | **18 / 4.0.0** |
| 前台服务类型 | shortService（3 分钟超时） | **mediaPlayback + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`** |
| 通知权限 | 仅清单声明 | **清单 + 运行时申请 + 结果回调** |
| 全屏 Intent | 无条件设置 | **`canUseFullScreenIntent()` 检测 + 权限体检引导** |
| 备份 / 明文流量 | 仅 `allowBackup=false` | **+ `dataExtractionRules` + `fullBackupContent` + `usesCleartextTraffic=false`** |
| 返回手势 | 重写 `onBackPressed()` | **`enableOnBackInvokedCallback=true` + `OnBackPressedDispatcher`** |
| 设置页 | `android.preference`（废弃） | **`androidx.preference 1.2.1` + `PreferenceFragmentCompat`** |
| 边到边 | 未适配 | **`EdgeToEdgeHelper`（主界面 / 设置页 / 告警页）** |
| 精确闹钟权限 | 无 | **不声明（用官方豁免的 `setAlarmClock`），最小权限** |

### 4.2 架构重构（6 个新组件）

| 组件 | 职责 |
|---|---|
| `AlertDispatcher` | 全部「要提醒」请求的唯一入口；开会话、发通知、挂兜底闹钟、停止/稍后/收尾 |
| `AlertSession` | 会话状态持久化（轮次、短信内容、active），`commit()` 保证跨进程可见 |
| `AlertScheduler` | `setAlarmClock()` 安排 / 取消下一轮，Doze 可靠、无需额外权限 |
| `RepeatAlarmReceiver` | 闹钟到点重发通知（绝不启动 FGS） |
| `AlertActionReceiver` | 通知按钮的广播入口，避免后台 `startService` 限制 |
| `RoundPolicy` | 轮次 / 间隔的纯 Java 规则，四个组件共用、可单元测试 |

### 4.3 稳定性修复（P1）

V2 / V3 / V4 / V5 / V6 / V7 / V8 / V9 / V10 / V11 / V12 共 11 项，逐条见 §5。

### 4.4 代码质量（P2）

V13~V20 共 8 项；新增 `RoundPolicyTest` 13 个用例；中英文案各 154 条且键名完全对称；
全部改动行/代码块以 `// modified` 标注。

### 4.5 新增功能（贴合场景，无花哨功能）

| 功能 | 价值 |
|---|---|
| **权限体检**（设置页首项 + 主界面状态条提示） | 把「短信权限 / 通知权限 / 全屏通知 / 电池优化 / 重复闹钟」5 个开关集中展示，点条目直达系统设置；解决「不响却不知为何」 |
| **带闹铃音的主通知渠道** | 服务起不来时也能响第一声，是合规改造带来的实质可靠性提升 |
| **状态栏闹钟图标** | `setAlarmClock` 会在状态栏显示下一次提醒时间，用户能确认「确实还会再响」 |
| **通知渠道分层** | 静音/带声音两渠道，服务接手后自动切换，避免双重响铃 |

### 4.6 明确未改动（保持兼容）

- 目录名 `PriorityAlerts-v3`、`applicationId` / 包名 `com.saiga.find.messagefinder` → **覆盖安装不丢配置**；
- 应用显示名「铃信 / Bellgram」、`SharedPreferences` 全部键名（含 `contact` / `message` / `contact_name`）；
- `KeywordMatcher` / `PhoneNumbers` / `QuietHours` / `HitLog` 的业务语义（仅 `HitLog` 换 import）；
- 主界面布局 `activity_main.xml`、`activity_alert.xml`、`dialog_practice.xml`、`arrays.xml`、`colors.xml`、`styles.xml`；
- 全部原有控件 id 与三个 Service action 字符串；
- v3 已实现的全部 12 项功能（匹配模式、夜间静音、规则演练、全屏告警、稍后提醒、命中记录等）。

---

## 5. 分点变更详情

> 每条按【代码位置】+【原始问题】+【修改思路】+【改动代码片段】组织；完整文件见 §6。

### 5.1 【V1】构建链与 targetSdk 升级

- **【代码位置】** `build.gradle`、`gradle/wrapper/gradle-wrapper.properties`、`app/build.gradle`
- **【原始问题】** AGP 8.5.2 最高只支持 compileSdk 34；`targetSdk 29` 是 v3 为绕开后台启动限制而刻意保留的，2026 年已不符合最新规范。
- **【修改思路】** 按官方兼容表升到 AGP 8.11.1 + Gradle 8.13 + JDK 17 + API 36；依赖换成与 36 匹配的稳定版；移除废弃的 `legacy-preference-v14`，新增 3 个官方 AndroidX 依赖（体积已评估，约 0.9 MB）。
- **【改动代码片段】**

```groovy
// modified: 3.2.1 -> 8.5.2 -> 8.11.1（配合 Gradle 8.13 / JDK 17 / API 36）
classpath 'com.android.tools.build:gradle:8.11.1'
```
```groovy
// modified: 29 -> 34 -> 36（Android 16；需安装 SDK Platform 36）
compileSdk 36
// modified: 29 -> 36（本轮核心合规项）
targetSdk 36
versionCode 18
versionName "4.0.0"
```

### 5.2 【V2/V3】前台服务类型与权限

- **【代码位置】** `app/src/main/AndroidManifest.xml`
- **【原始问题】** `shortService` 约 3 分钟超时，与 5 分钟重复提醒冲突；targetSdk 34+ 缺对应类型权限会启动失败。
- **【修改思路】** 服务改为单轮播放器（播完即退出），类型改为 `mediaPlayback`（确实在播音频，无短时限），声明对应普通权限；新增两个非导出广播接收器。
- **【改动代码片段】**

```xml
<!-- modified: mediaPlayback 类型对应的权限；normal 级别，安装即授予 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```
```xml
<!-- modified: shortService -> mediaPlayback -->
<service
    android:name=".SMSForegroundService"
    android:exported="false"
    android:foregroundServiceType="mediaPlayback" />
```

### 5.3 【V2】短信接收器不再启动前台服务

- **【代码位置】** `app/src/main/java/.../SMSReceiver.java`
- **【原始问题】** 广播处于后台，`startForegroundService()` 在 targetSdk ≥ 31 必抛异常。
- **【修改思路】** 接收器只做匹配/去重/记日志，把「要提醒」交给 `AlertDispatcher` 发通知；删除 `startAlertService()` 与 `Build` 引用。
- **【改动代码片段】**

```java
// modified: v4 主链路。广播接收器处于后台，绝不能在这里启动前台服务；
// 发通知是系统允许的高优先级行为，通知的全屏 Intent 会拉起用户可见的
// AlertActivity，再由它启动服务播放铃声，全程符合 Android 12~16 的限制。
AlertDispatcher.onSmsMatched(context, sender, matchedKeyword, personName, body);
```

### 5.4 【V6/V10】服务重写为单轮播放器

- **【代码位置】** `app/src/main/java/.../SMSForegroundService.java`（整文件重写）
- **【原始问题】** 常驻服务 + Handler 等 5 分钟：Doze 不准、被杀即断链、shortService 超时 ANR、STOP 与调度互相递归。
- **【修改思路】** 只负责本轮播放；`endRound()` 把下一轮交给 `AlertScheduler.setAlarmClock()` 后 `stopSelf()`；`finishFromService` / `snoozeFromService` 避免给自己发 STOP 造成重启；实现 `onTimeout(int,int)` 兜底。
- **【改动代码片段】**

```java
boolean hasNextRound = RoundPolicy.hasNextRound(rounds, total,
        ringDurationSec, repeatIntervalMin); // modified: 规则集中
if (hasNextRound) {
    AlertScheduler.scheduleNext(this, repeatIntervalMin, rounds + 1); // modified: 新增
    AlertDispatcher.markWaiting(this, rounds, repeatIntervalMin); // modified: 新增
} else {
    AlertDispatcher.finishFromService(this, rounds); // modified: 避免自重启
}
stopSelfSafely(false); // detach，保留通知
```
```java
/** Android 14+ 前台服务超时兜底。mediaPlayback 正常不会触发，仍按规范实现。 */
@Override
public void onTimeout(int startId, int fgsType) { // modified: 新增
    Log.w(TAG, "foreground service timeout, stop round");
    handleLocalStop();
}
```

### 5.5 【V7/V8/V12】通知层重写

- **【代码位置】** `app/src/main/java/.../NotificationHelper.java`（整文件重写）
- **【原始问题】** 主渠道静音导致服务起不来时完全无声；按钮用 `getService` 后台受限；全屏 Intent 无权限检测；点击正文只开主界面。
- **【修改思路】** 双渠道分层（带闹铃音的主渠道 + 静音渠道）；统一 `post()` 出口处理权限异常；按钮改 `getBroadcast`；`canUseFullScreenIntent()` 判断后再设 FSI；正文点击改为打开全屏告警页。
- **【改动代码片段】**

```java
public static final String CHANNEL_ID_ALERT = "priority_alert_v2";
public static final String CHANNEL_ID_AUDIBLE = "priority_alert_audible_v4"; // modified: 新增
/** 兼容旧调用点的别名 */
public static final String CHANNEL_ID_FALLBACK = CHANNEL_ID_AUDIBLE; // modified: 改为别名
```
```java
// modified: Android 14+ 的 USE_FULL_SCREEN_INTENT 可能未授予
if (canUseFullScreenIntent(context)) {
    builder.setFullScreenIntent(buildAlertScreenIntent(context, REQ_FULLSCREEN,
            sender, keyword, personName, body), true);
}
```
```java
/** 「停止」动作：发广播，不再直接启动服务（后台启动服务同样受限）。 */
private static PendingIntent buildStopIntent(Context context) { // modified: getService -> getBroadcast
    Intent intent = new Intent(context, AlertActionReceiver.class);
    intent.setAction(AlertActionReceiver.ACTION_STOP);
    return PendingIntent.getBroadcast(context, REQ_STOP, intent, pendingIntentFlags());
}
```

### 5.6 【V11】重复提醒的乐观调度兜底

- **【代码位置】** `app/src/main/java/.../AlertDispatcher.java`
- **【原始问题】** 亮屏时用户不点通知 → 服务不启动 → 无人安排下一轮，重复提醒断链。
- **【修改思路】** 每次发通知时同步挂好下一轮闹钟；服务若正常播完，会用同一 `requestCode` 覆盖一次，把计时起点校正为「响完那一刻」，不会产生双重提醒。
- **【改动代码片段】**

```java
postRound(app, 1, app.getString(R.string.notif_status_round, 1));
scheduleFollowUp(app, 1); // modified: 乐观调度，服务没起来也能有下一轮
```
```java
// modified: 与「单轮播完后」用同一套规则，避免两个组件判断不一致
if (!RoundPolicy.hasNextRound(justPostedRound, total, duration, interval)) {
    return;
}
AlertScheduler.scheduleNext(app, interval, justPostedRound + 1);
```

### 5.7 【V6】精确闹钟调度器

- **【代码位置】** `app/src/main/java/.../AlertScheduler.java`（新增）
- **【原始问题】** Handler 依赖服务存活；Doze 下不准时。
- **【修改思路】** 用官方 `setAlarmClock()`（用户可见闹钟，不受 `SCHEDULE_EXACT_ALARM` 限制、Doze 可唤醒），`showIntent` 显式指向 `MainActivity`；异常时降级 `set(RTC_WAKEUP)`；不声明多余权限。
- **【改动代码片段】**

```java
// modified: FLAG_IMMUTABLE 合规
PendingIntent showIntent = PendingIntent.getActivity(app, REQ_SHOW, show, pendingFlags());
alarmManager.setAlarmClock(
        new AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation);
```

### 5.8 【V2】全屏告警页成为合规启动点

- **【代码位置】** `app/src/main/java/.../AlertActivity.java`
- **【原始问题】** 页面只是「显示层」，铃声由别处启动；targetSdk 36 下已无处可合法启动服务。
- **【修改思路】** 利用「应用从用户可见状态转换」这一官方豁免，在本页 `onCreate/onNewIntent` 启动前台服务；按钮与返回键统一走 `AlertDispatcher`；边到边适配。
- **【改动代码片段】**

```java
EdgeToEdgeHelper.apply(this, R.id.alert_root); // modified: targetSdk 35+ 强制边到边
bindActions();
bindBackHandling(); // modified: 预测式返回手势
bindContent(getIntent());
startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
```
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    startForegroundService(intent);
} else {
    startService(intent);
}
```

### 5.9 【V4】主界面通知运行时权限

- **【代码位置】** `app/src/main/java/.../MainActivity.java`
- **【原始问题】** targetSdk 33+ 不申请 `POST_NOTIFICATIONS`，`notify()` 抛 `SecurityException`。
- **【修改思路】** `onCreate` 申请权限 + 提前建渠道；回调里提示并刷新状态条；状态条追加权限未就绪告警；底部清单可点击打开体检；边到边适配。
- **【改动代码片段】**

```java
private void ensureNotificationPermission() { // modified: 新增
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERM_NOTIFICATIONS_REQUEST);
        }
    }
    NotificationHelper.ensureChannels(this);
}
```

### 5.10 【V5/V20】权限体检

- **【代码位置】** `app/src/main/java/.../PermissionChecker.java`（新增）、`SettingsActivity`、`app_preferences.xml`
- **【原始问题】** 能否响铃取决于 5 个分散的系统开关，缺一项就静默失败且无线索。
- **【修改思路】** 集中检测 5 项，设置页首项显示「N 项待处理」，点击列出条目并直达对应系统设置页（全屏 Intent 用 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`，电池优化用系统列表页）。
- **【改动代码片段】**

```java
NotificationManager nm =
        (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
fsi = nm != null && nm.canUseFullScreenIntent(); // modified: Android 14+ 自检
```

### 5.11 【V13~V15】AndroidX Preference 迁移

- **【代码位置】** `SettingsActivity.java`、`settings_activity.xml`、`app_preferences.xml`、`AppConfig.java`、`HitLog.java`
- **【原始问题】** 平台 `android.preference` 已废弃；静态 `<fragment>`；`%s` 摘要在 AndroidX 上不保证。
- **【修改思路】** `PreferenceFragmentCompat` + `onCreatePreferences`；容器 `FrameLayout` + `FragmentTransaction`；`SwitchPreferenceCompat`；`SimpleSummaryProvider`；`PreferenceManager` 换 AndroidX（默认文件名相同，配置不丢）。
- **【改动代码片段】**

```java
@Override
public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                @Nullable String rootKey) { // modified: AndroidX 标准回调
    setPreferencesFromResource(R.xml.app_preferences, rootKey);
    bindListSummaries();
```
```java
((ListPreference) preference).setSummaryProvider(
        ListPreference.SimpleSummaryProvider.getInstance()); // modified: 官方现代写法
```

### 5.12 【V16】边到边适配

- **【代码位置】** `EdgeToEdgeHelper.java`（新增）+ 三个 Activity
- **【原始问题】** targetSdk 35+ 强制 edge-to-edge，Android 16 已无退出开关。
- **【修改思路】** `setDecorFitsSystemWindows(false)` + `OnApplyWindowInsetsListener` 叠加 systemBars / displayCutout insets；全程 try/catch，不让视觉适配把页面搞崩。
- **【改动代码片段】**

```java
Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
        | WindowInsetsCompat.Type.displayCutout());
view.setPadding(left + bars.left, top + bars.top,
        right + bars.right, bottom + bars.bottom);
```

### 5.13 【V9/V17/V18】清单安全与规范属性

- **【代码位置】** `AndroidManifest.xml`、`res/xml/data_extraction_rules.xml`、`backup_rules.xml`
- **【原始问题】** 引导页无谓导出；缺 Android 12+ 备份规则与明文流量限制；未开预测式返回。
- **【修改思路】** `Onboarding` 改 `exported=false` 并移除 intent-filter；新增备份排除规则（纵深防御）；`usesCleartextTraffic=false`；`enableOnBackInvokedCallback=true`。
- **【改动代码片段】**

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    android:usesCleartextTraffic="false"
    android:enableOnBackInvokedCallback="true" />
```
```xml
<!-- modified: 不导出、无 intent-filter，仅由 MainActivity 显式拉起 -->
<activity android:name=".Onboarding" android:enabled="true" android:exported="false" />
```

### 5.14 【V18】返回键迁移

- **【代码位置】** `AlertActivity.java`
- **【原始问题】** 开启 `enableOnBackInvokedCallback` 后 `onBackPressed()` 不再被调用，返回键变成「关不掉告警页」。
- **【修改思路】** 删除重写，改注册 `OnBackPressedCallback`，语义仍是「返回 = 稍后提醒」。
- **【改动代码片段】**

```java
private void bindBackHandling() { // modified: 新增
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            snoozeAndFinish();
            finishSafely();
        }
    });
}
```

### 5.15 【V19】轮次规则抽出并加单测

- **【代码位置】** `RoundPolicy.java`（新增）、`AppConfig.java`、`RoundPolicyTest.java`（新增）
- **【原始问题】** 轮次逻辑在 Service 内、依赖 Context、四个组件各自判断，无法测试。
- **【修改思路】** 抽成纯 Java 静态规则类，`totalRounds / hasNextRound / safeDelayMinutes` 三处统一；新增 13 个 JVM 单测覆盖边界。
- **【改动代码片段】**

```java
public static boolean hasNextRound(int currentRound, int totalRounds,
                                   int durationSec, int intervalMin) { // modified: 新增
    if (durationSec <= 0 || intervalMin <= 0) return false;
    if (totalRounds <= 0) return true;
    return Math.max(1, currentRound) < totalRounds;
}
```

### 5.16 【V7/V20】文案与资源同步

- **【代码位置】** `values/strings.xml`、`values-zh-rCN/strings.xml`
- **【原始问题】** 新增渠道名、权限体检、状态告警等文案缺失；版本号仍为 3.0.0。
- **【修改思路】** 中英各追加 19 条，键名完全对称（各 154 条）；版本号改 4.0.0。
- **【改动代码片段】**

```xml
<string name="channel_name_audible">优先短信（闹铃音）</string>
<string name="permission_check_title">权限体检</string>
<string name="status_permission_warning">! 有 %1$d 项权限未就绪 —— 点下方清单去处理</string>
<string name="current_app_version">4.0.0</string>
```

---
## 6. 完整整合后的全部源代码

> **本节说明（关于「完整」的边界）**
> 1. 下面按分组贴出**本轮全部修改 / 新增的源码与配置文件的完整当前内容**（§6.1–6.27），
>    把它们放回对应路径即可得到可构建的 v4 工程。
> 2. 所有修改行、新增代码块均以单行注释 `// modified`、`<!-- modified -->` 或 `# modified` 标注；
>    新增文件整体即「新增代码块」，在文件头用 `[新增文件 / modified]` 声明，并对关键方法/字段逐块标注，
>    不对每一行机械重复以免注释淹没代码。
> 3. 本轮**未改动**的文件不重复贴出（工程目录里的文件即是最终内容），完整清单见 §6.28；
>    v3 报告 `AUDIT_REPORT.md` 收录了上一轮的文件树与变更详情。
>    另有两份**文档类文件**（`README.md`、`AUDIT_REPORT.md`）本轮做了同步修改，但它们不是源码、
>    不参与构建，故只在 §6.28 说明改动内容，不在本节贴代码块。
> 4. 工程目录结构与 applicationId 均未变化，覆盖安装不丢配置。

### 6.0 目录树（本轮改动标记）

```
PriorityAlerts-v3/
├── build.gradle                          ← 改：AGP 8.11.1
├── settings.gradle                       （未改动）
├── gradle.properties                     （未改动）
├── gradle/wrapper/gradle-wrapper.properties ← 改：Gradle 8.13
├── app/
│   ├── build.gradle                      ← 改：compileSdk/targetSdk 36、v4.0.0、依赖
│   └── src/main/
│       ├── AndroidManifest.xml           ← 改：权限/组件/备份规则/边到边
│       ├── java/.../messagefinder/
│       │   ├── AlertDispatcher.java      ★ 新增：统一调度入口
│       │   ├── AlertSession.java         ★ 新增：会话状态
│       │   ├── AlertScheduler.java       ★ 新增：精确闹钟
│       │   ├── RepeatAlarmReceiver.java  ★ 新增：闹钟接收器
│       │   ├── AlertActionReceiver.java  ★ 新增：通知动作接收器
│       │   ├── PermissionChecker.java    ★ 新增：权限体检
│       │   ├── EdgeToEdgeHelper.java     ★ 新增：边到边适配
│       │   ├── RoundPolicy.java          ★ 新增：轮次规则
│       │   ├── NotificationHelper.java   ← 重写
│       │   ├── SMSForegroundService.java ← 重写
│       │   ├── SettingsActivity.java     ← 重写
│       │   ├── SMSReceiver.java          ← 改
│       │   ├── AlertActivity.java        ← 改
│       │   ├── MainActivity.java         ← 改
│       │   ├── AppConfig.java            ← 改
│       │   ├── HitLog.java               ← 改
│       │   ├── KeywordMatcher.java       （未改动）
│       │   ├── PhoneNumbers.java         （未改动）
│       │   ├── QuietHours.java           （未改动）
│       │   ├── MyDialogFragment.java     （未改动）
│       │   ├── Onboarding.java           （未改动）
│       │   └── SliderAdapter.java        （未改动）
│       ├── res/xml/
│       │   ├── app_preferences.xml       ← 改
│       │   ├── data_extraction_rules.xml ★ 新增
│       │   └── backup_rules.xml          ★ 新增
│       ├── res/layout/settings_activity.xml ← 改
│       └── res/values/strings.xml / values-zh-rCN/strings.xml ← 改
├── app/src/test/.../RoundPolicyTest.java ★ 新增：13 个用例
├── AUDIT_REPORT.md                       （v3 历史报告，顶部已加取代说明）
├── README.md                             ← 改：横幅同步到 v4.0.0 并指向本报告
└── AUDIT_REPORT_V4.md                    ★ 本报告
```

### 6.1 `build.gradle`　改 · 根构建脚本：AGP 8.5.2 → 8.11.1

```groovy
// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// 二次开发说明（modified）：
//   [v3] 原配置 AGP 3.2.1 + Gradle 4.6 -> AGP 8.5.2 + Gradle 8.7 + JDK 17。
//   [v4] 为支持 compileSdk/targetSdk 36（Android 16），再升到 AGP 8.11.1 + Gradle 8.13。
//        AGP 8.11 是官方首个明确支持 API 36 的版本（Gradle 8.13 / JDK 17 / Build Tools 35.0.0）。
//        本轮同时把 targetSdk 从 29 提到 36，运行期行为变更由代码侧的架构重构承接。

buildscript {

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // modified: 3.2.1 -> 8.5.2 -> 8.11.1（配合 Gradle 8.13 / JDK 17 / API 36）
        classpath 'com.android.tools.build:gradle:8.11.1'

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register('clean', Delete) {
    delete rootProject.layout.buildDirectory
}
```

### 6.2 `gradle/wrapper/gradle-wrapper.properties`　改 · Gradle 8.7 → 8.13

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
# modified: Gradle 4.6 -> 8.7 -> 8.13（AGP 8.11 要求 Gradle 8.13）
# 国内镜像；如镜像不可用，可临时替换为官方地址：
# https\://services.gradle.org/distributions/gradle-8.13-bin.zip
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### 6.3 `app/build.gradle`　改 · compileSdk/targetSdk → 36、版本 4.0.0、依赖调整

```groovy
// 二次开发说明（modified）：
//   1) apply plugin 保留原写法（与 buildscript classpath 配套），改动面最小；
//   2) 新增 namespace：AGP 8 起包名必须在这里声明，manifest 里的 package 已删除；
//   3) [v4·破坏性] compileSdk 34 -> 36、targetSdk 29 -> 36。
//      原因：2026-08-31 起 Google Play 要求新应用/更新 targetSdk >= 36（Android 16）；
//      且 targetSdk <= 34 的应用在更高版本系统上会被限制分发、并出现“为旧版 Android 打造”警告。
//      代价：targetSdk >= 31 后不允许从 SMS_RECEIVED 广播启动前台服务（官方豁免清单不含短信广播）。
//      本轮已把主链路重构为「高优先级通知 + 全屏 Intent + setAlarmClock 精确闹钟」，
//      前台服务只在用户可见（全屏告警页）时启动，因此不再依赖该豁免，详见 AUDIT_REPORT_V4.md §5.1。
//   4) [v4] 依赖调整：移除已废弃的 legacy-preference-v14，改用 androidx.preference 1.2.1；
//      新增 androidx.core 1.13.1 用于 edge-to-edge insets（targetSdk 35+ 强制全屏边到边）。
//      二者均为 Google 官方 AndroidX 稳定版，AAR 合计约 0.6 MB，无新增第三方/非官方依赖。
//   5) 显式指定 UTF-8 源码编码，避免中文注释在部分环境出现乱码告警。

apply plugin: 'com.android.application'

android {
    // modified: AGP 8 要求显式 namespace
    namespace 'com.saiga.find.messagefinder'

    // modified: 29 -> 34 -> 36（Android 16；需安装 SDK Platform 36）
    compileSdk 36

    defaultConfig {
        applicationId "com.saiga.find.messagefinder"
        // 保持向后兼容：Android 6.0 起
        minSdk 23
        // modified: 29 -> 36（本轮核心合规项，理由与配套架构改造见文件头与报告 §5.1）
        targetSdk 36
        // modified: 版本号随本次二次开发递增
        versionCode 18
        versionName "4.0.0"
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 调试包不做混淆，便于查看堆栈
        }
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
        // modified: 固定源码编码，保证中文注释与字符串正确编译
        encoding = 'UTF-8'
    }

    lint {
        // v4 已清除 android.preference 等废弃 API；保留该开关是为了避免 OEM 相关
        // 的第三方库告警阻断本地调试打包（个人自用工程，不进 CI）。
        abortOnError false
        checkReleaseBuilds false
    }
}

dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    // modified: 统一升级到与 compileSdk 36 匹配的稳定版本（原 1.2.0 / 1.3.0-alpha02）
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.core:core:1.13.1'
    implementation 'androidx.activity:activity:1.9.1'
    implementation 'androidx.preference:preference:1.2.1'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.legacy:legacy-support-v4:1.0.0'
    implementation 'de.hdodenhof:circleimageview:3.1.0'

    // 单元测试：新增的 KeywordMatcher / PhoneNumbers 为纯 Java 类，可直接在 JVM 上验证
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

### 6.4 `app/src/main/AndroidManifest.xml`　改 · 权限/组件/备份规则/边到边开关

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  v4 合规改造说明（modified）：
  1) targetSdk 36 / compileSdk 36。targetSdk>=31 后「从短信广播启动前台服务」被系统禁止，
     因此主链路改为：短信广播 -> 高优先级通知(全屏 Intent) -> 全屏告警页(用户可见) -> 前台服务。
  2) 前台服务类型 shortService -> mediaPlayback：
     shortService 在 Android 14+ 只有约 3 分钟时限，超时会触发 onTimeout 甚至 ANR；
     本应用的铃声音频播放属于 mediaPlayback，无该时限，且只需安装即授予的普通权限。
  3) USE_FULL_SCREEN_INTENT 在 Android 14+ 不再默认授予，代码用 canUseFullScreenIntent()
     检测并在权限体检里引导用户到 ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT。
  4) POST_NOTIFICATIONS 在 targetSdk 33+ 必须运行时申请（MainActivity 已接入）。
  5) 新增 dataExtractionRules / fullBackupContent：Android 12+ 备份规则的官方写法；
     allowBackup=false 仍保留，规则作为纵深防御，避免号码与关键词被同步出去。
  6) usesCleartextTraffic=false：本应用自身不发起任何明文网络请求。
  7) enableOnBackInvokedCallback=true：Android 13+ 预测式返回手势；
     AlertActivity 同步改用 OnBackPressedDispatcher 接管返回键。
  8) 新增两个非导出广播接收器：重复闹钟、通知动作。均用显式 Intent 触发，
     符合 targetSdk 34+ 对隐式 Intent / 非导出组件的限制。
  9) Onboarding 改为 exported=false 并移除 intent-filter：它只由 MainActivity 用显式 Intent 拉起，
     原先 exported=true 且带 MAIN intent-filter，任何外部应用都能把它拉到前台，属于不必要的暴露。
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 前台服务（API 28+）及其在 Android 14+ 必需的类型权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <!-- modified: mediaPlayback 类型对应的权限；normal 级别，安装即授予 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <!-- 接收短信权限：核心权限，运行时申请 -->
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <!-- modified: Android 13+ 通知运行时权限（targetSdk 36 下必须申请，已接入 MainActivity） -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <!-- modified: 全屏告警。Android 14+ 默认可能未授予，必须检测 + 引导用户手动开启 -->
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <!-- 说明：不声明 SCHEDULE_EXACT_ALARM。重复提醒统一使用 AlarmManager.setAlarmClock()，
         这是官方定义的“用户可见闹钟”API，不受 Android 12+ 精确闹钟权限限制，
         且能在低电耗(Doze)模式下准时触发；遵循最小权限原则。 -->

    <application
        android:icon="@mipmap/app_logo"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/app_logo_round"
        android:supportsRtl="true"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:usesCleartextTraffic="false"
        android:enableOnBackInvokedCallback="true"
        android:theme="@style/MyAppTheme.Splash">

        <activity
            android:name=".SettingsActivity"
            android:exported="false"
            android:label="@string/settings_button"
            android:theme="@style/AppSettingsTheme" />

        <!-- modified: 不导出、无 intent-filter，仅由 MainActivity 显式拉起 -->
        <activity
            android:name=".Onboarding"
            android:enabled="true"
            android:exported="false" />

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".AlertActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleTop"
            android:taskAffinity="com.saiga.find.messagefinder.alert"
            android:theme="@style/AlertTheme" />

        <receiver
            android:name=".SMSReceiver"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter>
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>

        <!-- modified: 精确闹钟触发的“下一轮提醒”，非导出，仅本应用显式广播 -->
        <receiver
            android:name=".RepeatAlarmReceiver"
            android:enabled="true"
            android:exported="false" />

        <!-- modified: 通知上「停止 / 稍后提醒」按钮，改为广播入口。
             从后台直接 startService 在 Android 12+ 同样受限，广播接收器不受此限制。 -->
        <receiver
            android:name=".AlertActionReceiver"
            android:enabled="true"
            android:exported="false" />

        <!-- modified: shortService -> mediaPlayback。服务只在用户可见时由 AlertActivity 启动，
             每轮响完即 stopSelf，不再长时间驻留前台。 -->
        <service
            android:name=".SMSForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>

</manifest>
```

### 6.5 `app/src/main/res/xml/data_extraction_rules.xml`　★新增 · Android 12+ 数据导出排除规则

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- modified: Android 12(API 31)+ 数据导出规则。本应用保存手机号与关键词规则，
     明确排除全部目录，禁止云备份与设备间迁移带走这些数据。 -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
    </device-transfer>
</data-extraction-rules>
```

### 6.6 `app/src/main/res/xml/backup_rules.xml`　★新增 · Android 11 及以下旧式备份规则

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- modified: Android 11 及以下的旧式备份规则，语义与 data_extraction_rules 一致。 -->
<full-backup-content>
    <exclude domain="root" path="." />
</full-backup-content>
```

### 6.7 `app/src/main/res/xml/app_preferences.xml`　改 · SwitchPreferenceCompat + 权限体检入口

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    android:title="@string/settings_button">

    <!-- modified: v4 权限体检入口。targetSdk 36 下能否响铃取决于多项系统开关，
         把它放在设置页最上方，用户「不响」时有明确的自查入口。 -->
    <Preference
        android:key="@string/permission_check_key"
        android:title="@string/permission_check_title"
        android:summary="@string/permission_check_summary"
        android:persistent="false" />

    <!-- 二次开发新增：提醒方式与重复策略 -->
    <PreferenceCategory
        android:title="@string/pref_category_reminder"
        android:key="@string/pref_category_reminder_key">

        <SwitchPreferenceCompat
            android:key="@string/match_all_key"
            android:title="@string/match_all_title"
            android:defaultValue="false"
            android:summaryOn="@string/match_all_on"
            android:summaryOff="@string/match_all_off" />

        <SwitchPreferenceCompat
            android:key="@string/ring_enabled_key"
            android:title="@string/ring_enabled"
            android:defaultValue="true"
            android:summaryOn="@string/ring_enabled_on"
            android:summaryOff="@string/ring_enabled_off" />

        <SwitchPreferenceCompat
            android:key="@string/vibrate_enabled_key"
            android:title="@string/vibrate_enabled"
            android:defaultValue="true"
            android:summaryOn="@string/vibrate_enabled_on"
            android:summaryOff="@string/vibrate_enabled_off" />

        <ListPreference
            android:key="@string/ring_duration_key"
            android:title="@string/ring_duration_title"
            android:dialogTitle="@string/ring_duration_title"
            android:dialogMessage="@string/ring_duration_hint"
            android:entries="@array/ring_duration_entries"
            android:entryValues="@array/ring_duration_values"
            android:defaultValue="60"
            android:summary="%s" />

        <ListPreference
            android:key="@string/repeat_interval_key"
            android:title="@string/repeat_interval_title"
            android:dialogTitle="@string/repeat_interval_title"
            android:dialogMessage="@string/repeat_interval_hint"
            android:entries="@array/repeat_interval_entries"
            android:entryValues="@array/repeat_interval_values"
            android:defaultValue="5"
            android:summary="%s" />

        <ListPreference
            android:key="@string/max_repeat_key"
            android:title="@string/max_repeat_title"
            android:dialogTitle="@string/max_repeat_title"
            android:dialogMessage="@string/max_repeat_hint"
            android:entries="@array/max_repeat_entries"
            android:entryValues="@array/max_repeat_values"
            android:defaultValue="3"
            android:summary="%s" />
        <ListPreference
            android:key="@string/quiet_hours_key"
            android:title="@string/quiet_hours_title"
            android:dialogTitle="@string/quiet_hours_title"
            android:dialogMessage="@string/quiet_hours_hint"
            android:entries="@array/quiet_hours_entries"
            android:entryValues="@array/quiet_hours_values"
            android:defaultValue="off"
            android:summary="%s" />
    </PreferenceCategory>

    <!-- preference category for experimental features -->
    <PreferenceCategory android:title="@string/app_pref_category1" android:key="@string/app_category_key1">
        <SwitchPreferenceCompat android:title="@string/auto_suggestion_feature"
            android:defaultValue="false"
            android:enabled="true"
            android:key="@string/auto_suggestion_key"
            android:summaryOff="@string/auto_suggestion_off"
            android:summaryOn="@string/auto_suggestion_on"/>
    </PreferenceCategory>
    <!-- preference category for user saved preferences -->
    <PreferenceCategory android:title="@string/app_pref_category2" android:key="@string/app_category_key2">
        <Preference android:title="@string/sms_config"
            android:key="@string/sms_config_key"
            android:enabled="false"
            android:shouldDisableView="false"
            android:summary="%s"/>

        <Preference android:title="@string/app_version"
            android:key="@string/app_version_key"
            android:enabled="false"
            android:shouldDisableView="false"
            android:summary="@string/current_app_version"/>       
           <!-- 二次开发新增：最近命中记录，便于自查规则是否生效 -->
        <Preference android:title="@string/hit_log"
            android:key="@string/hit_log_key"
            android:persistent="false"
            android:enabled="true"
            android:shouldDisableView="false"
            android:summary="%s"/>

        <Preference android:title="@string/clear_hit_log"
            android:key="@string/clear_hit_log_key"
            android:persistent="false"
            android:enabled="true"/>

        <Preference android:title="@string/app_privacy_policy"




            android:enabled="true"
            android:summary="@string/user_guidance">
            <intent android:action="android.intent.action.VIEW"
                android:data="@string/privacy_url"/>
           </Preference>








    </PreferenceCategory>

</PreferenceScreen>
```

### 6.8 `app/src/main/res/layout/settings_activity.xml`　改 · 改为 Fragment 容器

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- modified: v4 迁移到 androidx.preference.PreferenceFragmentCompat。
     旧写法 <fragment android:name="..."> 依赖已废弃的 android.preference 体系，
     换成容器 + FragmentTransaction，由 SettingsActivity 在 onCreate 里装载。 -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/settings_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:context="com.saiga.find.messagefinder.SettingsActivity" />
```

### 6.9 `app/src/main/res/values/strings.xml`　改 · 英文文案 154 条 / 版本 4.0.0

```xml
<resources>
    <string name="app_name">Bellgram</string>
    <string name="enter_the_contact_name">FROM NUMBER (OPTIONAL)</string>
    <string name="contact_value">Phone number, leave empty for all</string>
    <string name="message_string">ALERT KEYWORDS (REQUIRED)</string>
    <string name="message_hint">Keywords, separate with commas</string>
    <string name="trigger_button">Turn on alert</string>
    <string name="clear_button">Clear settings</string>
    <string name="reminder_settings_button">Reminder mode &amp; repeat</string>
    <string name="logo">app_logo</string>

    <string name="settings_button">App Settings</string>
    <string name="app_pref_category1">Features</string>
    <string name="app_category_key1">features_key</string>
    <string name="app_pref_category2">App Configurations</string>
    <string name="app_category_key2">configuration_key</string>

    <string name="sms_config">Active SMS Configuration</string>
    <string name="sms_config_key">notification_key</string>

    <string name="app_version">App Version</string>
    <string name="app_version_key">app_version_key</string>
    <string name="current_app_version">4.0.0</string>
    <string name="app_privacy_policy">Privacy Policy</string>
    <string name="privacy_url">http://unchartedtech.work/privacy-policy</string>
    <string name="user_guidance">Click to view App Privacy Policy</string>

    <string name="auto_suggestion_feature">Auto Suggestion</string>
    <string name="auto_suggestion_key">autosuggestion_key</string>
    <string name="auto_suggestion_on">Currently Enabled, restart the app for the feature</string>
    <string name="auto_suggestion_off">Currently Disabled, enabling it will request read contacts permission</string>

    <string name="dialog_text">Do you want to enable auto suggestion feature for contact number ?</string>
    <string name="helper_text_keyword">Separate with commas or spaces; any keyword hit triggers the alarm</string>

    <string name="OnboardingTitle1">"ENTER KEYWORDS"</string>
    <string name="OnboardingTitle2">"BELLGRAM"</string>
    <string name="OnboardingTitle3">"PRIVACY"</string>

    <string name="OnboardingDesc1">"Supports multiple keywords, informs when the user configured keyword is received via SMS".\n We need user approval to read incoming SMS in background to provide this feature"</string>
    <string name="OnboardingDesc2">"Enables Notification even in silent mode for the configured keywords"</string>
    <string name="OnboardingDesc3">"We follow all the android best practices to protect your privacy.We never collect and share data to anyone"</string>
    <string name="prev">Back</string>
    <string name="next">Next</string>
    <string name="finish">Finish</string>

    <!-- Hardcoded strings moved into resources for localization -->
    <string name="contact_picker_title">Contact Picker</string>
    <string name="contact_picker_message">Do you want to pick the contacts ?</string>
    <string name="yes">Yes</string>
    <string name="no">No</string>
    <string name="input_format_error">Invalid number, expected 5-15 digits</string>
    <string name="notif_title">Priority Message Received!</string>
    <string name="notif_text">Received the text %1$s from %2$s</string>
    <string name="notif_bigtext">Received the text %1$s from %2$s</string>
    <string name="stop">STOP</string>
    <string name="notif_summary">Press STOP to cancel</string>

    <!-- More hardcoded UI strings moved into resources -->
    <string name="channel_name">Priority SMS</string>
    <string name="dialog_set">SET</string>
    <string name="dialog_dismiss">DISMISS</string>
    <string name="dialog_dont_show">Dont Show Again</string>
    <string name="config_saved">saved the config to capture SMS packets</string>
    <string name="keyword_empty">Keyword for alert is empty!</string>
    <string name="enable_sms_perm">Enable READ SMS permission in App Permissions</string>
    <string name="show_user_msg">Some permissions needs to be enabled to work properly</string>
    <string name="enable_contact_perm">Enable Contacts permission for Auto Suggestion</string>
    <string name="set_permissions">SET PERMISSIONS</string>
    <string name="triggered">Alert is on</string>
    <string name="cleared">Cleared</string>

    <!-- Active configuration summary in settings -->
    <string name="config_summary_header">Current Configuration:\nPhone number: </string>
    <string name="not_configured"> Not Configured </string>
    <string name="contact_name_label">\nContact Name: </string>
    <string name="keywords_label">\nKeywords: </string>

    <!-- 二次开发新增：号码输入提示与校验说明 -->
    <string name="contact_helper">5-15 digits, country code allowed</string>
    <string name="no_contact_app">No contacts app found on this device</string>

    <!-- 二次开发新增：通知渠道说明（静音渠道 + 降级有声渠道） -->
    <string name="channel_desc">Rings the alarm tone played by Bellgram itself</string>
    <string name="channel_name_fallback">Priority SMS (fallback)</string>
    <string name="channel_desc_fallback">Used only when the alert service cannot start</string>

    <!-- 二次开发新增：最近命中记录 -->
    <string name="hit_log">Recent matches</string>
    <string name="hit_log_key">hit_log_key</string>
    <string name="hit_log_empty">No match recorded yet. Send a test SMS to verify your rule.</string>
    <string name="clear_hit_log">Clear match history</string>
    <string name="clear_hit_log_key">clear_hit_log_key</string>
    <string name="hit_log_cleared">Match history cleared</string>


    <!-- 二次开发新增：重做界面所需文案 -->
    <string name="app_tagline">Rings an alarm at full volume when an SMS with your keywords arrives, so a quiet notification tone never lets you miss it.</string>
    <string name="status_idle">Not listening yet — enter at least one keyword, then tap "Turn on alert".</string>
    <string name="status_listening">Listening · keywords: %1$s</string>
    <!-- 二次开发新增：状态条上的提醒策略摘要 -->
    <string name="status_mode">Reminder: %1$s</string>
    <string name="mode_ring_on">alarm sound</string>
    <string name="mode_ring_off">silent</string>
    <string name="mode_vibrate_on">vibrate</string>
    <string name="mode_vibrate_off">no vibration</string>
    <string name="mode_repeat_unlimited">repeat every %1$d min until stopped</string>
    <string name="mode_repeat_limited">repeat every %1$d min, %2$d alerts max</string>
    <string name="permission_tip">Redmi / HyperOS checklist: ① Settings → Apps → Bellgram → Autostart = ON; ② Battery saver → No restrictions; ③ Notifications → allow lock screen &amp; floating notifications; ④ allow the "Receive SMS" permission. Without these MIUI may kill the app in the background and the alarm will not ring.</string>

    <!-- 二次开发新增：提醒方式与重复策略 -->
    <string name="pref_category_reminder">RULES &amp; REMINDER</string>
    <string name="pref_category_reminder_key">reminder_key</string>

    <string name="ring_enabled">Ring the alarm</string>
    <string name="ring_enabled_key">ring_enabled</string>
    <string name="ring_enabled_on">Alarm tone plays at max volume</string>
    <string name="ring_enabled_off">Silent — only the notification / vibration will alert you</string>

    <string name="vibrate_enabled">Vibrate</string>
    <string name="vibrate_enabled_key">vibrate_enabled</string>
    <string name="vibrate_enabled_on">Repeating vibration pattern while alerting</string>
    <string name="vibrate_enabled_off">No vibration</string>

    <string name="ring_duration_title">Ring duration per round</string>
    <string name="ring_duration_key">ring_duration</string>
    <string name="ring_duration_hint">If you pick "Until I stop it", repeat reminders are disabled.</string>

    <string name="repeat_interval_title">Repeat every</string>
    <string name="repeat_interval_key">repeat_interval</string>
    <string name="repeat_interval_hint">Re-alerts when you have not tapped STOP.</string>

    <string name="max_repeat_title">Repeat at most</string>
    <string name="max_repeat_key">max_repeat</string>
    <string name="max_repeat_hint">"Until I stop it" keeps ringing until you tap STOP.</string>

    <!-- 二次开发新增：通知状态文案 -->
    <string name="notif_status_round">Alert %1$d</string>
    <string name="notif_status_wait">Alerted %1$d time(s), next in %2$d min</string>
    <string name="notif_status_done">Finished after %1$d alert(s)</string>
    <string name="notif_status_done_once">Finished</string>
    <string name="notif_status_snoozed">Snoozed · alerts again in %1$d min</string>
    <!-- 二次开发新增（借鉴短信铃铛）：匹配模式、夜间静音、规则演练、全屏告警 -->
    <string name="match_all_title">Require every keyword</string>
    <string name="match_all_key">match_all</string>
    <string name="match_all_on">Alert only when ALL keywords appear (fewer false alarms)</string>
    <string name="match_all_off">Alert when ANY keyword appears (original behaviour)</string>

    <string name="quiet_hours_title">Quiet hours (vibrate only)</string>
    <string name="quiet_hours_key">quiet_hours</string>
    <string name="quiet_hours_hint">During this window the alarm stays silent and only vibration + notification alert you. Repeats still happen.</string>

    <string name="practice_button">Test rule</string>
    <string name="practice_title">Test the rule with a sample SMS</string>
    <string name="practice_hint">Paste an SMS you would receive and see instantly whether it would be caught — before you rely on it.</string>
    <string name="practice_input_hint">Sample SMS text</string>
    <string name="practice_run">Test</string>
    <string name="practice_hit">Matched · keyword \"%1$s\"</string>
    <string name="practice_miss">Not matched · check the keywords or the matching mode</string>
    <string name="practice_no_keyword">Fill in at least one keyword first</string>
    <string name="practice_no_input">Paste a sample SMS first</string>

    <string name="notif_snooze">Snooze</string>
    <string name="alert_title">Bellgram</string>
    <string name="alert_from">From %1$s</string>
    <string name="alert_keyword">Matched keyword: %1$s</string>
    <string name="alert_tip">Tap STOP to silence. Snooze silences now and alerts again later.</string>
    <string name="alert_stop">STOP</string>
    <string name="alert_snooze">SNOOZE</string>

    <string name="mode_match_all">all keywords</string>
    <string name="mode_match_any">any keyword</string>
    <string name="mode_quiet">quiet %1$s (vibrate only)</string>
    <!-- v4 合规改造：带闹铃音的主渠道 + Android 16 权限体检 -->
    <string name="channel_name_audible">Priority SMS (alarm tone)</string>
    <string name="channel_desc_audible">Rings the alarm tone immediately when a matching SMS arrives</string>
    <string name="permission_check_key">permission_check</string>
    <string name="permission_check_title">Permission check</string>
    <string name="permission_check_summary">Check every system switch that can silence the alert</string>
    <string name="permission_check_ready">All set</string>
    <string name="permission_check_pending">%1$d item(s) need attention</string>
    <string name="perm_item_sms">Receive SMS</string>
    <string name="perm_item_sms_detail">Required to read incoming SMS and match your keywords</string>
    <string name="perm_item_notification">Notifications</string>
    <string name="perm_item_notification_detail">Android 13+ runtime permission plus the app notification switch</string>
    <string name="perm_item_fsi">Full-screen alerts</string>
    <string name="perm_item_fsi_detail">Android 14+ may revoke this by default; grant it under Special app access</string>
    <string name="perm_item_battery">Battery optimization</string>
    <string name="perm_item_battery_detail">Set to No restrictions on Redmi / HyperOS, or the alarm may be killed</string>
    <string name="perm_item_alarm">Exact repeat alarms</string>
    <string name="perm_item_alarm_detail">Uses setAlarmClock (user-visible alarm); no extra permission needed and works in Doze</string>
    <string name="status_permission_warning">! %1$d permission item(s) not ready - tap the checklist below</string>
    <string name="notif_permission_denied">Notifications are off - the alarm cannot be delivered</string>
</resources>
```

### 6.10 `app/src/main/res/values-zh-rCN/strings.xml`　改 · 中文文案 154 条 / 版本 4.0.0

```xml
<resources>
    <string name="app_name">铃信</string>
    <string name="enter_the_contact_name">接收号码（可选）</string>
    <string name="contact_value">手机号，留空表示所有号码</string>
    <string name="message_string">提醒关键词（必填）</string>
    <string name="message_hint">多个关键词用逗号分隔</string>
    <string name="trigger_button">启用提醒</string>
    <string name="clear_button">清除配置</string>
    <string name="reminder_settings_button">提醒方式与重复</string>
    <string name="logo">app_logo</string>

    <string name="settings_button">应用设置</string>
    <string name="app_pref_category1">功能</string>
    <!-- 该字符串用作 SharedPreferences 的 key，必须与默认值保持一致，禁止翻译 -->
    <string name="app_category_key1">features_key</string>
    <string name="app_pref_category2">应用配置</string>
    <string name="app_category_key2">configuration_key</string>

    <string name="sms_config">当前短信配置</string>
    <string name="sms_config_key">notification_key</string>

    <string name="app_version">应用版本</string>
    <string name="app_version_key">app_version_key</string>
    <string name="current_app_version">4.0.0</string>
    <string name="app_privacy_policy">隐私政策</string>
    <string name="privacy_url">http://unchartedtech.work/privacy-policy</string>
    <string name="user_guidance">点击查看应用隐私政策</string>

    <string name="auto_suggestion_feature">自动联想</string>
    <string name="auto_suggestion_key">autosuggestion_key</string>
    <string name="auto_suggestion_on">当前已启用，重启应用后该功能生效</string>
    <string name="auto_suggestion_off">当前未启用，开启将申请读取联系人权限</string>

    <string name="dialog_text">是否为联系人号码启用自动联想功能？</string>
    <string name="helper_text_keyword">多个关键词用逗号或空格分隔，任一命中即响铃</string>

    <string name="OnboardingTitle1">"输入关键词"</string>
    <string name="OnboardingTitle2">"铃信"</string>
    <string name="OnboardingTitle3">"隐私"</string>

    <string name="OnboardingDesc1">"支持多个关键词，当收到包含所配置关键词的短信时通知您"。\n 提供该功能需要您同意在后台读取收到的短信"</string>
    <string name="OnboardingDesc2">"即使在静音模式下，也能为所配置的关键词发出通知"</string>
    <string name="OnboardingDesc3">"我们遵循安卓各项最佳实践来保护您的隐私，绝不收集或向任何人分享数据"</string>
    <string name="prev">上一步</string>
    <string name="next">下一步</string>
    <string name="finish">完成</string>

    <!-- 由硬编码迁入的字符串 -->
    <string name="contact_picker_title">选择联系人</string>
    <string name="contact_picker_message">是否要选择联系人？</string>
    <string name="yes">是</string>
    <string name="no">否</string>
    <string name="input_format_error">号码格式不正确（应为 5-15 位数字）</string>
    <string name="notif_title">收到优先消息！</string>
    <string name="notif_text">收到来自 %2$s 的关键词 %1$s</string>
    <string name="notif_bigtext">收到来自 %2$s 的短信：%1$s</string>
    <string name="stop">停止</string>
    <string name="notif_summary">点击“停止”取消</string>

    <!-- 由硬编码迁入的其余字符串 -->
    <string name="channel_name">优先短信</string>
    <string name="dialog_set">设置</string>
    <string name="dialog_dismiss">关闭</string>
    <string name="dialog_dont_show">不再显示</string>
    <string name="config_saved">已保存配置，将据此捕获短信</string>
    <string name="keyword_empty">提醒关键词不能为空！</string>
    <string name="enable_sms_perm">请在应用权限中开启“读取短信”权限</string>
    <string name="show_user_msg">需要开启部分权限才能正常工作</string>
    <string name="enable_contact_perm">请开启联系人权限以使用自动联想</string>
    <string name="set_permissions">设置权限</string>
    <string name="triggered">提醒已开启</string>
    <string name="cleared">已清除</string>

    <!-- 设置页当前配置摘要 -->
    <string name="config_summary_header">当前配置：\n电话号码：</string>
    <string name="not_configured"> 未配置 </string>
    <string name="contact_name_label">\n联系人姓名：</string>
    <string name="keywords_label">\n关键词：</string>

    <!-- 二次开发新增：号码输入提示与校验说明 -->
    <string name="contact_helper">最多 15 位，可含国际区号</string>
    <string name="no_contact_app">本机未找到可用的通讯录应用</string>

    <!-- 二次开发新增：通知渠道说明（静音渠道 + 降级有声渠道） -->
    <string name="channel_desc">由本应用自行播放闹钟铃声，系统通知音已关闭</string>
    <string name="channel_name_fallback">优先短信（降级通道）</string>
    <string name="channel_desc_fallback">仅在提醒服务无法启动时使用</string>

    <!-- 二次开发新增：最近命中记录 -->
    <string name="hit_log">最近命中记录</string>
    <string name="hit_log_key">hit_log_key</string>
    <string name="hit_log_empty">暂无命中记录。可发送一条测试短信验证规则是否生效。</string>
    <string name="clear_hit_log">清空命中记录</string>
    <string name="clear_hit_log_key">clear_hit_log_key</string>
    <string name="hit_log_cleared">命中记录已清空</string>


    <!-- 二次开发新增：重做界面所需文案 -->
    <string name="app_tagline">收到含关键词的短信时，用最大音量的闹铃提醒你——提示音太小、容易错过的重要信息，不会再漏掉。</string>
    <string name="status_idle">尚未监听 · 填写至少一个关键词后点击「启用提醒」</string>
    <string name="status_listening">监听中 · 关键词：%1$s</string>
    <!-- 二次开发新增：状态条上的提醒策略摘要 -->
    <string name="status_mode">提醒方式：%1$s</string>
    <string name="mode_ring_on">闹钟铃声</string>
    <string name="mode_ring_off">静音</string>
    <string name="mode_vibrate_on">震动</string>
    <string name="mode_vibrate_off">不震动</string>
    <string name="mode_repeat_unlimited">每 %1$d 分钟提醒一次，直到我停止</string>
    <string name="mode_repeat_limited">每 %1$d 分钟提醒一次，最多 %2$d 次</string>
    <string name="permission_tip">红米 / HyperOS 必做四步：① 设置 → 应用设置 → 应用管理 → 铃信 → 自启动 = 开；② 省电策略 = 无限制；③ 通知管理 → 允许锁屏通知与悬浮通知；④ 权限 → 允许「接收短信」。缺任一项，MIUI 都可能在后台杀掉进程，闹铃不会响。</string>

    <!-- 二次开发新增：提醒方式与重复策略 -->
    <string name="pref_category_reminder">规则与提醒方式</string>
    <string name="pref_category_reminder_key">reminder_key</string>

    <string name="ring_enabled">响铃</string>
    <string name="ring_enabled_key">ring_enabled</string>
    <string name="ring_enabled_on">以最大音量播放闹钟铃声</string>
    <string name="ring_enabled_off">静音，仅靠通知/震动提醒</string>

    <string name="vibrate_enabled">震动</string>
    <string name="vibrate_enabled_key">vibrate_enabled</string>
    <string name="vibrate_enabled_on">提醒期间循环震动</string>
    <string name="vibrate_enabled_off">不震动</string>

    <string name="ring_duration_title">单次响铃时长</string>
    <string name="ring_duration_key">ring_duration</string>
    <string name="ring_duration_hint">选择「直到我手动停止」时，重复提醒不会生效。</string>

    <string name="repeat_interval_title">重复提醒间隔</string>
    <string name="repeat_interval_key">repeat_interval</string>
    <string name="repeat_interval_hint">未点击「停止」时，隔多久再响一次。</string>

    <string name="max_repeat_title">最多重复</string>
    <string name="max_repeat_key">max_repeat</string>
    <string name="max_repeat_hint">选择「不限」会一直重复到你点停止。</string>

    <!-- 二次开发新增：通知状态文案 -->
    <string name="notif_status_round">第 %1$d 次提醒</string>
    <string name="notif_status_wait">已提醒 %1$d 次 · %2$d 分钟后再次提醒</string>
    <string name="notif_status_done">提醒已结束（共 %1$d 次）</string>
    <string name="notif_status_done_once">提醒已结束</string>
    <string name="notif_status_snoozed">已稍后提醒 · %1$d 分钟后再次响铃</string>
    <!-- 二次开发新增（借鉴短信铃铛）：匹配模式、夜间静音、规则演练、全屏告警 -->
    <string name="match_all_title">必须包含全部关键词</string>
    <string name="match_all_key">match_all</string>
    <string name="match_all_on">只有全部关键词都出现才提醒（误报更少）</string>
    <string name="match_all_off">任一关键词出现即提醒（原版行为）</string>

    <string name="quiet_hours_title">夜间静音（只震动）</string>
    <string name="quiet_hours_key">quiet_hours</string>
    <string name="quiet_hours_hint">该时段内不响铃，只用震动 + 通知提醒；重复提醒照常进行。</string>

    <string name="practice_button">规则演练</string>
    <string name="practice_title">用示例短信验证规则</string>
    <string name="practice_hint">把一条你会收到的短信粘进来，立刻看到它会不会被拦截——先验证再依赖。</string>
    <string name="practice_input_hint">示例短信内容</string>
    <string name="practice_run">测试</string>
    <string name="practice_hit">命中 · 关键词「%1$s」</string>
    <string name="practice_miss">未命中 · 检查关键词或匹配模式</string>
    <string name="practice_no_keyword">请先填写至少一个关键词</string>
    <string name="practice_no_input">请先粘贴一条示例短信</string>

    <string name="notif_snooze">稍后提醒</string>
    <string name="alert_title">铃信</string>
    <string name="alert_from">来自 %1$s</string>
    <string name="alert_keyword">命中关键词：%1$s</string>
    <string name="alert_tip">点「停止」立即静音；点「稍后提醒」先静音、过一会儿再响。</string>
    <string name="alert_stop">停止</string>
    <string name="alert_snooze">稍后提醒</string>

    <string name="mode_match_all">全部关键词</string>
    <string name="mode_match_any">任意关键词</string>
    <string name="mode_quiet">夜间 %1$s 只震动</string>
    <!-- v4 合规改造：带闹铃音的主渠道 + Android 16 权限体检 -->
    <string name="channel_name_audible">优先短信（闹铃音）</string>
    <string name="channel_desc_audible">命中短信到达时立即播放闹铃音，不依赖后台服务</string>
    <string name="permission_check_key">permission_check</string>
    <string name="permission_check_title">权限体检</string>
    <string name="permission_check_summary">集中检查所有会让提醒失效的系统开关</string>
    <string name="permission_check_ready">全部就绪</string>
    <string name="permission_check_pending">%1$d 项待处理</string>
    <string name="perm_item_sms">接收短信</string>
    <string name="perm_item_sms_detail">读取短信并匹配关键词的核心权限</string>
    <string name="perm_item_notification">通知权限</string>
    <string name="perm_item_notification_detail">Android 13+ 运行时权限，以及应用通知总开关</string>
    <string name="perm_item_fsi">全屏通知</string>
    <string name="perm_item_fsi_detail">Android 14+ 可能默认未授予，需在「特殊权限设置」里手动开启</string>
    <string name="perm_item_battery">电池优化</string>
    <string name="perm_item_battery_detail">红米 / HyperOS 必须设为「无限制」，否则后台被杀、闹铃不响</string>
    <string name="perm_item_alarm">重复提醒闹钟</string>
    <string name="perm_item_alarm_detail">使用 setAlarmClock（用户可见闹钟），无需额外权限，低电耗模式下也能准时触发</string>
    <string name="status_permission_warning">! 有 %1$d 项权限未就绪 —— 点下方清单去处理</string>
    <string name="notif_permission_denied">通知权限未开启，提醒无法送达</string>
</resources>
```

### 6.11 `app/src/main/java/com/saiga/find/messagefinder/AlertDispatcher.java`　★新增 · 统一调度入口

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构的核心调度器：所有「要提醒」的请求都从这里进。
//
// 旧链路（v3）：SMSReceiver -> startForegroundService -> 服务里 Handler 等 5 分钟再响。
//   在 targetSdk 31+ 上，第一步就会被系统以 ForegroundServiceStartNotAllowedException 拒绝；
//   即使侥幸起来，服务被回收后 Handler 消失，重复提醒也就断了。
// 新链路（v4）：
//   SMSReceiver / RepeatAlarmReceiver
//     -> AlertDispatcher.postRound() 发高优先级通知（自带闹铃音 + 全屏 Intent）
//     -> 系统拉起 AlertActivity（用户可见）
//     -> AlertActivity 合法启动 SMSForegroundService，播放这一轮铃声/震动
//     -> 单轮结束 -> AlertScheduler.setAlarmClock() 安排下一轮 -> 服务 stopSelf
//   全程没有任何「后台启动前台服务」，符合 Android 12~16 的行为变更要求。
//
// 设计参考：AOSP 系统闹钟（DeskClock）与 Android 官方《Notifications / Full-screen intents》
// 的通行做法——把「通知」当作唯一可靠的唤醒入口，把「播放」放在用户可见组件里。
// 本项目只借鉴该架构思想，未复制第三方代码。

import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class AlertDispatcher {

    private static final String TAG = "AlertDispatcher";

    private AlertDispatcher() {
        // 工具类不允许实例化
    }

    /**
     * 收到一条命中规则的短信：开启新会话并发出第一轮提醒。
     */
    public static void onSmsMatched(Context context, String sender, String keyword,
                                    String person, String body) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            // 新短信到来时取消上一轮尚未触发的闹钟，避免两条短信的提醒交织叠加
            AlertScheduler.cancel(app); // modified: 新增
            AlertSession session = AlertSession.get(app);
            session.start(sender, keyword, person, body,
                    AppConfig.get(app).getTotalRounds()); // modified: 新增
            postRound(app, 1, app.getString(R.string.notif_status_round, 1));
            scheduleFollowUp(app, 1); // modified: 乐观调度，服务没起来也能有下一轮
        } catch (Exception e) {
            Log.e(TAG, "dispatch sms alert failed", e);
        }
    }

    /**
     * 精确闹钟到点：发出下一轮提醒。
     *
     * @param round 即将开始的轮次
     */
    public static void onRepeatAlarm(Context context, int round) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertSession session = AlertSession.get(app);
            if (!session.isActive()) {
                Log.i(TAG, "session already stopped, skip repeat");
                return;
            }
            int total = session.getTotalRounds();
            if (total > 0 && round > total) {
                finish(app, Math.max(1, round - 1));
                return;
            }
            session.setRound(round);
            postRound(app, round, app.getString(R.string.notif_status_round, round));
            scheduleFollowUp(app, round); // modified: 每一轮发出通知时就挂好下一轮闹钟
        } catch (Exception e) {
            Log.e(TAG, "dispatch repeat alert failed", e);
        }
    }

    /**
     * 用户点「停止」：取消闹钟、结束会话、撤通知、并让正在播放的服务停下来。
     */
    public static void stop(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertScheduler.cancel(app);
            AlertSession.get(app).stop();
            NotificationHelper.cancel(app);
            requestServiceStop(app);
        } catch (Exception e) {
            Log.e(TAG, "stop alert failed", e);
        }
    }

    /**
     * 用户点「稍后提醒」：立即静音，过一个间隔再响一轮。
     *
     * <p>与「停止」的区别：会话保持 active，且 {@link AlertSession#ensureRound(int)}
     * 保证即使用完了重复次数，用户主动要求的这一轮也一定会响。
     */
    public static void snooze(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, true);
    }

    /**
     * 供服务自身处理 v3 残留通知的「稍后提醒」时使用。
     * 服务已经自行停止，不需要（也不应该）再给自己发一次 startService，
     * 否则 stopSelf 之后又收到启动请求会触发一次无意义的 onCreate / onDestroy。
     */
    public static void snoozeFromService(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, false);
    }

    private static void snoozeInternal(Context context, int delayMinutes,
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            final int safeDelay = Math.max(1, delayMinutes);
            AlertScheduler.cancel(app);
            AlertSession session = AlertSession.get(app);
            session.setActive(true);
            int nextRound = session.getRound() + 1;
            session.ensureRound(nextRound);
            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            if (stopService) {
                requestServiceStop(app);
            }
            // 用静音渠道覆盖通知：只改文案，不再重复出声
            postQuiet(app, app.getString(R.string.notif_status_snoozed, safeDelay));
        } catch (Exception e) {
            Log.e(TAG, "snooze alert failed", e);
        }
    }

    /**
     * 所有轮次结束：留一条「已结束」通知供用户回看，并停掉闹钟与服务。
     */
    public static void finish(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, true);
    }

    /** 服务自己收尾时调用，避免给自己发 STOP 造成无意义的重启。 */
    public static void finishFromService(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, false);
    }

    private static void finishInternal(Context context, int rounds,
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            AlertScheduler.cancel(app);
            AlertSession.get(app).stop();
            CharSequence status = rounds > 1
                    ? app.getString(R.string.notif_status_done, rounds)
                    : app.getString(R.string.notif_status_done_once);
            postQuiet(app, status);
            if (stopService) {
                requestServiceStop(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "finish alert failed", e);
        }
    }

    /**
     * 发通知的同时就把下一轮闹钟挂好。
     *
     * <p>这一步是**兜底**，解决「亮屏时用户没点通知 -> 全屏页没弹出 -> 服务没启动 ->
     * 就没人负责安排下一轮」的断链。闹钟由系统托管，进程被杀也不受影响。
     *
     * <p>如果服务正常启动并播完这一轮，它会用同一个 requestCode 的 PendingIntent
     * 再覆盖一次闹钟，把计时起点校正为「响完的那一刻」，因此不会出现双重提醒。
     */
    private static void scheduleFollowUp(Context app, int justPostedRound) { // modified: 新增
        AppConfig config = AppConfig.get(app);
        int interval = config.getRepeatIntervalMin();
        int duration = config.getRingDurationSec();
        int total = AlertSession.get(app).getTotalRounds();
        // modified: 与「单轮播完后」用同一套规则，避免两个组件对「还有没有下一轮」判断不一致
        if (!RoundPolicy.hasNextRound(justPostedRound, total, duration, interval)) {
            return;
        }
        AlertScheduler.scheduleNext(app, interval, justPostedRound + 1);
    }

    /**
     * 通知「还有下一轮」：把通知改成「已提醒 N 次 · M 分钟后再次提醒」。
     * 由服务在单轮响铃结束时调用。
     */
    public static void markWaiting(Context context, int finishedRound,
                                   int intervalMinutes) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            postQuiet(app, app.getString(R.string.notif_status_wait,
                    Math.max(1, finishedRound), Math.max(1, intervalMinutes)));
        } catch (Exception e) {
            Log.e(TAG, "mark waiting failed", e);
        }
    }

    /** 发一轮「带闹铃音 + 全屏 Intent」的通知。这是整个应用最可靠的唤醒入口。 */
    private static void postRound(Context app, int round, CharSequence status) { // modified: 新增
        AlertSession session = AlertSession.get(app);
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_AUDIBLE,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status);
        Log.d(TAG, "posted alert notification, round=" + round);
    }

    /** 发一条静音渠道的通知（只更新文案，不再出声）。 */
    private static void postQuiet(Context app, CharSequence status) { // modified: 新增
        AlertSession session = AlertSession.get(app);
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status);
    }

    /**
     * 让正在前台播放的服务停止。
     *
     * <p>服务此时通常已经在前台运行，向它追加一个 action 是允许的；若服务已被回收，
     * Android 8+ 会抛「不允许后台启动服务」的异常，捕获即可——会话状态已经在调用方
     * 置为 inactive，闹钟也已取消，不会影响正确性。
     */
    private static void requestServiceStop(Context app) { // modified: 新增
        try {
            Intent intent = new Intent(app, SMSForegroundService.class);
            intent.setAction(SMSForegroundService.ACTION_STOP_ALERT);
            app.startService(intent);
        } catch (Exception e) {
            Log.d(TAG, "service already stopped, ignore", e);
        }
    }

    private static Context app(Context context) {
        return context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }
}
```

### 6.12 `app/src/main/java/com/saiga/find/messagefinder/AlertSession.java`　★新增 · 会话状态持久化

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：一次「提醒会话」的共享状态。
// 为什么需要它：targetSdk 36 下短信广播不能再启动前台服务，主链路改为
// 「通知 -> 全屏页 -> 单轮服务 -> 精确闹钟 -> 再发通知」。这些组件可能分属不同进程、
// 也可能在闹钟到来时进程已被回收，轮次与短信内容必须持久化，不能放在服务内存里。
//
// 设计参考：Android 官方《Background work / Schedule alarms》中「把任务状态与
// 触发者解耦」的做法，以及系统闹钟应用 Clock 用 SharedPreferences 记录下一次响铃信息的思路。
// 本项目只借用思想，未复制任何第三方代码。

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 提醒会话状态。使用独立的 SharedPreferences 文件，避免与用户配置混在一起。
 *
 * <p>写入统一使用 {@code commit()}（而不是 {@code apply()}）：闹钟广播接收器可能在
 * 另一个进程被立刻拉起，{@code apply()} 的异步落盘会造成「读到的还是上一轮」的竞态。
 */
public final class AlertSession {

    private static final String PREF_NAME = "alert_session_v4"; // modified: 新增
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_ROUND = "round";
    private static final String KEY_TOTAL = "total_rounds";
    private static final String KEY_FROM = "from";
    private static final String KEY_KEYWORD = "keyword";
    private static final String KEY_PERSON = "person";
    private static final String KEY_BODY = "body";

    private final SharedPreferences prefs;

    private AlertSession(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** 获取会话门面。ApplicationContext 避免持有 Activity。 */
    public static AlertSession get(Context context) { // modified: 新增
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new AlertSession(app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    /**
     * 开启一次新会话（收到一条新的命中短信）。
     *
     * @param totalRounds 计划总轮次；{@code <=0} 表示不限次数
     */
    public void start(String from, String keyword, String person, String body,
                      int totalRounds) { // modified: 新增
        prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putInt(KEY_ROUND, 1)
                .putInt(KEY_TOTAL, Math.max(0, totalRounds))
                .putString(KEY_FROM, safe(from))
                .putString(KEY_KEYWORD, safe(keyword))
                .putString(KEY_PERSON, safe(person))
                .putString(KEY_BODY, safe(body))
                .commit();
    }

    /** @return 会话是否仍在等待用户处理 */
    public boolean isActive() { // modified: 新增
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    /** 用户点了「停止」：只把 active 置 false，内容保留给通知展示。 */
    public void stop() { // modified: 新增
        prefs.edit().putBoolean(KEY_ACTIVE, false).commit();
    }

    /** 「稍后提醒」时重新激活会话（用户明确表示这件事还没处理完）。 */
    public void setActive(boolean active) { // modified: 新增
        prefs.edit().putBoolean(KEY_ACTIVE, active).commit();
    }

    /** @return 当前（或最近一次）提醒轮次，从 1 开始 */
    public int getRound() { // modified: 新增
        return Math.max(1, prefs.getInt(KEY_ROUND, 1));
    }

    public void setRound(int round) { // modified: 新增
        prefs.edit().putInt(KEY_ROUND, Math.max(1, round)).commit();
    }

    /** @return 计划总轮次；0 表示不限次数 */
    public int getTotalRounds() { // modified: 新增
        return Math.max(0, prefs.getInt(KEY_TOTAL, 1));
    }

    /**
     * 保证第 {@code round} 轮一定会被执行。
     * 「稍后提醒」即使用户已经用完重复次数，也至少要再响一次。
     */
    public void ensureRound(int round) { // modified: 新增
        int safeRound = Math.max(1, round);
        int total = getTotalRounds();
        if (total > 0 && safeRound > total) {
            prefs.edit().putInt(KEY_TOTAL, safeRound).commit();
        }
    }

    public String getFrom() { // modified: 新增
        return safe(prefs.getString(KEY_FROM, ""));
    }

    public String getKeyword() { // modified: 新增
        return safe(prefs.getString(KEY_KEYWORD, ""));
    }

    public String getPerson() { // modified: 新增
        return safe(prefs.getString(KEY_PERSON, ""));
    }

    public String getBody() { // modified: 新增
        return safe(prefs.getString(KEY_BODY, ""));
    }

    private static String safe(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
    }
}
```

### 6.13 `app/src/main/java/com/saiga/find/messagefinder/AlertScheduler.java`　★新增 · setAlarmClock 精确闹钟

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：重复提醒改由「用户可见闹钟」驱动。
//
// 为什么不再用前台服务里的 Handler.postDelayed 等 5 分钟：
//  1) targetSdk 36 下后台启动前台服务受严格限制，服务随时可能被回收，Handler 随之消失，
//     「5 分钟后再响」就永远不响了；
//  2) Android 14+ 的 shortService 前台服务只有约 3 分钟时限，长驻等待会触发 onTimeout / ANR；
//  3) Doze 低电耗模式下 Handler 会被推迟，系统闹钟才是最可靠的计时手段。
//
// 实现依据：Android 官方《Schedule alarms》—— setAlarmClock() 属于「用户可见闹钟」，
// 不受 Android 12+ SCHEDULE_EXACT_ALARM 权限限制，且允许在低电耗模式下按时唤醒；
// 系统会在状态栏显示闹钟图标，语义与本应用「像闹钟一样提醒」完全一致。
// 因此本工程遵循最小权限原则，不声明 SCHEDULE_EXACT_ALARM。

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 重复提醒闹钟的唯一调度入口。 */
public final class AlertScheduler {

    private static final String TAG = "AlertScheduler";
    public static final String ACTION_REPEAT = "com.saiga.find.messagefinder.action.REPEAT_ALERT"; // modified: 新增
    public static final String EXTRA_ROUND = "alert_round"; // modified: 新增

    private static final int REQ_REPEAT = 41;
    private static final int REQ_SHOW = 42;

    private AlertScheduler() {
        // 工具类不允许实例化
    }

    /**
     * 安排下一轮提醒。
     *
     * @param context      任意 Context
     * @param delayMinutes 间隔（分钟），最小按 1 分钟处理
     * @param nextRound    下一轮的轮次，从 1 开始
     */
    public static void scheduleNext(Context context, int delayMinutes, int nextRound) { // modified: 新增
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager unavailable");
            return;
        }
        final long triggerAtMillis = System.currentTimeMillis()
                + Math.max(1, delayMinutes) * 60_000L;
        final int safeRound = Math.max(1, nextRound);
        try {
            PendingIntent operation = buildRepeatIntent(app, safeRound);

            // AlarmClockInfo 的 showIntent：用户点状态栏闹钟图标时打开主界面。
            // targetSdk 34+ 要求发往非导出组件的 Intent 必须是显式的，这里显式指定 MainActivity。
            Intent show = new Intent(app, MainActivity.class);
            show.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showIntent = PendingIntent.getActivity(app, REQ_SHOW, show,
                    pendingFlags()); // modified: FLAG_IMMUTABLE 合规

            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation);
            Log.d(TAG, "next alert scheduled, round=" + safeRound
                    + ", in " + Math.max(1, delayMinutes) + " min");
        } catch (Exception e) {
            // 个别定制 ROM 会对 setAlarmClock 抛 SecurityException / NullPointerException。
            // 降级为不精确闹钟：宁可晚几分钟，也不要彻底不响。
            Log.e(TAG, "setAlarmClock failed, degrade to inexact alarm", e);
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis,
                        buildRepeatIntent(app, safeRound));
            } catch (Exception fallbackError) {
                Log.e(TAG, "inexact alarm also failed", fallbackError);
            }
        }
    }

    /** 取消尚未触发的下一轮提醒。用户点「停止」、收到新短信时都要调用。 */
    public static void cancel(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                // PendingIntent 等价性忽略 extras，因此 round 传 1 也能取消任意轮次的闹钟
                alarmManager.cancel(buildRepeatIntent(app, 1));
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel repeat alarm failed", e);
        }
    }

    private static PendingIntent buildRepeatIntent(Context app, int round) { // modified: 新增
        Intent intent = new Intent(app, RepeatAlarmReceiver.class);
        intent.setAction(ACTION_REPEAT);
        intent.putExtra(EXTRA_ROUND, round);
        return PendingIntent.getBroadcast(app, REQ_REPEAT, intent, pendingFlags());
    }

    /** Android 12+ 必须显式声明 PendingIntent 可变性。 */
    private static int pendingFlags() { // modified: 新增
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
```

### 6.14 `app/src/main/java/com/saiga/find/messagefinder/RepeatAlarmReceiver.java`　★新增 · 闹钟广播接收器

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：精确闹钟到点后的入口。
// 这里只做「重新发一条高优先级通知」，绝不启动前台服务——因为广播接收器处于后台，
// targetSdk 31+ 从后台 startForegroundService 会抛 ForegroundServiceStartNotAllowedException。
// 通知会带全屏 Intent，锁屏/息屏时由系统拉起 AlertActivity；Activity 处于用户可见状态，
// 此时再由它启动前台服务播放铃声才符合系统豁免规则。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RepeatAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "RepeatAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null) {
            return;
        }
        try {
            if (!AlertScheduler.ACTION_REPEAT.equals(intent.getAction())) {
                return;
            }
            int round = Math.max(1, intent.getIntExtra(AlertScheduler.EXTRA_ROUND, 1));
            AlertDispatcher.onRepeatAlarm(context, round);
        } catch (Exception e) {
            // 广播接收器里的异常会直接弹「应用已停止运行」
            Log.e(TAG, "repeat alarm handling failed", e);
        }
    }
}
```

### 6.15 `app/src/main/java/com/saiga/find/messagefinder/AlertActionReceiver.java`　★新增 · 通知动作广播接收器

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 合规改造：通知上的「停止 / 稍后提醒」改为广播入口。
// 原实现用 PendingIntent.getService 直接启动服务；targetSdk 31+ 从后台（通知按钮属于后台）
// 调用 startService / startForegroundService 同样受后台启动限制，可能静默失败。
// 广播接收器不受该限制，由它统一改状态、取消闹钟，再尝试通知正在前台的服务停下来。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AlertActionReceiver extends BroadcastReceiver {

    private static final String TAG = "AlertActionReceiver";
    public static final String ACTION_STOP = "com.saiga.find.messagefinder.action.STOP_ALERT"; // modified: 新增
    public static final String ACTION_SNOOZE = "com.saiga.find.messagefinder.action.SNOOZE_ALERT"; // modified: 新增

    /** 用户把重复间隔设为 0（不重复）时，点「稍后提醒」的兜底间隔。 */
    private static final int FALLBACK_SNOOZE_MINUTES = 5;

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        try {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                AlertDispatcher.stop(context);
            } else if (ACTION_SNOOZE.equals(action)) {
                int minutes = RoundPolicy.safeDelayMinutes(
                        AppConfig.get(context).getRepeatIntervalMin(),
                        FALLBACK_SNOOZE_MINUTES); // modified: 规则集中
                AlertDispatcher.snooze(context, minutes);
            } else {
                Log.w(TAG, "unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle notification action failed", e);
        }
    }
}
```

### 6.16 `app/src/main/java/com/saiga/find/messagefinder/PermissionChecker.java`　★新增 · 权限体检

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 合规改造：「权限体检」。
// targetSdk 36 之后，能否正常提醒取决于一组分散在系统各处的开关：
//   运行时权限(RECEIVE_SMS / POST_NOTIFICATIONS) + 通知总开关 + Android 14 的全屏 Intent 特殊权限
//   + 厂商电池优化白名单（红米 HyperOS 上这一项实际上最关键）。
// 缺任意一项都会表现为「短信到了却不响」，而用户完全没有线索。这里把状态集中展示并给出跳转。
//
// 跳转方式参考 Android 官方 Settings 常量与《Full-screen intent limits》(AOSP) 文档，
// 未复制第三方代码。

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class PermissionChecker {

    private static final String TAG = "PermissionChecker";
    /** Android 14 的「管理全屏 Intent」设置页 action（用字面量以兼容低于 34 的编译/运行环境）。 */
    private static final String ACTION_MANAGE_FSI = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"; // modified: 新增

    private PermissionChecker() {
        // 工具类不允许实例化
    }

    /** 一项权限检查结果。 */
    public static final class Item {
        public final String title;
        public final boolean ok;
        public final String detail;
        public final Intent fix;

        private Item(String title, boolean ok, String detail, Intent fix) {
            this.title = title;
            this.ok = ok;
            this.detail = detail;
            this.fix = fix;
        }
    }

    /** 汇总所有会影响提醒是否生效的系统开关。 */
    public static List<Item> check(Context context) { // modified: 新增
        List<Item> items = new ArrayList<>();
        if (context == null) {
            return items;
        }
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        Intent appDetails = appDetailsIntent(pkg);

        // 1) 接收短信（dangerous 权限）
        boolean sms = ContextCompat.checkSelfPermission(app, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED;
        items.add(new Item(app.getString(R.string.perm_item_sms), sms,
                app.getString(R.string.perm_item_sms_detail), appDetails));

        // 2) 通知：Android 13+ 的运行时权限 + 应用通知总开关
        boolean notif = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notif = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        try {
            notif = notif && NotificationManagerCompat.from(app).areNotificationsEnabled();
        } catch (Exception e) {
            Log.w(TAG, "check notification enabled failed", e);
        }
        items.add(new Item(app.getString(R.string.perm_item_notification), notif,
                app.getString(R.string.perm_item_notification_detail),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? notificationSettingsIntent(pkg) : appDetails));

        // 3) 全屏 Intent（Android 14+ 不再默认授予）
        boolean fsi = true;
        Intent fsiFix = appDetails;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                NotificationManager nm =
                        (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                fsi = nm != null && nm.canUseFullScreenIntent();
            } catch (Exception e) {
                Log.w(TAG, "canUseFullScreenIntent failed", e);
            }
            fsiFix = fsiSettingsIntent(pkg);
        }
        items.add(new Item(app.getString(R.string.perm_item_fsi), fsi,
                app.getString(R.string.perm_item_fsi_detail), fsiFix));

        // 4) 电池优化白名单：红米 / HyperOS 上不设「无限制」会在后台被杀
        boolean battery = true;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            battery = pm == null || pm.isIgnoringBatteryOptimizations(pkg);
        } catch (Exception e) {
            Log.w(TAG, "check battery optimization failed", e);
        }
        items.add(new Item(app.getString(R.string.perm_item_battery), battery,
                app.getString(R.string.perm_item_battery_detail),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        // 5) 精确闹钟：用 setAlarmClock（用户可见闹钟），无需额外授权，仅作说明
        items.add(new Item(app.getString(R.string.perm_item_alarm), true,
                app.getString(R.string.perm_item_alarm_detail), null));

        return items;
    }

    /** @return 未就绪的项目数，用于主界面状态条提示 */
    public static int missingCount(Context context) { // modified: 新增
        int count = 0;
        for (Item item : check(context)) {
            if (!item.ok) {
                count++;
            }
        }
        return count;
    }

    /**
     * 弹出权限体检对话框。点击未就绪的条目直接跳到对应的系统设置页。
     */
    public static void showDialog(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        final List<Item> items = check(context);
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            labels[i] = (item.ok ? "✓ " : "! ") + item.title + "\n" + item.detail;
        }
        try {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.permission_check_title)
                    .setItems(labels, (dialog, which) -> {
                        if (which < 0 || which >= items.size()) {
                            return;
                        }
                        Item item = items.get(which);
                        if (item.ok || item.fix == null) {
                            return;
                        }
                        try {
                            item.fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(item.fix);
                        } catch (Exception e) {
                            Log.w(TAG, "open permission settings failed", e);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Exception e) {
            // Activity 正在销毁时弹窗会抛 WindowManager$BadTokenException
            Log.w(TAG, "show permission dialog failed", e);
        }
    }

    private static Intent appDetailsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + pkg));
        return intent;
    }

    private static Intent notificationSettingsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
        return intent;
    }

    private static Intent fsiSettingsIntent(String pkg) { // modified: 新增
        Intent intent = new Intent(ACTION_MANAGE_FSI);
        intent.setData(Uri.parse("package:" + pkg));
        return intent;
    }
}
```

### 6.17 `app/src/main/java/com/saiga/find/messagefinder/EdgeToEdgeHelper.java`　★新增 · 边到边 insets 适配

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] targetSdk 35(Android 15)+ 强制 edge-to-edge，
// Android 16(targetSdk 36) 已彻底移除 windowOptOutEdgeToEdgeEnforcement 这一临时退出开关。
// 不做适配的话，状态栏/导航栏会盖住标题与底部按钮，尤其在挖孔屏与手势导航下更明显。
//
// 做法参考 Android 官方《Edge-to-edge / Handle insets》：
//   setDecorFitsSystemWindows(false) + OnApplyWindowInsetsListener 给根布局补 padding。
// 只借用官方推荐的通用写法，未复制第三方代码。

import android.app.Activity;
import android.util.Log;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public final class EdgeToEdgeHelper {

    private static final String TAG = "EdgeToEdgeHelper";

    private EdgeToEdgeHelper() {
        // 工具类不允许实例化
    }

    /**
     * 让内容避开系统栏与屏幕挖孔。
     *
     * @param activity   目标页面
     * @param rootViewId 根布局 id（通常是 setContentView 传入布局的最外层 View）
     */
    public static void apply(Activity activity, int rootViewId) { // modified: 新增
        if (activity == null) {
            return;
        }
        try {
            WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
            View root = activity.findViewById(rootViewId);
            if (root == null) {
                return;
            }
            // 保留布局里原有的 padding，只把系统栏 insets 叠加上去
            final int left = root.getPaddingLeft();
            final int top = root.getPaddingTop();
            final int right = root.getPaddingRight();
            final int bottom = root.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(left + bars.left, top + bars.top,
                        right + bars.right, bottom + bars.bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(root);
        } catch (Exception e) {
            // 个别 ROM 的 WindowCompat 实现可能抛异常，不能因为视觉适配把页面搞崩
            Log.w(TAG, "apply edge-to-edge insets failed", e);
        }
    }
}
```

### 6.18 `app/src/main/java/com/saiga/find/messagefinder/RoundPolicy.java`　★新增 · 轮次纯计算规则

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：提醒轮次的纯计算规则。
//
// 轮次判断原先散落在 SMSForegroundService 内部（依赖 Android Context，无法在 JVM 上测试），
// 重构后「收到短信」「闹钟到点」「单轮播完」「用户稍后提醒」四个组件都要独立判断
// 「还有没有下一轮」。规则一旦出现分歧，最典型的后果就是重复提醒多响一次或干脆不响。
// 因此抽成无 Android 依赖的纯 Java 类，集中约束 + 可用 JUnit 直接验证。

/** 提醒轮次与间隔的统一计算规则。 */
public final class RoundPolicy {

    private RoundPolicy() {
        // 工具类不允许实例化
    }

    /**
     * 计算一次会话的计划总轮次。
     *
     * @param durationSec 单轮响铃时长（秒）；{@code <=0} 表示一直响到用户停止
     * @param intervalMin 重复间隔（分钟）；{@code <=0} 表示不重复
     * @param maxRepeat   最多重复次数；{@code <=0} 表示不限
     * @return 总轮次；{@code 0} 表示不限次数
     */
    public static int totalRounds(int durationSec, int intervalMin,
                                  int maxRepeat) { // modified: 新增
        if (durationSec <= 0 || intervalMin <= 0) {
            return 1;
        }
        return maxRepeat <= 0 ? 0 : Math.max(1, maxRepeat + 1);
    }

    /**
     * 当前这轮结束后是否还有下一轮。
     *
     * @param currentRound 刚结束/刚发出的轮次，从 1 开始
     * @param totalRounds  {@link #totalRounds(int, int, int)} 的结果，{@code <=0} 表示不限
     */
    public static boolean hasNextRound(int currentRound, int totalRounds,
                                       int durationSec, int intervalMin) { // modified: 新增
        if (durationSec <= 0 || intervalMin <= 0) {
            return false;
        }
        if (totalRounds <= 0) {
            return true;
        }
        return Math.max(1, currentRound) < totalRounds;
    }

    /**
     * 规范化「稍后提醒」的间隔。用户把重复间隔设为 0（不重复）时，
     * 主动点「稍后提醒」仍应给一个合理的兜底间隔，否则会变成永远不响。
     */
    public static int safeDelayMinutes(int configured, int fallback) { // modified: 新增
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, fallback);
    }
}
```

### 6.19 `app/src/main/java/com/saiga/find/messagefinder/NotificationHelper.java`　重写 · 双渠道 + FSI 检测 + 广播动作

```java
package com.saiga.find.messagefinder;

// [v4 重写 / modified] 通知与通知渠道的唯一构建入口。
//
// v3 的定位是「服务内的通知 + 服务起不来时的降级通知」；
// v4 架构重构后，通知变成**主链路的唯一唤醒入口**：
//   · 广播接收器（后台）只发通知，绝不启动前台服务；
//   · 通知自带闹铃音 + 全屏 Intent，锁屏/息屏时拉起 AlertActivity；
//   · 用户可见的 Activity 才启动前台服务，把通知换到静音渠道后由 MediaPlayer 独占响铃。
//
// 因此需要两个渠道，且语义与 v3 相反：
//   CHANNEL_ID_AUDIBLE（带闹铃音）：广播/闹钟发出的「第一声」，保证服务起不来也能响；
//   CHANNEL_ID_ALERT  （静音）    ：服务已接手播放后使用，避免通知音 + MediaPlayer 双重响铃。
// 渠道声音一旦创建不可修改，所以换用全新的 ID，而不是去改老用户设备上已存在的渠道。
//
// 参考：Android 官方《Create a notification / Full-screen intents》《Full-screen intent limits》(AOSP)。
// 未复制第三方代码。

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** 优先级提醒通知构建器。 */
public final class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    /** 静音渠道：服务已接手播放铃声时使用，防止双重响铃。 */
    public static final String CHANNEL_ID_ALERT = "priority_alert_v2"; // modified: 沿用 v3 渠道
    /** 带闹铃音的主提醒渠道：v4 新 ID，避免旧渠道声音不可变的历史包袱。 */
    public static final String CHANNEL_ID_AUDIBLE = "priority_alert_audible_v4"; // modified: 新增
    /** 兼容旧调用点的别名（v3 的「降级渠道」语义已废弃）。 */
    public static final String CHANNEL_ID_FALLBACK = CHANNEL_ID_AUDIBLE; // modified: 改为别名

    /** 通知 ID，保持与原实现一致，方便用户清理时对应。 */
    public static final int NOTIFICATION_ID = 123;

    private static final int REQ_CONTENT = 10;
    private static final int REQ_STOP = 11;
    private static final int REQ_SNOOZE = 12;
    private static final int REQ_FULLSCREEN = 13;

    private static final String EXTRA_FROM = "From";
    private static final String EXTRA_KEYWORD = "Keyword";
    private static final String EXTRA_PAYLOAD = "Payload";
    private static final String EXTRA_PERSON = "Person";

    private NotificationHelper() {
        // 工具类不允许实例化
    }

    /** 创建（或更新）两个通知渠道。重复调用是安全的。 */
    public static void ensureChannels(Context context) { // modified: 新增带声音的主渠道
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription(context.getString(R.string.channel_desc));
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alertChannel.setSound(null, null); // modified: 服务播放铃声时不出通知音
            alertChannel.enableVibration(false);
            alertChannel.setBypassDnd(true);
            manager.createNotificationChannel(alertChannel);

            NotificationChannel audibleChannel = new NotificationChannel(
                    CHANNEL_ID_AUDIBLE,
                    context.getString(R.string.channel_name_audible),
                    NotificationManager.IMPORTANCE_HIGH);
            audibleChannel.setDescription(context.getString(R.string.channel_desc_audible));
            audibleChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            audibleChannel.setSound(alarmSound, audioAttributes); // modified: 广播阶段自带闹铃音
            audibleChannel.enableVibration(true);
            audibleChannel.setBypassDnd(true);
            manager.createNotificationChannel(audibleChannel);
        } catch (Exception e) {
            // 渠道创建失败不应影响主流程（例如部分定制 ROM 对 setBypassDnd 抛异常）
            Log.e(TAG, "create notification channel failed", e);
        }
    }

    /** 构建提醒通知（不带轮次状态）。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body) {
        return build(context, channelId, sender, keyword, personName, body, null);
    }

    /** 构建提醒通知，并附带一行「第几次提醒 / 几分钟后再响 / 已结束」。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status) { // modified: 全屏 Intent 权限检测 + 广播动作
        String displaySender = TextUtils.isEmpty(personName) ? safeText(sender) : personName;
        String displayKeyword = safeText(keyword);
        String displayBody = safeText(body);

        CharSequence summaryText = TextUtils.isEmpty(status)
                ? context.getString(R.string.notif_summary) : status;
        NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(context.getString(R.string.notif_title))
                .bigText(context.getString(R.string.notif_bigtext, displayBody, displaySender))
                .setSummaryText(summaryText);

        // 注意：刻意不使用 setOnlyAlertOnce(true)。每一轮重复提醒都需要重新出声，
        // 加上该标志会导致第二轮通知静默。
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(context.getString(R.string.notif_title))
                .setContentText(context.getString(R.string.notif_text, displayKeyword, displaySender))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setColor(context.getResources().getColor(R.color.secondaryDarkColor, null))
                .setColorized(true)
                .setShowWhen(false)
                .setAutoCancel(false)
                .setOngoing(false)
                .setStyle(bigTextStyle)
                .setSmallIcon(R.drawable.notification_mf_icon)
                // modified: 点击通知正文 -> 打开全屏告警页（进而合法启动服务响铃），
                // 而不是只打开主界面；亮屏状态下这是用户获得强提醒的入口。
                .setContentIntent(buildAlertScreenIntent(context, REQ_CONTENT,
                        sender, keyword, personName, body));
        if (!TextUtils.isEmpty(status)) {
            builder.setSubText(status);
        }
        builder.addAction(R.drawable.ic_block_24dp,
                context.getString(R.string.stop), buildStopIntent(context));
        builder.addAction(0,
                context.getString(R.string.notif_snooze), buildSnoozeIntent(context));
        builder.setDeleteIntent(buildStopIntent(context)); // modified: 划掉通知 = 停止提醒

        // modified: Android 14+ 的 USE_FULL_SCREEN_INTENT 可能未授予。
        // 未授予时不设置全屏 Intent，让系统退化为悬挂通知（HUN），用户点击可进入告警页；
        // 强行设置不仅无效，部分 ROM 还会抛异常。
        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(buildAlertScreenIntent(context, REQ_FULLSCREEN,
                    sender, keyword, personName, body), true);
        }
        return builder.build();
    }

    /**
     * 发（或更新）通知。这是所有组件发通知的唯一出口。
     *
     * <p>统一处理 targetSdk 33+ 的 POST_NOTIFICATIONS：未授权时 AndroidX 会抛
     * SecurityException，通知总开关被关时直接放弃并记日志——两种情况都不能让应用崩溃。
     */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status) { // modified: 新增统一出口
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(app);
            if (!manager.areNotificationsEnabled()) {
                Log.w(TAG, "notifications disabled by user/system, skip posting");
                return;
            }
            ensureChannels(app);
            manager.notify(NOTIFICATION_ID,
                    build(app, channelId, sender, keyword, person, body, status));
        } catch (SecurityException e) {
            Log.e(TAG, "POST_NOTIFICATIONS not granted, cannot post alert", e);
        } catch (Exception e) {
            Log.e(TAG, "post notification failed", e);
        }
    }

    /** 撤销提醒通知。 */
    public static void cancel(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            NotificationManagerCompat.from(context.getApplicationContext())
                    .cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w(TAG, "cancel notification failed", e);
        }
    }

    /**
     * 兼容 v3 的降级入口（服务里铃声播放失败时调用）。
     * v4 中它不再是「降级」，而是主动换到带声音渠道再提醒一次。
     */
    public static void notifyDirectly(Context context, String sender, String keyword,
                                      String personName, String body) { // modified: 语义调整
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null);
    }

    /**
     * Android 14(API 34)+ 起，USE_FULL_SCREEN_INTENT 不再默认授予非「通话/闹钟」类应用。
     * 系统提供 canUseFullScreenIntent() 供应用自检，用户可在「特殊应用权限」里手动开启。
     */
    public static boolean canUseFullScreenIntent(Context context) { // modified: 新增
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true; // Android 13 及以下安装即授予
        }
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.canUseFullScreenIntent();
        } catch (Exception e) {
            Log.w(TAG, "canUseFullScreenIntent failed", e);
            return false;
        }
    }

    /** 「停止」动作：发广播，不再直接启动服务（后台启动服务同样受限）。 */
    private static PendingIntent buildStopIntent(Context context) { // modified: getService -> getBroadcast
        Intent intent = new Intent(context, AlertActionReceiver.class);
        intent.setAction(AlertActionReceiver.ACTION_STOP);
        return PendingIntent.getBroadcast(context, REQ_STOP, intent, pendingIntentFlags());
    }

    /** 「稍后提醒」动作。 */
    private static PendingIntent buildSnoozeIntent(Context context) { // modified: getService -> getBroadcast
        Intent intent = new Intent(context, AlertActionReceiver.class);
        intent.setAction(AlertActionReceiver.ACTION_SNOOZE);
        return PendingIntent.getBroadcast(context, REQ_SNOOZE, intent, pendingIntentFlags());
    }

    /**
     * 全屏告警页的 PendingIntent。显式 Intent（targetSdk 34+ 对隐式 Intent 有严格限制）。
     *
     * @param requestCode 内容点击与全屏 Intent 用不同 requestCode，避免 PendingIntent 互相覆盖
     */
    private static PendingIntent buildAlertScreenIntent(Context context, int requestCode,
                                                        String sender, String keyword,
                                                        String person, String body) { // modified: 新增
        Intent intent = new Intent(context, AlertActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        putAlertExtras(intent, sender, keyword, person, body);
        return PendingIntent.getActivity(context, requestCode, intent, pendingIntentFlags());
    }

    /** Android 12+ 必须显式声明 PendingIntent 可变性。 */
    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static String safeText(String value) {
        return TextUtils.isEmpty(value) ? "" : value;
    }

    /** 打包「提醒请求」所需的 extras。 */
    static void putAlertExtras(Intent intent, String sender, String keyword,
                               String person, String body) {
        intent.putExtra(EXTRA_FROM, sender);
        intent.putExtra(EXTRA_KEYWORD, keyword);
        intent.putExtra(EXTRA_PERSON, person);
        intent.putExtra(EXTRA_PAYLOAD, body);
    }

    static String getFrom(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_FROM));
    }

    static String getKeyword(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_KEYWORD));
    }

    static String getPerson(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_PERSON));
    }

    static String getPayload(Intent intent) {
        return intent == null ? "" : safeText(intent.getStringExtra(EXTRA_PAYLOAD));
    }
}
```

### 6.20 `app/src/main/java/com/saiga/find/messagefinder/SMSReceiver.java`　改 · 移除后台启动前台服务

```java
package com.saiga.find.messagefinder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.util.Log;

import java.util.Set;

/**
 * 接收短信广播，按用户配置的“号码 + 关键词”规则判断是否需要触发优先提醒。
 *
 * <p>处理链路：号码过滤 → 长短信拼接 → 关键词匹配 → 重复投递去重 → 记录命中日志
 * → 交给 {@link AlertDispatcher} 发高优先级通知（v4 起不再在这里启动前台服务）。
 *
 * <p>本次修改的要点（均以 // modified 标注）：
 * <ul>
 *   <li>整个 onReceive 包 try/catch：广播接收器抛异常会直接弹“应用已停止运行”；</li>
 *   <li>发送方号码、短信正文做空值保护，去掉原来会 NPE 的 Objects.requireNonNull；</li>
 *   <li>长短信（>70 汉字会被拆包）先拼接再匹配，避免关键词跨包时漏报；</li>
 *   <li>同一短信被系统重复投递时去重，避免连续响铃；</li>
 *   <li>【v4·破坏性】移除 startForegroundService：targetSdk 31+ 不允许从短信广播
 *       启动前台服务（官方豁免清单不含 SMS_RECEIVED），改为统一交给 AlertDispatcher。</li>
 * </ul>
 */
public class SMSReceiver extends BroadcastReceiver {

    public static final String TAG = "SMSReceiver";

    /** 重复投递判定窗口：同一发送方 + 同一正文在 3 秒内视为同一条短信。 */
    private static final long DUPLICATE_WINDOW_MS = 3000L;

    /** 上一次处理过的短信指纹与时间戳，用于去重（广播接收器为单实例，静态字段即可）。 */
    private static String lastFingerprint = null;
    private static long lastFingerprintAt = 0L;

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 整体异常兜底
        if (context == null || intent == null) {
            Log.w(TAG, "onReceive with null context/intent, ignored");
            return;
        }
        try {
            handleSms(context, intent);
        } catch (Exception e) {
            // 广播接收器内未捕获的异常会直接导致进程崩溃（用户看到“应用已停止运行”）
            Log.e(TAG, "handle sms failed", e);
        }
    }

    private void handleSms(Context context, Intent intent) {
        AppConfig config = AppConfig.get(context);
        Set<String> keywords = config.getKeywords();
        if (keywords.isEmpty()) {
            // 用户没有配置关键词：直接忽略，不做任何处理（与原逻辑一致）
            Log.d(TAG, "no keyword configured, skip");
            return;
        }

        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            Log.w(TAG, "no sms message found in intent");
            return;
        }

        String sender = null;
        StringBuilder bodyBuilder = new StringBuilder();
        for (SmsMessage message : messages) {
            if (message == null) {
                continue;
            }
            if (sender == null) {
                sender = readOriginatingAddress(message);
            }
            String part = message.getDisplayMessageBody();
            if (part != null) {
                bodyBuilder.append(part); // modified: 长短信多包拼接，避免关键词跨包漏报
            }
        }
        String body = bodyBuilder.toString();
        if (TextUtils.isEmpty(body)) {
            Log.w(TAG, "empty sms body, skip");
            return;
        }

        // 号码过滤：未配置号码时表示“所有发送方都关注”
        if (!PhoneNumbers.matches(config.getContact(), sender)) {
            Log.d(TAG, "sender not matched, configured=" + config.getContact());
            return;
        }

        // 匹配模式跟着设置走：默认“任意命中”，开启后要求“全部关键词都出现”（借鉴短信铃铛）
        String matchedKeyword = KeywordMatcher.findMatch(
                keywords, body, config.isMatchAllEnabled()); // modified: 支持“全部命中”模式
        if (matchedKeyword == null) {
            Log.d(TAG, "no keyword matched, matchAll=" + config.isMatchAllEnabled());
            return;
        }

        if (isDuplicate(sender, body, matchedKeyword)) {
            Log.d(TAG, "duplicate sms within window, skip");
            return;
        }

        Log.i(TAG, "keyword matched: " + matchedKeyword + ", from " + sender);

        // 记录命中日志，便于用户在设置页自查规则是否生效
        HitLog.add(context, sender, matchedKeyword, body);

        // 通知里展示的姓名优先取用户从通讯录里选中的名字
        String personName = config.getContactName();

        // modified: v4 主链路。广播接收器处于后台，绝不能在这里启动前台服务；
        // 发通知是系统允许的高优先级行为，通知的全屏 Intent 会拉起用户可见的
        // AlertActivity，再由它启动服务播放铃声，全程符合 Android 12~16 的限制。
        AlertDispatcher.onSmsMatched(context, sender, matchedKeyword, personName, body);
    }

    /**
     * 读取发送方号码。部分机型/短信类型下 getDisplayOriginatingAddress 可能返回 null，
     * 原实现直接 Objects.requireNonNull 会抛 NullPointerException。
     */
    private String readOriginatingAddress(SmsMessage message) { // modified: 空值安全
        String address = null;
        try {
            address = message.getDisplayOriginatingAddress();
        } catch (Exception e) {
            Log.w(TAG, "read display address failed", e);
        }
        if (TextUtils.isEmpty(address)) {
            try {
                address = message.getOriginatingAddress();
            } catch (Exception e) {
                Log.w(TAG, "read raw address failed", e);
            }
        }
        return address == null ? "" : address;
    }

    /**
     * 判断是否为短时间内的重复投递。
     *
     * <p>短信广播在部分 ROM 上会被投递两次，原实现会导致同一条短信连续响两次。
     */
    private boolean isDuplicate(String sender, String body, String keyword) { // modified: 新增
        String fingerprint = (sender == null ? "" : sender) + '#' + body.length() + '#'
                + body.hashCode() + '#' + keyword;
        long now = System.currentTimeMillis();
        if (fingerprint.equals(lastFingerprint) && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
            return true;
        }
        lastFingerprint = fingerprint;
        lastFingerprintAt = now;
        return false;
    }
}
```

### 6.21 `app/src/main/java/com/saiga/find/messagefinder/SMSForegroundService.java`　重写 · 单轮播放器 + 闹钟交接

```java
package com.saiga.find.messagefinder;

// [v4 重写 / modified] 单轮提醒播放器。
//
// 职责变化（破坏性）：v3 里这个服务是「常驻总控」——持有 Handler 等 5 分钟、自己决定
// 下一轮什么时候响。v4 里它只负责**当前这一轮**：播放闹铃 + 震动，响满时长后把
// 下一轮交给 AlertScheduler 的精确闹钟，然后立刻 stopSelf()。
//
// 为什么要这样拆：
//  1) targetSdk 31+ 禁止从后台启动前台服务。现在服务只由全屏告警页（用户可见）启动，
//     属于官方列出的豁免情形，不再依赖短信广播；
//  2) Android 14+ 的 shortService 前台服务约 3 分钟就会被 onTimeout 甚至 ANR，
//     而本应用的重复间隔是 5 分钟，长驻服务在规范上根本走不通；
//  3) 服务被系统回收后 Handler 会消失，「5 分钟后再响」就永远不响；闹钟由系统托管，更可靠。
//  4) 前台服务类型改为 mediaPlayback（确实在播放音频），没有短时限，权限为安装即授予的普通权限。
//
// 参考：Android 官方《Foreground service types》《Restrictions on starting a foreground
// service from the background》《Schedule alarms》。未复制第三方代码。

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

public class SMSForegroundService extends Service implements MediaPlayer.OnPreparedListener {

    /** 启动一轮提醒的 action（保留原有接口，调用方仍是显式 Intent）。 */
    public static final String ACTION_START_ALERT = "com.start.foreground";
    /** 停止提醒的 action。 */
    public static final String ACTION_STOP_ALERT = "com.stop.foreground";
    /** 兼容 v3 残留通知的「稍后提醒」action。 */
    public static final String ACTION_SNOOZE_ALERT = "com.snooze.foreground";

    private static final String TAG = "SmsService";
    private static final String RINGTONE_RESOURCE_PATH = "raw/priority_ringtone";
    private static final long WAKE_LOCK_MARGIN_MS = 60 * 1000L;
    private static final long NO_TIMEOUT_WAKE_LOCK_MS = 30 * 60 * 1000L;
    private static final int FALLBACK_SNOOZE_MINUTES = 5;

    private MediaPlayer mediaPlayer;
    private Vibrator vibratorService;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private Handler alertHandler;
    private Runnable ringTimeoutRunnable;

    private int savedAlarmVolume = -1;
    private boolean volumeBoosted = false;
    private volatile boolean alertActive = false;
    private volatile boolean ringWindowOpen = false;
    private boolean foregroundStarted = false;

    private boolean ringEnabled = true;
    private boolean vibrateEnabled = true;
    private boolean quietHoursActive = false;
    private int ringDurationSec = 60;
    private int repeatIntervalMin = 5;
    private int currentRound = 1;

    private String currentSender = "";
    private String currentKeyword = "";
    private String currentPerson = "";
    private String currentBody = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "alert service created (single-round player)");
        mediaPlayer = new MediaPlayer();
        vibratorService = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        alertHandler = new Handler(Looper.getMainLooper());
        NotificationHelper.ensureChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand without action, stop self");
            stopSelfSafely(false);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START_ALERT.equals(action)) {
            handleStartAlert(intent);
        } else if (ACTION_STOP_ALERT.equals(action)) {
            // 纯本地清理：状态、闹钟、通知都由 AlertDispatcher 处理，避免两边互相递归调用
            handleLocalStop();
        } else if (ACTION_SNOOZE_ALERT.equals(action)) {
            handleLocalStop();
            int minutes = RoundPolicy.safeDelayMinutes(repeatIntervalMin,
                    FALLBACK_SNOOZE_MINUTES); // modified: 规则集中
            AlertDispatcher.snoozeFromService(this, minutes); // modified: 避免自重启
        } else {
            Log.w(TAG, "unknown action: " + action);
            stopSelfSafely(false);
        }
        // 一次性服务：不希望被系统自动重建后莫名其妙再次响铃
        return START_NOT_STICKY;
    }

    // ==================== 单轮提醒主流程 ====================

    private void handleStartAlert(Intent intent) {
        AlertSession session = AlertSession.get(this);
        // 会话状态为准；intent extras 只作为兼容兜底（v3 残留的通知可能直接带内容启动服务）
        currentSender = fallback(session.getFrom(), NotificationHelper.getFrom(intent));
        currentKeyword = fallback(session.getKeyword(), NotificationHelper.getKeyword(intent));
        currentPerson = fallback(session.getPerson(), NotificationHelper.getPerson(intent));
        currentBody = fallback(session.getBody(), NotificationHelper.getPayload(intent));
        currentRound = session.getRound();

        readReminderPolicy();
        cancelRingTimeout();
        stopRingAndVibration();

        // Android 8+ 通过 startForegroundService 启动后必须在 5 秒内 startForeground，
        // 即使会话已被用户停止，也要先挂通知再立刻收掉，否则系统会判 ANR。
        Notification notification = NotificationHelper.build(this,
                NotificationHelper.CHANNEL_ID_ALERT, currentSender, currentKeyword,
                currentPerson, currentBody,
                getString(R.string.notif_status_round, currentRound));
        try {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification); // modified: 最先调用
            foregroundStarted = true;
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed, degrade to plain notification", e);
            NotificationHelper.notifyDirectly(this, currentSender, currentKeyword,
                    currentPerson, currentBody);
            stopSelf();
            return;
        }

        if (!session.isActive()) {
            Log.d(TAG, "session already stopped, discard this round");
            stopSelfSafely(true);
            return;
        }

        alertActive = true;
        acquireWakeLock(computeWakeLockTimeoutMs());
        startRound();
    }

    /** 读取本轮生效的提醒策略（用户在设置页改完，下一轮立刻生效）。 */
    private void readReminderPolicy() { // modified: 重写，不再计算常驻总轮次
        AppConfig config = AppConfig.get(this);
        quietHoursActive = config.isInQuietHours();
        ringEnabled = config.isRingEnabled() && !quietHoursActive;
        vibrateEnabled = config.isVibrateEnabled();
        ringDurationSec = config.getRingDurationSec();
        repeatIntervalMin = config.getRepeatIntervalMin();
        Log.d(TAG, "policy: ring=" + ringEnabled + (quietHoursActive ? "(quiet hours)" : "")
                + ", vibrate=" + vibrateEnabled
                + ", duration=" + ringDurationSec + "s, interval=" + repeatIntervalMin + "min"
                + ", round=" + currentRound);
    }

    /** 开始本轮：响铃 + 震动 + 挂「响满时长」的定时器。 */
    private void startRound() { // modified: 重写为单轮
        if (!alertActive) {
            return;
        }
        if (ringEnabled) {
            ringWindowOpen = true;
            if (!startAlarmPlayback()) {
                Log.w(TAG, "alarm playback failed, fallback to notification sound");
                fallbackToNotificationSound();
            }
        } else {
            Log.d(TAG, "ring disabled (user setting or quiet hours), skip playback");
        }
        if (vibrateEnabled) {
            startVibration();
        }
        cancelRingTimeout();
        if (ringDurationSec > 0) {
            ringTimeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    ringTimeoutRunnable = null;
                    endRound();
                }
            };
            alertHandler.postDelayed(ringTimeoutRunnable, ringDurationSec * 1000L);
        }
        // ringDurationSec == 0：一直响到用户点「停止」（mediaPlayback 类型无 3 分钟时限）
    }

    /**
     * 本轮响完：停铃、恢复音量、把下一轮交给精确闹钟、退出前台并结束服务。
     */
    private void endRound() { // modified: 重写，不再用 Handler 常驻等待
        if (!alertActive) {
            return;
        }
        alertActive = false;
        ringWindowOpen = false;
        cancelRingTimeout();
        stopRingAndVibration();
        restoreAlarmVolume();
        releasePlayer();
        releaseWakeLock();

        AlertSession session = AlertSession.get(this);
        int rounds = Math.max(1, currentRound);
        if (!session.isActive()) {
            stopSelfSafely(true);
            return;
        }
        int total = session.getTotalRounds();
        boolean hasNextRound = RoundPolicy.hasNextRound(rounds, total,
                ringDurationSec, repeatIntervalMin); // modified: 规则集中
        if (hasNextRound) {
            // 闹钟由系统托管，服务退出后依然会在 5 分钟后把接收器拉起来
            AlertScheduler.scheduleNext(this, repeatIntervalMin, rounds + 1); // modified: 新增
            AlertDispatcher.markWaiting(this, rounds, repeatIntervalMin); // modified: 新增
        } else {
            AlertDispatcher.finishFromService(this, rounds); // modified: 避免自重启
        }
        // detach = 保留通知，用户回来时仍能看到「第几次 / 几分钟后再响 / 已结束」
        stopSelfSafely(false);
    }

    /** 收到 STOP：只做本地清理，绝不碰闹钟与会话状态（由 AlertDispatcher 统一负责）。 */
    private void handleLocalStop() { // modified: 新增，替代 v3 的 stopAlert
        Log.d(TAG, "local stop");
        alertActive = false;
        ringWindowOpen = false;
        cancelRingTimeout();
        stopRingAndVibration();
        restoreAlarmVolume();
        releasePlayer();
        releaseWakeLock();
        stopSelfSafely(false);
    }

    /** 铃声播放失败时，换到带声音渠道再提醒一次，保证至少响一声。 */
    private void fallbackToNotificationSound() {
        try {
            NotificationHelper.notifyDirectly(this, currentSender, currentKeyword,
                    currentPerson, currentBody);
        } catch (Exception e) {
            Log.e(TAG, "fallback notification failed", e);
        }
    }

    /** 准备并播放闹钟铃声。 */
    private boolean startAlarmPlayback() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager != null) {
            try {
                if (!audioManager.isVolumeFixed()) {
                    if (!volumeBoosted) {
                        savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                        volumeBoosted = true;
                    }
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "boost alarm volume failed", e);
            }
        }
        try {
            if (mediaPlayer == null) {
                mediaPlayer = new MediaPlayer();
            }
            mediaPlayer.reset();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            Uri ringtoneUri = Uri.parse("android.resource://" + getPackageName() + "/"
                    + RINGTONE_RESOURCE_PATH);
            mediaPlayer.setDataSource(this, ringtoneUri);
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(this);
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "media player error, what=" + what + ", extra=" + extra);
                    return true;
                }
            });
            if (audioManager != null && audioManager.isVolumeFixed()) {
                mediaPlayer.setVolume(1.0f, 1.0f);
            }
            mediaPlayer.prepareAsync();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "prepare alarm playback failed", e);
            releasePlayer();
            return false;
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        if (!alertActive || !ringWindowOpen) {
            Log.d(TAG, "onPrepared but ring window closed, skip start");
            return;
        }
        try {
            mp.start();
            Log.d(TAG, "alarm playback started");
        } catch (IllegalStateException e) {
            Log.e(TAG, "start playback failed", e);
        }
    }

    private void startVibration() {
        if (vibratorService == null || !vibratorService.hasVibrator()) {
            return;
        }
        try {
            long[] pattern = new long[]{50, 1000, 400, 900, 300, 800};
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                int[] amplitudes = new int[]{0, 255, 0, 200, 0, 150};
                vibratorService.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 2));
            } else {
                vibratorService.vibrate(pattern, 2);
            }
        } catch (Exception e) {
            Log.w(TAG, "vibrate failed", e);
        }
    }

    private void stopRingAndVibration() {
        try {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "pause playback failed", e);
        }
        try {
            if (vibratorService != null) {
                vibratorService.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel vibration failed", e);
        }
    }

    private void cancelRingTimeout() {
        if (ringTimeoutRunnable != null && alertHandler != null) {
            alertHandler.removeCallbacks(ringTimeoutRunnable);
            ringTimeoutRunnable = null;
        }
        if (alertHandler != null) {
            alertHandler.removeCallbacksAndMessages(null);
        }
    }

    /** 恢复用户原来的闹钟音量。只有确实提升过才恢复，避免把音量写成 0。 */
    private void restoreAlarmVolume() {
        if (!volumeBoosted || audioManager == null || savedAlarmVolume < 0) {
            return;
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
        } catch (Exception e) {
            Log.w(TAG, "restore alarm volume failed", e);
        } finally {
            volumeBoosted = false;
            savedAlarmVolume = -1;
        }
    }

    /**
     * 退出前台并结束服务。
     *
     * @param removeNotification true = 连通知一起撤掉（会话已停止时）；
     *                           false = detach，把通知留在通知栏供用户查看
     */
    private void stopSelfSafely(boolean removeNotification) { // modified: 区分 detach / remove
        try {
            if (foregroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(removeNotification
                            ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
                } else {
                    stopForeground(removeNotification);
                }
                foregroundStarted = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "stopForeground failed", e);
        }
        stopSelf();
    }

    private long computeWakeLockTimeoutMs() { // modified: 单轮时长，不再按总轮次估算
        if (ringDurationSec <= 0) {
            return NO_TIMEOUT_WAKE_LOCK_MS;
        }
        return Math.max(3 * 60_000L, ringDurationSec * 1000L + WAKE_LOCK_MARGIN_MS);
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            releaseWakeLock();
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bellgram:alert");
            wakeLock.acquire(timeoutMs);
        } catch (Exception e) {
            Log.w(TAG, "acquire wake lock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "release wake lock failed", e);
        } finally {
            wakeLock = null;
        }
    }

    private void releasePlayer() {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.reset();
                mediaPlayer.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "release media player failed", e);
        } finally {
            mediaPlayer = null;
        }
    }

    /** Android 14+ 前台服务超时兜底。mediaPlayback 正常不会触发，仍按规范实现。 */
    @Override
    public void onTimeout(int startId, int fgsType) { // modified: 新增
        Log.w(TAG, "foreground service timeout, stop round");
        handleLocalStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        alertActive = false;
        ringWindowOpen = false;
        cancelRingTimeout();
        restoreAlarmVolume();
        releasePlayer();
        try {
            if (vibratorService != null) {
                vibratorService.cancel();
            }
        } catch (Exception e) {
            Log.w(TAG, "cancel vibration on destroy failed", e);
        }
        releaseWakeLock();
        Log.d(TAG, "alert service destroyed");
    }

    private static String fallback(String primary, String secondary) {
        return primary == null || primary.isEmpty() ? (secondary == null ? "" : secondary) : primary;
    }
}
```

### 6.22 `app/src/main/java/com/saiga/find/messagefinder/AlertActivity.java`　改 · 合规启动点 + 返回手势 + insets

```java
package com.saiga.find.messagefinder;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 全屏告警页。
 *
 * <p>设计参考：短信铃铛的「锁屏直接弹出全屏告警，必须手动点停止或稍后提醒」，
 * 以及系统闹钟类应用的通行做法（全屏 Intent + 锁屏可见 + 亮屏）。
 *
 * <p>为什么值得做：普通高优先级通知在锁屏上只是一个横幅，横幅可以被忽略、可以被系统折叠；
 * 全屏页会把当前画面整个盖掉，用户要么点「停止」要么点「稍后提醒」才能继续用手机，
 * 这才是“叫醒力度”的实质提升。仅在**锁屏或息屏**时由系统自动拉起；
 * 手机正在被使用时，系统只会显示为浮动通知，不会强行打断（这是 Android 的既定策略）。
 *
 * <p>【v4 架构重构】本页现在是整条提醒链路的**关键合规节点**：
 * targetSdk 31+ 不允许从后台启动前台服务，但「应用处于用户可见状态」是官方豁免情形。
 * 因此短信广播只发通知，通知把本页拉起后，由本页在 onCreate 里启动前台服务播放铃声。
 *
 * <p>边界处理（这些点最容易做成“卡死”或者“关不掉”）：
 * <ul>
 *   <li>返回键 = 稍后提醒（不会出现“按了返回铃声还在响、又找不到入口”的死局）；</li>
 *   <li>改用 OnBackPressedDispatcher 接管返回键，适配 Android 13+ 预测式返回手势
 *       （manifest 已开启 enableOnBackInvokedCallback）；</li>
 *   <li>服务启动失败时不会崩，通知渠道自带的闹铃音已经响过，不至于完全静默；</li>
 *   <li>页面只是「显示层 + 合法启动点」，不持有定时器；轮次由 AlertScheduler 托管。</li>
 * </ul>
 */
public class AlertActivity extends AppCompatActivity {

    private static final String TAG = "AlertActivity";

    private String sender = "";
    private String keyword = "";
    private String person = "";
    private String body = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 setContentView 之前处理锁屏展示，否则首帧会闪一下黑色
        showOverLockScreen();
        setContentView(R.layout.activity_alert);
        EdgeToEdgeHelper.apply(this, R.id.alert_root); // modified: targetSdk 35+ 强制边到边
        bindActions();
        bindBackHandling(); // modified: 预测式返回手势
        bindContent(getIntent());
        startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop + 全屏 Intent 重复触发时复用本实例，只需刷新内容
        setIntent(intent);
        bindContent(intent);
        startAlertService(); // modified: 新一轮提醒复用本实例时重新播放
    }

    /**
     * 让页面能盖在锁屏之上、并把屏幕点亮。
     *
     * <p>Android 8.1 起提供了官方 API（{@code setShowWhenLocked}/{@code setTurnScreenOn}），
     * 低版本退回 Window flag。两套都必须写，否则旧机型上锁屏里根本看不到这个页面。
     */
    private void showOverLockScreen() { // modified: 新增
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
                KeyguardManager keyguardManager =
                        (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                    // 不加这个，部分机型上全屏页会被锁屏盖在下面
                    keyguardManager.requestDismissKeyguard(this, null);
                }
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            }
            // 页面停留期间保持亮屏：用户可能需要对着短信内容操作
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception e) {
            Log.w(TAG, "show over lock screen failed", e);
        }
    }

    private void bindActions() { // modified: 新增
        View stopButton = findViewById(R.id.alert_stop);
        if (stopButton != null) {
            stopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    AlertDispatcher.stop(AlertActivity.this); // modified: 统一走调度器
                    finishSafely();
                }
            });
        }
        View snoozeButton = findViewById(R.id.alert_snooze);
        if (snoozeButton != null) {
            snoozeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    snoozeAndFinish(); // modified: 统一走调度器
                    finishSafely();
                }
            });
        }
    }

    /**
     * 把内容填进界面。所有字段都做空值兜底，避免“短信正文为空”时整页显示 null。
     *
     * @param intent 携带提醒数据的 Intent
     */
    private void bindContent(Intent intent) { // modified: 新增
        if (intent != null) {
            sender = NotificationHelper.getFrom(intent);
            keyword = NotificationHelper.getKeyword(intent);
            person = NotificationHelper.getPerson(intent);
            body = NotificationHelper.getPayload(intent);
        }
        String displaySender = TextUtils.isEmpty(person)
                ? (TextUtils.isEmpty(sender) ? getString(R.string.app_name) : sender)
                : person + " (" + sender + ")";

        TextView senderView = findViewById(R.id.alert_sender);
        if (senderView != null) {
            senderView.setText(getString(R.string.alert_from, displaySender));
        }
        TextView keywordView = findViewById(R.id.alert_keyword);
        if (keywordView != null) {
            if (TextUtils.isEmpty(keyword)) {
                keywordView.setVisibility(View.GONE);
            } else {
                keywordView.setVisibility(View.VISIBLE);
                keywordView.setText(getString(R.string.alert_keyword, keyword));
            }
        }
        TextView bodyView = findViewById(R.id.alert_body);
        if (bodyView != null) {
            bodyView.setText(TextUtils.isEmpty(body) ? getString(R.string.notif_summary) : body);
        }
    }

    /**
     * 在本页可见时启动前台服务，播放这一轮铃声与震动。
     *
     * <p>这是 v4 合规链路的核心：Android 12+ 的后台启动限制有「应用从用户可见状态转换」
     * 这一豁免项，而短信广播不在豁免清单里。把启动点放到这里，主链路才成立。
     * 即使启动失败，通知渠道自带的闹铃音也已经在通知到达时响过，不会完全静默。
     */
    private void startAlertService() { // modified: 新增
        if (!AlertSession.get(this).isActive()) {
            Log.d(TAG, "session inactive, skip starting service");
            return;
        }
        try {
            Intent intent = new Intent(this, SMSForegroundService.class); // 显式 Intent
            intent.setAction(SMSForegroundService.ACTION_START_ALERT);
            NotificationHelper.putAlertExtras(intent, sender, keyword, person, body);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            // 例如用户刚点过「停止」、或系统临时拒绝：不能让全屏页因此崩掉
            Log.e(TAG, "start foreground service failed", e);
        }
    }

    /** 稍后提醒的间隔与通知按钮保持一致。 */
    private void snoozeAndFinish() { // modified: 新增
        int minutes = RoundPolicy.safeDelayMinutes(
                AppConfig.get(this).getRepeatIntervalMin(), 5); // modified: 规则集中
        AlertDispatcher.snooze(this, minutes);
    }

    /** 返回键 = 稍后提醒。用 OnBackPressedDispatcher 兼容预测式返回手势。 */
    private void bindBackHandling() { // modified: 新增
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                snoozeAndFinish();
                finishSafely();
            }
        });
    }

    /** 关闭本页。用 finishAndRemoveTask 兜底，避免异常状态下回到空白任务栈。 */
    private void finishSafely() { // modified: 新增
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } catch (Exception e) {
            Log.w(TAG, "finish alert activity failed", e);
            finish();
        }
    }

    // modified: 删除 onBackPressed() 重写。
    // manifest 开启 enableOnBackInvokedCallback 后，Android 13+ 不再回调该方法；
    // 统一由 bindBackHandling() 注册的 OnBackPressedCallback 处理，避免双重触发。
}
```

### 6.23 `app/src/main/java/com/saiga/find/messagefinder/MainActivity.java`　改 · 通知权限 + 权限体检 + insets

```java
package com.saiga.find.messagefinder;

import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

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
 * </ul>
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener, TextWatcher {

    private static final String TAG = "Sms Receiver";

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
    private Button clearButton;
    private CoordinatorLayout userMessageLayout;
    private TextInputLayout contactNumberLayout;

    private SimpleCursorAdapter contactAdapter;
    private Snackbar smsPermSnackbar;

    private AppConfig appConfig;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** 自动联想是否已被用户关闭（用于决定“是否弹通讯录选择器”）。 */
    private boolean isAutoSuggestionDisabled = false;
    /** 引导页是否在本进程启动时展示过；为 true 时不在主界面抢焦点弹权限框。 */
    private static boolean showOnboarding = false;
    /** 用户是否已经从通讯录选过联系人（避免反复弹“要不要选择联系人”）。 */
    private boolean isContactPicked = false;
    /** 本次会话是否已经问过“要不要从通讯录选择”，避免每次聚焦都弹一次。 */
    private boolean contactPickerAsked = false; // modified: 修复反复弹框打扰用户
    /** 界面是否来自配置变更重建，用于避免重复弹权限说明框。 */
    private boolean restoredFromState = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 先切主题再 super.onCreate：保证窗口背景不是启动图
        setTheme(R.style.MyAppTheme);
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

        // 从引导页进入主界面时播放一次滑入动画
        slideAnimation();
        setContentView(R.layout.activity_main);
        // modified: targetSdk 35+ 强制 edge-to-edge，内容必须避开系统栏与挖孔
        EdgeToEdgeHelper.apply(this, R.id.user_message_layout);
        // modified: targetSdk 33+ 的 POST_NOTIFICATIONS 属于运行时权限，不申请则通知完全发不出去
        ensureNotificationPermission();
        bindPermissionChecklist(); // modified: 底部清单可点，直达权限体检

        appConfig = AppConfig.get(this); // modified: 用实例替代原来的 static SharedPreferences

        trigButton = findViewById(R.id.trigger);
        clearButton = findViewById(R.id.clear);
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
                            appConfig.saveContactName(contactTitle.getText().toString());
                            Log.d(TAG, "selected phone contact " + contactTitle.getText());
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

        clearButton.setOnClickListener(this);
        trigButton.setOnClickListener(this);
        // 二次开发新增：主界面直接提供“提醒方式与重复”入口
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
        // 状态条统一在 onResume 中刷新（onCreate 之后必然执行，避免重复调用）
    }

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

    /** 底部「权限 / 保活清单」可点击，直接打开权限体检对话框。 */
    private void bindPermissionChecklist() { // modified: 新增
        View tip = findViewById(R.id.permission_tip);
        if (tip != null) {
            tip.setOnClickListener(view -> PermissionChecker.showDialog(MainActivity.this));
            tip.setClickable(true);
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
        if (missing > 0) {
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
        int enabled = getPackageManager()
                .getComponentEnabledSetting(new ComponentName(this, Onboarding.class));
        return enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
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
            EditText contactView = findViewById(R.id.contact_value);
            contactView.setText(PhoneNumbers.canonical(phoneNumber)); // modified: 11 位国内号码
            appConfig.saveContactName(contactName);
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
        // 让根布局先拿到焦点，避免横竖屏切换时输入法自动弹出
        View root = findViewById(R.id.ui_layout);
        if (root != null) {
            root.requestFocus();
        }
        updateStatusUi(); // modified: 从设置页返回时同步状态
        super.onResume();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.trigger) {
            handleTrigger();
        } else if (id == R.id.clear) {
            handleClear();
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

    /** “清除”按钮：清空配置与输入框。 */
    private void handleClear() {
        clearSoftKeyboardState();
        appConfig.clear(); // modified: 统一走 AppConfig，避免漏删键
        // 注意：这里不清空“最近命中记录”。日志属于排障信息，与配置解耦，
        // 由设置页的“清空命中记录”单独管理，避免误删历史。

        EditText contactView = findViewById(R.id.contact_value);
        EditText messageView = findViewById(R.id.message_value);
        messageView.getText().clear();
        contactView.getText().clear();
        contactView.clearFocus();
        messageView.clearFocus();

        clearButton.setText(R.string.cleared);
        trigButton.setText(R.string.trigger_button);
        setTriggerEnabled(true);
        updateStatusUi(); // modified
    }

    /** 读取输入框文本，永不返回 null。 */
    private String getTextOf(int viewId) { // modified: 统一空值处理
        EditText editText = findViewById(viewId);
        if (editText == null || editText.getText() == null) {
            return "";
        }
        return editText.getText().toString().trim();
    }

    private void clearFocusOfForm() {
        EditText contactView = findViewById(R.id.contact_value);
        EditText messageView = findViewById(R.id.message_value);
        contactView.clearFocus();
        messageView.clearFocus();
    }

    /**
     * 保存配置并给出 UI 反馈。
     *
     * @param contact  号码，空串表示不限
     * @param keywords 关键词列表
     */
    private void saveConfig(String contact, List<String> keywords) { // modified: 参数化，替代依赖成员变量的旧实现
        appConfig.save(contact, keywords);
        showSnackbar(R.string.config_saved);
        trigButton.setText(R.string.triggered);
        clearButton.setText(R.string.clear_button);
        updateStatusUi(); // modified
    }

    private void showSnackbar(int stringResId) {
        if (userMessageLayout != null) {
            Snackbar.make(userMessageLayout, stringResId, Snackbar.LENGTH_LONG).show();
        }
    }

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
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERM_NOTIFICATIONS_REQUEST) { // modified: 新增通知权限回调
            if (!granted) {
                showSnackbar(R.string.notif_permission_denied);
            }
            updateStatusUi();
            return;
        }

        if (requestCode == PERM_SMS_REQUEST) {
            if (granted) {
                // 权限授予后，重新读取界面输入再保存，避免使用可能已失效的成员变量
                String contactRaw = getTextOf(R.id.contact_value);
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
        if (userMessageLayout == null) {
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
        if (showOnboarding || restoredFromState) { // modified: 生命周期校验
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
```

### 6.24 `app/src/main/java/com/saiga/find/messagefinder/SettingsActivity.java`　重写 · AndroidX Preference 迁移

```java
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
// 参考：Android 官方《Settings / Migrate to AndroidX Preference》。未复制第三方代码。

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.util.Set;

/** 设置页：展示当前生效的配置、命中记录、权限体检与版本号。 */
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SmsSettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        EdgeToEdgeHelper.apply(this, R.id.settings_container); // modified: 边到边适配
        // modified: 用 FragmentTransaction 装载，替代已废弃的 <fragment> 静态标签
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, new AppPreferenceFragment())
                .commit();
    }

    /** 设置页内容由该 Fragment 承载。 */
    public static class AppPreferenceFragment extends PreferenceFragmentCompat
            implements Preference.OnPreferenceChangeListener {

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState,
                                        @Nullable String rootKey) { // modified: AndroidX 标准回调
            setPreferencesFromResource(R.xml.app_preferences, rootKey);
            bindListSummaries();

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

            // modified: 权限体检入口。点击后列出未就绪项，点条目直达对应系统设置页
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

        /**
         * androidx.preference 的现代写法：用官方 SummaryProvider 显示 ListPreference 当前值。
         * 旧版 {@code android:summary="%s"} 的框架行为在 AndroidX 上不保证保留，
         * 不显式设置会出现「选了 5 分钟但摘要一片空白」。
         */
        private void bindListSummaries() { // modified: 新增
            String[] keys = {
                    getString(R.string.ring_duration_key),
                    getString(R.string.repeat_interval_key),
                    getString(R.string.max_repeat_key),
                    getString(R.string.quiet_hours_key)
            };
            for (String key : keys) {
                Preference preference = findPreference(key);
                if (preference instanceof ListPreference) {
                    ((ListPreference) preference).setSummaryProvider(
                            ListPreference.SimpleSummaryProvider.getInstance());
                }
            }
        }

        @Override
        public void onResume() {
            super.onResume();
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
            if (!(newValue instanceof Boolean)) {
                Log.w(TAG, "unexpected preference value type: " + newValue);
                return false;
            }
            boolean enabled = (Boolean) newValue;
            if (preference instanceof SwitchPreferenceCompat && getActivity() != null) {
                AppConfig.get(getActivity()).setAutoSuggestionDisabled(!enabled);
                Log.d(TAG, "auto suggestion enabled: " + enabled);
            }
            return true;
        }

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
            // modified: 权限体检摘要实时反映缺了几项
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
```

### 6.25 `app/src/main/java/com/saiga/find/messagefinder/AppConfig.java`　改 · AndroidX PreferenceManager + 轮次规则

```java
package com.saiga.find.messagefinder;

// [新增文件] 配置读写集中入口。
// 解决问题：
//  1) 键名 "contact"/"message"/"contact_name" 原先散落在 MainActivity / SMSReceiver / SettingsActivity 三处，
//     改一处漏两处，属于典型的“字符串契约”隐患；
//  2) 原代码统一用 (HashSet<String>) prefs.getStringSet(...) 强转，
//     Android 各版本/OEM 返回的实现类不保证是 HashSet，存在 ClassCastException 崩溃风险；
//  3) 原代码把 SharedPreferences 存成 static 字段，持有 Activity 引用，存在内存泄漏与生命周期错配风险。

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用配置（联系人号码 + 关键词集合 + 联系人姓名）与自动联想开关的统一读写门面。
 *
 * <p>注意：为兼容已安装用户的旧数据，这里继续使用 {@link PreferenceManager#getDefaultSharedPreferences}
 * 与原有键名，不迁移、不重命名存储位置。
 *
 * <p>v4 合规改造：由已废弃的 {@code android.preference.PreferenceManager} 换到
 * {@code androidx.preference.PreferenceManager}。两者默认文件名都是
 * {@code <包名>_preferences}，因此老用户的号码 / 关键词 / 全部提醒策略都不会丢。
 */
public final class AppConfig {

    // ---- 短信配置键（沿用旧键名，保证升级后配置不丢）----
    private static final String KEY_CONTACT = "contact";
    private static final String KEY_KEYWORDS = "message";
    private static final String KEY_CONTACT_NAME = "contact_name";

    // ---- 自动联想开关（旧实现使用名为 "Settings" 的独立 preference 文件）----
    private static final String FEATURE_PREF_NAME = "Settings";
    private static final String KEY_AUTO_SUGGESTION_DISABLED = "autoSuggDisabled";

    // ---- 二次开发新增：提醒方式与重复策略（键名与 app_preferences.xml 严格一致）----
    /** 是否响铃。 */
    private static final String KEY_RING_ENABLED = "ring_enabled";
    /** 是否震动。 */
    private static final String KEY_VIBRATE_ENABLED = "vibrate_enabled";
    /** 单次响铃时长（秒），"0" 表示一直响到用户手动停止。 */
    private static final String KEY_RING_DURATION = "ring_duration";
    /** 重复提醒间隔（分钟），"0" 表示不重复。 */
    private static final String KEY_REPEAT_INTERVAL = "repeat_interval";
    /** 最多重复次数，"0" 表示不限（直到用户处理）。 */
    private static final String KEY_MAX_REPEAT = "max_repeat";

    /** 默认响铃时长：60 秒（一次闹铃级别的提醒，足够叫醒但不会无限扰民）。 */
    private static final int DEFAULT_RING_DURATION_SEC = 60;
    /** 默认重复间隔：5 分钟（与系统闹钟的“贪睡”一致）。 */
    private static final int DEFAULT_REPEAT_INTERVAL_MIN = 5;
    /** 默认最多重复 3 次。 */
    private static final int DEFAULT_MAX_REPEAT = 3;

    // ---- 二次开发新增（借鉴短信铃铛）：匹配模式与夜间静音 ----
    /** 是否要求“全部关键词都出现”。false = 任意一个出现即命中（与原版语义一致）。 */
    private static final String KEY_MATCH_ALL = "match_all";
    /** 夜间静音时段，取值为 "off" 或 "起始小时-结束小时"，例如 "22-7"。 */
    private static final String KEY_QUIET_HOURS = "quiet_hours";
    /** 夜间静音默认关闭（不改动用户既有体验）。 */
    private static final String DEFAULT_QUIET_HOURS = "off";

    private final SharedPreferences configPrefs;
    private final SharedPreferences featurePrefs;

    private AppConfig(SharedPreferences configPrefs, SharedPreferences featurePrefs) {
        this.configPrefs = configPrefs;
        this.featurePrefs = featurePrefs;
    }

    /**
     * 获取配置门面实例。使用 ApplicationContext，避免持有 Activity 造成泄漏。
     *
     * @param context 任意 Context
     * @return 配置门面
     */
    public static AppConfig get(Context context) { // modified: 用 ApplicationContext 取代原来的 static Activity 引用
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        return new AppConfig(
                PreferenceManager.getDefaultSharedPreferences(appContext),
                appContext.getSharedPreferences(FEATURE_PREF_NAME, Context.MODE_PRIVATE));
    }

    /**
     * @return 用户配置的号码，未配置时返回空串（永不为 null，调用方无需判空）
     */
    public String getContact() { // modified: null -> 空串，消除调用侧 NPE
        String value = configPrefs.getString(KEY_CONTACT, null);
        return value == null ? "" : value.trim();
    }

    /**
     * @return 用户配置的联系人姓名，未配置时返回空串
     */
    public String getContactName() {
        String value = configPrefs.getString(KEY_CONTACT_NAME, null);
        return value == null ? "" : value.trim();
    }

    /**
     * @return 关键词集合的只读副本，未配置时返回空集合（永不为 null）
     */
    public Set<String> getKeywords() { // modified: 不再强转 HashSet，改用 Set 接口 + 不可变副本
        Set<String> stored;
        try {
            stored = configPrefs.getStringSet(KEY_KEYWORDS, null);
        } catch (ClassCastException e) {
            // 极端情况：键被其它版本写成了非字符串集合类型，按“未配置”处理，避免崩溃
            stored = null;
        }
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(stored));
    }

    /**
     * @return 是否已经配置过关键词（关键词为空即视为未配置，与原有“未配置就不监听”的语义一致）
     */
    public boolean isConfigured() {
        return !getKeywords().isEmpty();
    }

    /**
     * 保存短信配置。
     *
     * @param contact  号码，空串表示不限号码
     * @param keywords 关键词列表，不可为空
     */
    public void save(String contact, List<String> keywords) { // modified: 统一写入入口，空集合不再写入 null
        SharedPreferences.Editor editor = configPrefs.edit();
        String trimmedContact = contact == null ? "" : contact.trim();
        if (trimmedContact.isEmpty()) {
            editor.remove(KEY_CONTACT);
        } else {
            editor.putString(KEY_CONTACT, trimmedContact);
        }
        if (keywords == null || keywords.isEmpty()) {
            editor.remove(KEY_KEYWORDS);
        } else {
            // 必须传入新的 Set：SharedPreferences 不会复制入参，直接传会导致后续 set 同时被修改
            editor.putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(keywords));
        }
        editor.apply();
    }

    /**
     * 保存联系人姓名（用于通知与设置页展示）。
     *
     * @param contactName 联系人姓名，可为空
     */
    public void saveContactName(String contactName) {
        SharedPreferences.Editor editor = configPrefs.edit();
        if (contactName == null || contactName.trim().isEmpty()) {
            editor.remove(KEY_CONTACT_NAME);
        } else {
            editor.putString(KEY_CONTACT_NAME, contactName.trim());
        }
        editor.apply();
    }

    /**
     * 清空短信配置（保留自动联想开关与命中日志）。
     */
    public void clear() { // modified: 与“清除”按钮语义对齐，避免误清其它设置
        configPrefs.edit()
                .remove(KEY_CONTACT)
                .remove(KEY_KEYWORDS)
                .remove(KEY_CONTACT_NAME)
                .apply();
    }

    /**
     * @return 自动联想是否被用户关闭（默认 true，与旧版本默认行为一致）
     */
    public boolean isAutoSuggestionDisabled() {
        return featurePrefs.getBoolean(KEY_AUTO_SUGGESTION_DISABLED, true);
    }

    /**
     * 设置自动联想开关。
     *
     * @param disabled true 表示关闭自动联想（改用系统通讯录选择器）
     */
    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }

    // ==================== 二次开发新增：提醒方式与重复策略 ====================

    /**
     * @return 是否响铃（默认 true）
     */
    public boolean isRingEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_RING_ENABLED, true);
    }

    /**
     * @return 是否震动（默认 true）
     */
    public boolean isVibrateEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_VIBRATE_ENABLED, true);
    }

    /**
     * @return 单次响铃时长（秒）；0 表示一直响到用户手动停止
     */
    public int getRingDurationSec() { // modified: 新增
        return readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC);
    }

    /**
     * @return 重复提醒间隔（分钟）；0 表示不重复
     */
    public int getRepeatIntervalMin() { // modified: 新增
        int value = readInt(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL_MIN);
        return Math.max(0, Math.min(value, 60));
    }

    /**
     * @return 最多重复次数；0 表示不限（一直重复到用户处理）
     */
    public int getMaxRepeatCount() { // modified: 新增
        int value = readInt(KEY_MAX_REPEAT, DEFAULT_MAX_REPEAT);
        return Math.max(0, Math.min(value, 30));
    }

    /**
     * 计算一次提醒会话计划的总轮次。
     *
     * <p>v4 架构重构：这段逻辑原先写在 SMSForegroundService 里；由于重复轮次现在由
     * 精确闹钟驱动、服务每轮都会退出，轮次必须在「收到短信」时就确定并写进会话状态，
     * 因此上移到配置门面（纯计算，可单元测试）。
     *
     * @return 总轮次；0 表示不限次数，直到用户点「停止」
     */
    public int getTotalRounds() { // modified: 新增
        // modified: 规则抽到 RoundPolicy，供四个组件共用且可单元测试
        return RoundPolicy.totalRounds(getRingDurationSec(), getRepeatIntervalMin(),
                getMaxRepeatCount());
    }

    /**
     * 读取以字符串形式存储的整型偏好（ListPreference 存的是 String）。
     *
     * @param key          键名
     * @param defaultValue 解析失败时的兜底值
     * @return 整型值
     */
    private int readInt(String key, int defaultValue) {
        String raw = configPrefs.getString(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            // 也可能是被写成了整型（例如从旧版本迁移过来），尝试整型读取
            try {
                return configPrefs.getInt(key, defaultValue);
            } catch (ClassCastException e) {
                return defaultValue;
            }
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ==================== 二次开发新增：匹配模式与夜间静音（借鉴短信铃铛） ====================

    /**
     * @return 是否要求“全部关键词都出现”才触发（默认 false，与原版“任意命中”一致）
     */
    public boolean isMatchAllEnabled() { // modified: 新增
        return configPrefs.getBoolean(KEY_MATCH_ALL, false);
    }

    /**
     * @return 夜间静音起始小时；-1 表示未启用
     */
    public int getQuietStartHour() { // modified: 新增
        return parseQuietHour(true);
    }

    /**
     * @return 夜间静音结束小时；-1 表示未启用
     */
    public int getQuietEndHour() { // modified: 新增
        return parseQuietHour(false);
    }

    /**
     * @return 当前时刻是否处于夜间静音时段（此时只震动、不响铃）
     */
    public boolean isInQuietHours() { // modified: 新增
        int start = getQuietStartHour();
        int end = getQuietEndHour();
        if (start < 0 || end < 0) {
            return false;
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return QuietHours.isActive(start, end, hour);
    }

    /**
     * 解析夜间静音时段的配置值。
     *
     * <p>取值形如 "off" 或 "22-7"（起始小时-结束小时）。任何无法解析的值都按“未启用”处理，
     * 避免脏配置把用户手机变成“永远不响铃”。
     *
     * @param start true 取起始小时，false 取结束小时
     * @return 小时值 0-23；未启用或解析失败返回 -1
     */
    private int parseQuietHour(boolean start) { // modified: 新增
        String raw = configPrefs.getString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS);
        if (raw == null) {
            return -1;
        }
        String value = raw.trim();
        if (value.isEmpty() || "off".equalsIgnoreCase(value)) {
            return -1;
        }
        String[] parts = value.split("-");
        if (parts.length != 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[start ? 0 : 1].trim());
            return QuietHours.normalizeHour(hour);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
```

### 6.26 `app/src/main/java/com/saiga/find/messagefinder/HitLog.java`　改 · 清除平台废弃 API

```java
package com.saiga.find.messagefinder;

// [新增文件] 命中记录（最近 N 条）。
// 需求来源：这类“关键词触发提醒”的应用一旦不响，用户无法判断是“没收到短信 / 关键词没匹配 / 服务没起来”，
// 属于最典型的排障痛点。参考 pppscn/SmsForwarder 的“转发日志”设计思路，
// 这里做一个极简的环形缓冲（上限 20 条），只落本地 SharedPreferences，不外传、不申请新权限。
// 实现上不引入 Room / 数据库依赖，避免为一个日志功能增加 APK 体积与迁移成本。

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager; // modified: 清除平台废弃 API，默认文件名不变
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 短信命中记录，用于在设置页自查“规则是否生效”。
 */
public final class HitLog {

    private static final String KEY_HIT_LOG = "hit_log";
    private static final int MAX_ENTRIES = 20;
    private static final String LINE_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = " | ";
    private static final int SNIPPET_MAX_LENGTH = 40;
    private static final String TIME_PATTERN = "MM-dd HH:mm:ss";

    /** 广播接收器与主线程都可能写入，加锁避免“读-改-写”竞争导致记录丢失。 */
    private static final Object LOCK = new Object();

    private HitLog() {
        // 工具类不允许实例化
    }

    /**
     * 追加一条命中记录。
     *
     * @param context 任意 Context
     * @param sender  发送方号码
     * @param keyword 命中的关键词
     * @param body    短信正文
     */
    public static void add(Context context, String sender, String keyword, String body) { // modified: 新增
        if (context == null) {
            return;
        }
        String record = format(sender, keyword, body);
        synchronized (LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String stored = prefs.getString(KEY_HIT_LOG, "");
            List<String> lines = new ArrayList<>();
            lines.add(record);
            if (!TextUtils.isEmpty(stored)) {
                String[] previous = stored.split(LINE_SEPARATOR);
                for (String line : previous) {
                    if (lines.size() >= MAX_ENTRIES) {
                        break;
                    }
                    if (!TextUtils.isEmpty(line) && !line.trim().isEmpty()) {
                        lines.add(line);
                    }
                }
            }
            prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).apply();
        }
    }

    /**
     * @param context 任意 Context
     * @return 命中记录文本，每条一行；无记录时返回空串
     */
    public static String dump(Context context) { // modified: 新增，供设置页展示
        if (context == null) {
            return "";
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String stored = prefs.getString(KEY_HIT_LOG, "");
        return stored == null ? "" : stored;
    }

    /**
     * 清空命中记录。
     *
     * @param context 任意 Context
     */
    public static void clear(Context context) { // modified: 新增
        if (context == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(KEY_HIT_LOG).apply();
    }

    private static String format(String sender, String keyword, String body) {
        String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
        String safeSender = TextUtils.isEmpty(sender) ? "未知号码" : sender.trim();
        String safeKeyword = TextUtils.isEmpty(keyword) ? "-" : keyword.trim();
        return time + FIELD_SEPARATOR + safeSender + FIELD_SEPARATOR + "[" + safeKeyword + "] "
                + snippet(body);
    }

    /** 单行化并截断正文，避免记录里混入换行符破坏存储格式。 */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (singleLine.length() <= SNIPPET_MAX_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }
}
```

### 6.27 `app/src/test/java/com/saiga/find/messagefinder/RoundPolicyTest.java`　★新增 · 13 个轮次规则单测

```java
package com.saiga.find.messagefinder;

// [新增测试 / modified] v4 架构重构：轮次规则从 Service 抽出后可直接在 JVM 上验证。
// 重点覆盖「不重复」「一直响」「不限次数」「最后一轮」「非法参数」这几类边界。

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoundPolicyTest {

    @Test
    public void totalRounds_defaultIsFirstPlusThreeRepeats() {
        assertEquals(4, RoundPolicy.totalRounds(60, 5, 3));
    }

    @Test
    public void totalRounds_noRepeatMeansOneRound() {
        assertEquals(1, RoundPolicy.totalRounds(60, 0, 3));
    }

    @Test
    public void totalRounds_alwaysRingMeansOneRound() {
        assertEquals(1, RoundPolicy.totalRounds(0, 5, 3));
    }

    @Test
    public void totalRounds_unlimitedReturnsZero() {
        assertEquals(0, RoundPolicy.totalRounds(60, 5, 0));
    }

    @Test
    public void totalRounds_negativeMaxRepeatTreatedAsUnlimited() {
        assertEquals(0, RoundPolicy.totalRounds(60, 5, -5));
    }

    @Test
    public void hasNextRound_trueBeforeLastRound() {
        assertTrue(RoundPolicy.hasNextRound(1, 4, 60, 5));
        assertTrue(RoundPolicy.hasNextRound(3, 4, 60, 5));
    }

    @Test
    public void hasNextRound_falseOnLastRound() {
        assertFalse(RoundPolicy.hasNextRound(4, 4, 60, 5));
    }

    @Test
    public void hasNextRound_unlimitedAlwaysTrue() {
        assertTrue(RoundPolicy.hasNextRound(99, 0, 60, 5));
    }

    @Test
    public void hasNextRound_falseWhenRepeatDisabled() {
        assertFalse(RoundPolicy.hasNextRound(1, 4, 60, 0));
        assertFalse(RoundPolicy.hasNextRound(1, 4, 0, 5));
    }

    @Test
    public void hasNextRound_toleratesZeroCurrentRound() {
        assertTrue(RoundPolicy.hasNextRound(0, 2, 60, 5));
    }

    @Test
    public void safeDelayMinutes_usesConfiguredValue() {
        assertEquals(10, RoundPolicy.safeDelayMinutes(10, 5));
    }

    @Test
    public void safeDelayMinutes_zeroOrNegativeUseFallback() {
        assertEquals(5, RoundPolicy.safeDelayMinutes(0, 5));
        assertEquals(5, RoundPolicy.safeDelayMinutes(-3, 5));
    }

    @Test
    public void safeDelayMinutes_neverReturnsZero() {
        assertEquals(1, RoundPolicy.safeDelayMinutes(0, 0));
        assertEquals(1, RoundPolicy.safeDelayMinutes(-1, -1));
    }
}
```

### 6.28 本轮未改动的文件（清单）

| 分类 | 文件 |
|---|---|
| 构建 | `settings.gradle`、`gradle.properties` |
| 资源 | `res/values/colors.xml`、`styles.xml`、`arrays.xml`、`values-zh-rCN/arrays.xml`、`res/drawable/*`、`res/mipmap/*`、`res/raw/*` |
| 布局 | `res/layout/activity_main.xml`、`activity_alert.xml`、`dialog_practice.xml`、`dialog_layout.xml`、`page_layout.xml`、`screen_onboarding.xml` |
| Java | `KeywordMatcher.java`、`PhoneNumbers.java`、`QuietHours.java`、`MyDialogFragment.java`、`Onboarding.java`、`SliderAdapter.java` |
| 测试 | `KeywordMatcherTest.java`（16）、`PhoneNumbersTest.java`（13）、`QuietHoursTest.java`（10）、`ExampleUnitTest.java`（1）、`ExampleInstrumentedTest.java` |

> 上述文件的当前内容就是最终整合结果；本轮未触碰，故不重复占用篇幅。
> 全部源码合计：**27 个改动/新增文件**（见 §6.1–6.27）+ 上表未改动文件。
>
> **文档同步（非源码，本轮已实际修改，故不列入上方「未改动」表）**：
> `README.md` 顶部横幅已改为 v4.0.0（更新构建要求 AGP 8.11.1 / Gradle 8.13 / targetSdk 36、
> 移除「targetSdk 有意保持 29」的过时结论、补充架构重构与验证说明，并指向本报告）；
> `AUDIT_REPORT.md` 顶部已加「v3 历史存档 / 已被 v4 取代」提示块，逐条列出 4 项被推翻的结论。

---

## 7. 风险提示

### 7.1 最高风险：本机没有 JDK / Android SDK，**本轮未实际编译**

这台机器上 `java` / `gradle` / `javac` 均不可用，也没有 Android SDK 目录，因此**所有改动只经过静态校验**
（XML 良构、资源引用、中英文键名对称、Java 括号配平），没有经过 `aapt` / `javac` / R8。
请务必先按 §8.1 构建一次。若报错，把完整输出发我，我按行定位修正。

### 7.2 破坏性变更带来的兼容性影响

| # | 风险 | 影响与处置 |
|---|---|---|
| R1 | **targetSdk 29 → 36** | 系统行为变更一次性生效（后台服务、通知权限、全屏 Intent、隐式 Intent、备份等）。本轮已逐项适配，但**必须用真机按 §8.4 全量验收**。若要回退，把 `targetSdk` 改回 29 即可恢复 v3 链路（但重新不符合规范） |
| R2 | **通知按钮改广播** | 覆盖安装后，**升级前已停留在通知栏的旧通知**上的「停止 / 稍后提醒」仍指向旧的 `getService` PendingIntent，点击会失效一轮。新短信产生的通知正常。可在升级后手动划掉旧通知 |
| R3 | **前台服务类型改 mediaPlayback** | 属于「后台音频播放」语义。系统不审核个人自用应用；若将来上架 Play，需在声明中说明这是闹铃音频而非媒体内容 |
| R4 | **点击通知正文改为打开全屏告警页** | 行为变化（原来打开主界面）。目的是让亮屏状态也能进入强提醒链路。若要回到「打开主界面」，改 `NotificationHelper.build()` 的 `setContentIntent` 一处即可 |
| R5 | **通知渠道新增 ID** | 旧渠道 `priority_alert_v2` / `priority_alert_fallback_v2` 会残留在系统设置里（渠道无法删除，只能由应用代码创建/改名）。新渠道带闹铃音，旧渠道静音；不影响功能，但用户在系统通知设置里会看到多个渠道 |
| R6 | **首轮可能有 1~2 秒声音重叠** | 带声音渠道的通知到达时系统先播闹铃音，服务起来后把同 ID 通知覆盖到静音渠道，由 MediaPlayer 接手。已播放的提示音会自然结束，可能与铃声短暂重叠；不会持续双响 |
| R7 | **Onboarding 不再导出** | 第三方应用/桌面快捷方式无法再拉起引导页（本来也没有这种入口）；只由 `MainActivity` 显式启动，行为不变 |

### 7.3 运行期风险（红米 K60 Pro / HyperOS 重点）

| # | 风险 | 说明 / 缓解 |
|---|---|---|
| R8 | **全屏 Intent 权限可能未授予** | Android 14+ 对非通话/闹钟类应用默认不给 `USE_FULL_SCREEN_INTENT`。此时锁屏只会显示悬挂通知（HUN）而不会弹全屏页，用户点 HUN 仍可进入告警页。请用「权限体检」第 3 项手动开启 |
| R9 | **电池优化 / 自启动** | HyperOS 上若不设「无限制」+「自启动 = 开」，闹钟广播与全屏页都可能被拦。权限体检第 4 项直达设置页；主界面底部清单也写了四步 |
| R10 | **`setAlarmClock` 会在状态栏显示闹钟图标** | 这是系统行为（用户可见闹钟），对「确认还会再响」反而有利；若不喜欢，只能换回 `SCHEDULE_EXACT_ALARM + setExactAndAllowWhileIdle`（需要额外授权，不推荐） |
| R11 | **部分国产 ROM 会限制 `setAlarmClock` 或延迟投递** | 代码已捕获异常并降级为 `set(RTC_WAKEUP, ...)`；极端情况下重复提醒可能晚几分钟。可用 §8.5 的 `dumpsys alarm` 核对闹钟是否已注册 |
| R12 | **POST_NOTIFICATIONS / RECEIVE_SMS 被拒** | 两者都是运行时权限。被拒后应用不会崩，但提醒链路不工作；状态条会红字提示「N 项权限未就绪」 |
| R13 | **夜间静音只在每轮开始时判定** | 与 v3 一致，一轮播到一半跨入静音时段不会中途静音（有意为之） |
| R14 | **「一直响到停止」（时长 0）时服务会长时间前台** | `mediaPlayback` 无 3 分钟时限，合规；但会持续耗电直到用户点停止。唤醒锁最长 30 分钟超时兜底 |
| R15 | **`privacy_url` 仍是 `http://`** | 它是交给外部浏览器的 `ACTION_VIEW`，不受本应用 `usesCleartextTraffic=false` 限制。若该站点未启用 HTTPS，请不要改成 https（会打不开）；更彻底的做法是改成本地隐私说明弹窗 |
| R16 | **edge-to-edge 视觉** | 主界面 / 设置页 / 告警页已补 insets；引导页是整屏背景式布局，未补白以免露出白边，请在真机上确认顶部文字没有被状态栏压住 |
| R17 | **Android 16 其它行为变更** | 本应用不使用正则 / UUID 反序列化 / JobScheduler / 隐式广播，故不受 OpenJDK 17 严格校验、任务配额等变更影响 |

### 7.4 依赖体积与稳定性评估

| 依赖 | 版本 | 体积量级 | 稳定性 |
|---|---|---|---|
| `androidx.preference:preference` | 1.2.1 | AAR ≈ 0.2 MB | Google 官方稳定版，替代已废弃的平台 API |
| `androidx.core:core` | 1.13.1 | AAR ≈ 0.5 MB | 官方稳定版，`appcompat` 本就传递依赖，仅显式提升版本 |
| `androidx.activity:activity` | 1.9.1 | AAR ≈ 0.2 MB | 官方稳定版，`appcompat 1.7.0` 已传递依赖 |
| 移除 `androidx.legacy:legacy-preference-v14` | 1.0.0 | −0.1 MB | 已废弃 |

净增约 0.8 MB，**没有引入任何第三方 / 非官方库**，也没有新增权限以外的敏感能力。

---

## 8. 验证方案

### 8.1 第一层：构建（必做，本轮未编译）

前置条件（Android Studio 里一次性确认）：

1. SDK Manager 安装 **Android SDK Platform 36** 与 **Build Tools 35.0.0**；
2. `Settings → Build Tools → Gradle → Gradle JDK` 选 **JDK 17**；
3. Gradle 8.13 由 Wrapper 自动下载；若腾讯云镜像取不到，把
   `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成注释里的官方地址。

```bash
gradlew.bat :app:assembleDebug --stacktrace
```

期望：`BUILD SUCCESSFUL`。常见报错对照：

| 报错 | 处理 |
|---|---|
| `compileSdk 36 requires AGP ...` | 确认根目录 `build.gradle` 已是 `8.11.1`，Gradle 已是 `8.13` |
| `SDK location not found` | 配 `ANDROID_HOME` 或工程根目录 `local.properties` |
| `Unresolved: androidx.preference:preference:1.2.1` | 确认 `repositories` 含 `google()` 且网络可访问 |
| `resource style/PreferenceThemeOverlay.v14.Material not found` | 确认 `androidx.preference` 依赖已同步；必要时把 `styles.xml` 的 `preferenceTheme` 改成 `@style/PreferenceThemeOverlay` |
| `cannot find symbol canUseFullScreenIntent / onTimeout` | 确认用 SDK Platform 36 编译（不是 34） |

### 8.2 第二层：单元测试

```bash
gradlew.bat :app:testDebugUnitTest --tests "com.saiga.find.messagefinder.*"
```

期望 **53 个用例全绿**：`KeywordMatcherTest` 16 + `PhoneNumbersTest` 13 + `QuietHoursTest` 10 +
`RoundPolicyTest` 13 + `ExampleUnitTest` 1。重点看新增的：

- `hasNextRound_falseOnLastRound` → 最后一轮之后不再安排闹钟；
- `hasNextRound_unlimitedAlwaysTrue` → 「不限次数」不会被误判结束；
- `safeDelayMinutes_neverReturnsZero` → 用户把间隔设为 0 时点「稍后提醒」仍会响。

### 8.3 第三层：静态校验（本轮已执行，结果如下）

| 检查项 | 方法 | 结果 |
|---|---|---|
| XML 良构 | 遍历 `res/**/*.xml` + `AndroidManifest.xml` 用 XML 解析器 | **0 错** |
| 资源引用完整性 | 校验 `@string/@color/@drawable/@layout/@xml/@array/@style` 与 `R.*` 引用 | **0 缺失**（`android.R.string.cancel` 2 处为正则误报，已人工确认） |
| 重复资源名 | 同一 `values` 文件内 `name` 去重 | **0 处重复** |
| 中英文本地化对称 | 比对两套 `strings.xml` 的 `name` 集合 | **各 154 条，双向差集为空** |
| Java 括号/字符串配平 | 剔除注释与字符串字面量后统计 `{}`、`()` | **27 个文件全部配平** |
| 废弃 API 残留 | 全量 grep `android.preference` | 仅剩注释中的说明文字，**无实际 import** |

### 8.4 第四层：真机验收（红米 K60 Pro / HyperOS）

> 建议用另一台手机发送真实短信；模拟器可用 Android Studio 的 Extended Controls → Phone → Send SMS。

**A. 安装与权限（C1–C6）**

| # | 操作 | 预期 |
|---|---|---|
| C1 | 覆盖安装 v4（不卸载 v3） | 号码 / 关键词 / 提醒策略全部保留；应用名仍为「铃信」 |
| C2 | 首次启动 | 弹出通知权限申请；同意后设置页「权限体检」显示「全部就绪」或列出缺项 |
| C3 | 设置 → 应用管理 → 铃信 → 权限 | 可见 `POST_NOTIFICATIONS` 已授予、`RECEIVE_SMS` 已授予 |
| C4 | 设置 → 特殊权限 → 全屏通知 | 按体检第 3 项跳转并开启（Android 14+ 必做） |
| C5 | 电池优化设为「无限制」+ 自启动打开 | 体检第 4 项变为 ✓ |
| C6 | 主界面状态条 | 有缺项时追加红字「! 有 N 项权限未就绪」；全部就绪时不显示 |

**B. 主链路（C7–C13）**

| # | 操作 | 预期 |
|---|---|---|
| C7 | 配置关键词「到账」，点「启用提醒」 | 状态条变绿并显示策略摘要 |
| C8 | **锁屏**后用另一台手机发「工商银行到账 8000 元」 | 屏幕点亮、弹出全屏告警页、**响起闹铃**、持续震动 |
| C9 | 观察状态栏 | 出现闹钟图标（下一次重复提醒已登记） |
| C10 | 全屏页点「停止」 | 立即静音、通知消失、闹钟被取消（`dumpsys alarm` 无本应用闹钟） |
| C11 | **亮屏解锁**状态下再发一条命中短信 | 不弹全屏页（系统策略），出现悬挂通知 + 闹铃音；点通知进入全屏告警页并开始响铃 |
| C12 | 全屏页点「稍后提醒」或按返回键 | 立即静音、页面关闭；间隔后再响一轮（返回键在 Android 13+ 也生效） |
| C13 | 发一条不含关键词的短信 | 完全不响、不弹通知 |

**C. 重复提醒（C14–C19）**

| # | 操作 | 预期 |
|---|---|---|
| C14 | 默认配置（60 秒 / 5 分钟 / 最多重复 3 次）发命中短信后**不做任何处理** | 通知依次显示「第 1 次提醒」→「已提醒 1 次 · 5 分钟后再次提醒」→「第 2 次」…共 4 轮，最后显示「提醒已结束（共 4 次）」 |
| C15 | 把应用从最近任务划掉 / 锁屏静置 6 分钟 | 第 2 轮**仍会响**（闹钟由系统托管，不依赖服务存活） |
| C16 | 亮屏且不点通知，静置 6 分钟 | 第 2 轮通知仍会到达（验证 §5.6 的乐观调度兜底） |
| C17 | 设置「重复间隔 = 0（不重复）」后再测 | 只响一轮，通知显示「提醒已结束」，状态栏无闹钟 |
| C18 | 设置「最多重复 = 不限」 | 一直重复直到点「停止」；点停止后立刻不再响 |
| C19 | 把「单轮时长」设为 30 秒 | 响 30 秒自动停，随后按间隔重复；不会出现 3 分钟超时 ANR |

**D. 边界与兼容（C20–C26）**

| # | 操作 | 预期 |
|---|---|---|
| C20 | 设置页四项下拉（时长/间隔/次数/夜间时段） | 摘要正确显示当前选中值（验证 `SimpleSummaryProvider` 迁移） |
| C21 | 设置页开关「必须包含全部关键词」 | 开关可点、状态持久化；规则演练结果随之变化 |
| C22 | 主界面点底部权限清单、设置页点「权限体检」 | 弹出 5 项清单，点未就绪项直达对应系统设置页 |
| C23 | 主界面 / 设置页 / 全屏告警页在挖孔屏 + 手势导航下 | 标题与底部按钮不被状态栏、导航条遮挡 |
| C24 | 夜间静音时段（如 22:00–07:00）内发命中短信 | 不响铃，只有震动 + 通知；`logcat` 可见 `policy: ring=false(quiet hours)` |
| C25 | 连续发两条相同的命中短信（3 秒内） | 只提醒一次（去重仍生效） |
| C26 | 升级前留在通知栏的旧通知 | 点「停止」可能无效（R2），划掉即可；新短信产生的通知一切正常 |

### 8.5 第五层：日志与闹钟核对

```bash
# 关键链路日志
adb logcat -s SMSReceiver AlertDispatcher AlertScheduler RepeatAlarmReceiver SmsService NotificationHelper AlertActionReceiver

# 确认精确闹钟已注册（应能看到 setAlarmClock 记录与下一次触发时间）
adb shell dumpsys alarm | grep -i bellgram
adb shell dumpsys alarm | grep -i messagefinder

# 确认通知渠道与全屏权限状态
adb shell dumpsys notification --noredact | grep -i messagefinder

# 手动触发一次闹钟广播（调试用，显式组件，验证接收器链路）
adb shell am broadcast -n com.saiga.find.messagefinder/.RepeatAlarmReceiver \
  -a com.saiga.find.messagefinder.action.REPEAT_ALERT --ei alert_round 2
```

日志关键字：`policy: ring=...`、`next alert scheduled, round=...`、`posted alert notification, round=...`、
`start alert service created (single-round player)`、`snooze requested`、`local stop`。

### 8.6 若构建或真机行为不符合预期

把以下三样发我，我按行定位并给出补丁：

1. `gradlew.bat :app:assembleDebug --stacktrace` 的完整输出；
2. §8.5 的 `adb logcat` 与 `dumpsys alarm` 片段；
3. 机型 / HyperOS 版本，以及失败的具体验收编号（如 C15）。

---

**报告结束。** 本轮共新增 8 个 Java 类、2 个 XML 规则文件、1 个测试类；重写 7 个既有 Java 文件、
3 个构建配置、3 个资源/布局文件。所有改动行均以 `// modified` 或 `<!-- modified -->` 标注，
完整整合后的源码见 §6。
