> ⚠️ **本报告是 v3.0.0 的历史存档（versionCode 17）。当前有效版本为 v4.0.0，请以
> [AUDIT_REPORT_V4.md](AUDIT_REPORT_V4.md) 为准。**
>
> 用户后续要求「app 要符合最新的 Android 应用规范」并授权「必要时，你可以重构整个应用的架构」，
> 因此 v4 完成了 Android 16（API 36）合规专项与架构级重构。本报告中**下列 4 项结论已被 v4 明确推翻**：
>
> | # | v3 结论 | v4 现状 | 推翻原因 |
> |---|---|---|---|
> | ① | `targetSdk` 有意保持 29 | **36** | Google Play 自 2026-08-31 起要求 API 36+；保持 29 不符合最新规范 |
> | ② | 前台服务声明 `shortService` | **`mediaPlayback`** | `shortService` 约 3 分钟超时，与 5 分钟重复提醒冲突，会触发 `onTimeout` / ANR |
> | ③ | 暂不迁移 `android.preference` | **已迁移 `androidx.preference`** | 平台 API 自 API 28 废弃，官方要求迁移 |
> | ④ | 重复提醒用前台服务内 Handler 长驻 | **`AlarmManager.setAlarmClock()` + 通知驱动** | targetSdk ≥ 31 后台不得启动 FGS；`SMS_RECEIVED` 不在豁免清单内 |
>
> v3 的 19 项 P1 缺陷修复、界面重做、12 项场景内功能、对标「短信铃铛」补齐的 5 项能力
> **全部保留有效**，v4 是在其之上的合规升级。本文以下 §0–§8 仅作为历史决策与逐项修复记录保留，
> 其中涉及上述 4 点的文字（如 §0① 版本矩阵、`targetSdk 29`）请勿再作为当前工程事实引用。

---

# 铃信 / Bellgram（message-finder）二次开发：审计与改造报告（v3 历史存档）

- 原始工程：`PriorityAlerts`（com.saiga.find.messagefinder，上游 saiga006/message-finder，GPL-3.0，版本 2.9.8 / versionCode 16）
- 改造工程：`PriorityAlerts-v3`（版本 3.0.0 / versionCode 17）
- 应用显示名：**铃信**（中文）/ **Bellgram**（英文）。目录名、包名、`applicationId` 均未改动，
  因此旧版本可直接覆盖安装、配置与通知渠道都沿用；改名只影响桌面图标文字、引导页标题、
  系统设置里的应用名（权限清单文案同步更新）与告警页标识。
- 审计方式：全量静态代码审查（Java 13 个文件 ≈ 2400 行 + 26 个资源文件 + Gradle 配置），逐行通读，不依赖运行期。
- 说明：本机没有 JDK / Android SDK，**未能实际编译**。已做的工作：括号与字符串字面量配平校验、资源引用完整性校验（`R.string` / `R.id` / `R.drawable` / `R.color` / `R.layout` / `R.xml` / `R.menu` 全部可解析）、逐条手工推演关键分支。首次 `gradlew assembleDebug` 若报个别语法/依赖问题，属于可快速修正的范畴。

---

---

## 0. 先回答你补充的三点

### ① 「2018 年老工程 / 新版 Android Studio 打不开」——已解决

| 项 | 原值（2018 年） | 现值 | 说明 |
|---|---|---|---|
| Gradle Plugin (AGP) | 3.2.1 | **8.5.2** | 新版 AS 只支持 7.x / 8.x |
| Gradle | 4.6 | **8.7** | AGP 8.5 要求 8.7+ |
| JDK（构建用） | 8 | **17** | AGP 8 强制要求 |
| compileSdk | 29 | **34** | 新版 androidx / material 要求 |
| minSdk | 23 | 23（不变） | 兼容 Android 6.0+ |
| targetSdk | 29 | **29（有意不变）** | 见下方说明 |
| manifest `package` | 有 | 已移除，改用 `namespace` | AGP 8 强制 |
| 组件 `exported` | 缺失 | 已显式声明 | API 31+ 必需 |
| material 库 | 1.3.0-**alpha02** | 1.11.0（正式版） | 去掉 alpha 依赖 |

打开步骤：
1. Android Studio → `Open` → 选 `PriorityAlerts-v3` 目录（**不是**里面的 `app`）；
2. 首次会提示同步，耐心等 Gradle 8.7 下载（wrapper 已指向腾讯云镜像，若失败按 `gradle-wrapper.properties` 里的注释换成官方地址）；
3. `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选 **17**（这一步不做，必然报 `Unsupported class file major version`）。

> **为什么 targetSdk 不跟着升到 34？** 这不是偷懒：本应用的主链路是「短信广播 → 启动前台服务」，
> 而 Android 12(API 31) 起明确禁止应用在后台启动前台服务，豁免清单里**没有** `SMS_RECEIVED` 广播。
> targetSdk 一旦 ≥31，这条链路会直接被系统拒绝，核心功能失效。
> 个人自用、不上架应用商店的场合，保持 29 是唯一稳妥选择。代码里已同时补了异常捕获与降级通知，
> 将来若必须升级，至少不会崩，只需再处理通知渠道/全屏 Intent 策略（届时单独出方案）。

### ② 界面美化——已重做（详见 4.5 / 5.14）

改动覆盖**主界面、引导页、授权弹窗、设置页**四处，且**所有控件 id 保持原样**，业务代码不需要跟着改：
- 主界面：渐变乱背景 → 浅灰底 + 白色圆角卡片；散落按钮 → 主按钮（实心蓝）+ 次按钮（描边）；
- 分区标题 + 字段说明重排，输入区一眼看懂「哪个必填、哪个可选」；
- 新增**状态条**：绿色「监听中 · 关键词：报警」或灰色「尚未监听」，解决“重启后不知道还在不在生效”的困惑；
- 引导页：去掉写死的 `673dp` 高度与 `104dp` 上边距（小屏会挤爆），改为弹性三段式；指示点与按钮不再重叠；
- 授权弹窗：图标定宽 + 文字自适应，不再被 `weight=1` 挤成两半。

### ③ 主功能确认——与你的描述完全一致

> 「把手机内具有特定内容信息的短信进行特殊提醒，比如响闹铃；因为短信提示音力度不够，容易疏漏。」

这就是本项目的原始定位，我**没有偏离**它，反而把「提醒力度」这条线补强了：
| 环节 | 做法 | 本次是否加强 |
|---|---|---|
| 触发条件 | 号码（可选）+ 关键词（任一命中，支持中文/全角标点） | ✅ 修好了中文关键词拆词、11 位号码截断 |
| 提醒方式 | 内置闹钟铃声**循环播放** + 最大音量（接管 `STREAM_ALARM`） | ✅ 修好了“音量被写成 0”事故，加了唤醒锁防息屏中断 |
| 视觉提醒 | `IMPORTANCE_HIGH` 高优先级通知 + 锁屏可见 + 尝试绕过免打扰 | ✅ 修好了双重响铃；新增点击通知可进应用 |
| 震动 | 自定义波形循环振动 | ✅ 加了 `hasVibrator()` 防护 |
| 可靠性 | 前台服务常驻 + 短信广播 | ✅ 加了降级通知、重复投递去重、长短信拼接 |
| 可核对性 | —— | ✅ 新增「最近命中记录」，能证明“到底响没响过” |

### ④ 红米 K60 Pro + 多种提醒方式 + 未处理就「5 分钟后再响」——已实现（详见 1.5 / 4.6 / 5.15）

你的诉求拆开是三件事，逐条对应如下：

| 你的诉求 | 实现方式 | 默认值 |
|---|---|---|
| 有多种提醒方式 | **响铃**与**震动**可独立开关（设置页顶部「提醒方式」分组） | 两者都开 |
| 铃声不能一直响到烦 | **单次响铃时长**可选：直到我手动停止 / 30 秒 / 60 秒 / 2 分钟 | 60 秒 |
| 没处理就再提醒一次（像闹钟的“贪睡”） | **重复提醒间隔** + **最多重复次数**，到点自动再响一轮 | 每 5 分钟、最多 4 轮（首次 1 + 重复 3） |
| 别无限吵 | 「最多重复」可选 1/3/5/10 次或「不限」；点通知上的「停止」立即全停 | —— |
| 红米手机上要能活下来 | 主界面写死 4 步 HyperOS 必做清单（自启动 / 省电无限制 / 允许锁屏通知 / 短信权限） | —— |

关键行为（与系统闹钟的贪睡一致）：
1. 短信命中 → 立刻响铃 60 秒 + 循环震动，通知上写「第 1 次提醒」；
2. 60 秒到 → 停铃停震、**把闹钟音量还原**（不让手机长时间停在最大音量），通知改成「已提醒 1 次 · 5 分钟后再次提醒」；
3. 5 分钟到 → 再响一轮，通知变「第 2 次提醒」；
4. 达到「最多 4 轮」→ 通知变「提醒已结束（共 4 次）」，服务退出前台，通知保留供你回看；
5. 任何时刻点「停止」或划掉通知 → 定时器全部取消，不会再响。

> 为什么不做「不处理就永远响下去」：那是闹钟的语义（必须被处理），短信不是。
> 在 `USAGE_ALARM` + 最大音量 + 绕过免打扰的组合下持续 20 分钟以上，会在会议、地铁等场合造成实际困扰。
> 需要极端强度时，把「最多重复」选成「不限」即可——两种强度都留给你自己选。

### ⑤ 对标「短信铃铛」——已补齐 5 项（详见 1.6 / 5.16）

> 你提到「可参考类似软件 短信铃铛」。我按它的功能清单逐项比对，挑出**确实缺、且直接服务于“不漏看”**的五项做了实现，
> 其余四项（正则匹配、权限体检页、历史搜索、多规则组）明确不做，理由写在 1.6 的表里。

#### 本轮新增五项（均已实现，含单元测试）

| # | 能力 | 短信铃铛怎么做 | 我们怎么做 | 默认值 |
|---|---|---|---|---|
| 1 | **全部关键词才提醒** | 「同时包含全部关键词」规则 | 设置页开关 `match_all`；`KeywordMatcher.findMatch(..., matchAll)` | 关（保持原“任意命中”） |
| 2 | **夜间静音（只震动）** | 全局静音时段 22:00–07:00 | 设置页「夜间静音」四档预设 + `QuietHours` 跨零点判定（有单测） | 关 |
| 3 | **规则演练** | 保存前填示例短信验证 | 主界面「规则演练」→ 粘一段短信，**边输边判**命中/未命中 | —— |
| 4 | **稍后提醒** | 全屏告警上两个按钮：停止 / 稍后提醒 | 通知新增「稍后提醒」动作 + 全屏告警页同名按钮；静音后按间隔再响 | 间隔取「重复间隔」 |
| 5 | **锁屏全屏告警** | 锁屏直接弹全屏，必须手动处理 | `AlertActivity` + `setFullScreenIntent` + `USE_FULL_SCREEN_INTENT`；锁屏/息屏时自动拉起 | 开（随提醒一起） |

一句话说明为什么是这五项：**1 解决“误报太多不敢用”，2 解决“半夜被吵只能关掉”，3 解决“规则写错了自己不知道”，
4 解决“现在不方便，但别让它彻底结束”，5 解决“横幅被忽略”。** 这五条正好把「短信铃铛」里和“提醒力度”有关的部分补齐了。

---

## 1. 同类方案调研小结

### 1.1 参照对象

| 项目 | 规模/活跃度 | 技术栈 | 与本项目的关系 |
|---|---|---|---|
| **pppscn/SmsForwarder** | ≈26.4k stars，3.2k forks，2026-06 仍在提交 | Kotlin + Room + 多通道 Adapter | 国内同类天花板，功能是“短信/来电/通知 → 规则 → 13 个下游通道” |
| **capcom6/android-sms-gateway** | 中等规模，持续维护 | Kotlin + Room + Hilt + WorkManager + Retrofit | 把自己当“短信网关服务端”，工程分层最规范 |
| **xiaoyuanhost/TranspondSms** | SmsForwarder 的 Java 原型 | Java + SharedPreferences | 与本项目同代、同技术栈，可对照“老 Java 写法”的退化路径 |
| Android 官方行为变更文档 | — | — | Android 12 后台启动前台服务限制、Android 14 前台服务类型必填 |

### 1.2 可借鉴的优秀做法（本次已采纳）

1. **三段式分层：事件源 → 规则引擎 → 执行器**。SmsForwarder 把“匹配”从“接收”里剥出来，独立成 Rule Engine。本项目原先在 `SMSReceiver` 里直接 `split` 再 `contains`，无法测试、无法复用。本次抽出 `KeywordMatcher`（归一化+解析+匹配）与 `PhoneNumbers`（号码归一化+比对）两个纯 Java 类，`MainActivity` 与 `SMSReceiver` 共用同一套规则——这是**消除“输入侧和匹配侧规则不一致”这类经典 bug 的唯一可靠办法**。
2. **命中日志（转发日志）**。SmsForwarder 的日志是其排障能力的核心。本项目新增 `HitLog`（最近 20 条，仅本地 SharedPreferences，不新增权限、不引入数据库），设置页可见、可清空。
3. **长短信先拼接后匹配**。GSM 每条 160 字符（中文 70 字）就会被拆包，`getMessagesFromIntent` 返回的是分片数组。原实现逐片匹配，关键词跨包即漏报。已改为拼接后再匹配。
4. **通知渠道与自播铃声解耦**。SmsForwarder 这类应用普遍把渠道设为静音、自己用 MediaPlayer/Ringtone 控制，否则“系统通知音 + 自播铃声”必然双响。本项目原实现正是这个坑。
5. **降级通道**。当主路径（前台服务）被系统拒绝时，退化为普通高优先级通知，至少不漏报。

### 1.3 明确不采纳的做法（避免过度设计）

| 方案 | 为什么不采纳 |
|---|---|
| 引入 Room + Kotlin 协程 + Hilt 重写 | 本项目只有“一条规则 + 一次提醒”，数据模型是 3 个键。引入 ORM/DI 只会增加 APK 体积、迁移成本与崩溃面，属于典型的过度设计。 |
| 用 WorkManager 替代前台服务 | WorkManager 最小调度粒度约 10 分钟级，且不能持有 MediaPlayer 持续响铃，会直接破坏“立即响、持续响”的核心目标。 |
| 升级 targetSdk 到 34/35 | 见 1.4 第 1 条，会**直接打断主链路**。 |
| 引入正则匹配、多通道转发、远程控制 | 与本项目“个人自用、单一目标”的定位不符，属于“无关花哨功能”。 |

### 1.4 必须规避的坑（都是本次实际修掉的）

1. **targetSdk 与前台服务**：Android 12(API 31) 起，应用在后台时**不允许**启动前台服务，例外清单里**不包含** `SMS_RECEIVED` 广播。本应用的主链路恰好是“短信广播 → 启动前台服务”，因此 **targetSdk 一旦 ≥31，核心功能会直接失效**。结论：个人自用、不走 Play 分发场景下，保持 `targetSdk 29` 是正确选择；代码里同时补了 `ForegroundServiceStartNotAllowedException` 的捕获与降级，为将来必须升级时留好退路。
2. **Android 14 起前台服务类型必填**：targetSdk ≥34 时必须声明 `android:foregroundServiceType`，否则 `startForeground()` 抛 `MissingForegroundServiceTypeException`。已提前声明 `shortService`（targetSdk 29 下系统忽略，属向后安全的预防性声明）。
3. **PendingIntent 可变性**：targetSdk ≥31 时未声明 `FLAG_IMMUTABLE`/`FLAG_MUTABLE` 会直接抛 `IllegalArgumentException`。已补齐。
4. **`contains` 做号码匹配**：配置 `1234` 会命中 `1000012349999`。已改为“规范化后精确相等 / 长度足够时后缀相等”。
5. **“保存音量”默认 0**：把“未保存”和“音量为 0”混为一谈，是这类闹钟应用最常见的“把用户手机闹钟搞静音”事故。已用显式标志位 `volumeBoosted` 隔离。
6. **强转 `HashSet`**：`SharedPreferences.getStringSet` 只保证返回 `Set`，实现类随版本/OEM 变化，强转是定时炸弹。

### 1.5 补做：重复提醒（贪睡）机制的调研与取舍

针对你提的「未处理就像闹钟一样 5 分钟再提醒一次」，我看了三类实现，结论如下：

| 参照对象 | 它的做法 | 本次取舍 |
|---|---|---|
| 系统闹钟（AOSP DeskClock） | AlarmManager 精确闹钟 + `setAlarmClock()` + 全屏 Intent；响铃到时限后进入 snooze，默认 10 分钟 | **只借“贪睡”语义，不借 AlarmManager**：精确闹钟在 Android 12+ 需 `SCHEDULE_EXACT_ALARM` 特殊权限，个人应用申请不到，落地即失败 |
| SmsForwarder / Tasker 的“重复提醒” | 定时器（Handler / Timer）+ 通知上的「稍后提醒」按钮，可配间隔与次数 | **采纳**：Handler 定时 + 间隔/次数可配 + 通知上「停止」；不做按钮式 snooze（自动重复已覆盖同一需求，少一个按钮少一份歧义） |
| 各类“重要提醒/闹钟”类 App 的默认值 | 间隔 5 分钟、重复 3 次左右、单轮响铃 30–60 秒 | **采纳为默认值**：60 秒 × 每 5 分钟 × 最多 4 轮，覆盖“洗澡/开会/开车”这类 15 分钟量级的脱手场景 |

在“用什么驱动重复”上的三个候选，以及最终选择：

| 方案 | 可行性 | 结论 |
|---|---|---|
| `AlarmManager.setExactAndAllowWhileIdle` | Android 12+ 需用户在系统设置里单独授予“闹钟和提醒”权限，否则抛 `SecurityException`；被拒绝后**没有任何提醒** | ❌ 不用 |
| `WorkManager` | 最小周期约 15 分钟，无法满足“5 分钟再提醒” | ❌ 不用 |
| `Handler.postDelayed` + `PARTIAL_WAKE_LOCK` | 服务已在前台，局部唤醒锁可保证 CPU 不休眠、Handler 按时触发；无额外权限 | ✅ 采用（见 5.15，含持锁时长计算与上限） |

> 唤醒锁的代价必须说清楚：重复提醒期间会持续持有 `PARTIAL_WAKE_LOCK`（最长 60 分钟封顶）。
> 它只阻止 CPU 休眠、不点屏幕，耗电约等于“亮屏待机”的十分之一。
> 相比“错过一条银行/家人/工作的重要短信”，这个代价是可接受的；用完即释放，不常驻。

## 1.6 与「短信铃铛」的功能对标（采纳 / 不采纳）

| 它的能力 | 是否有价值 | 本轮决定 | 说明 |
|---|---|---|---|
| 任意关键词 / 全部关键词 / 正则 / 限定发件人+时段 | 高 | **采纳前两项** | 正则对普通用户是门槛而非帮助，且容易写出灾难性回溯；发件人与时段本项目已有（号码规则 + 夜间静音） |
| 锁屏全屏告警，必须手动处理 | 高 | **采纳** | 见 5.16.5；这是“叫醒力度”的实质提升 |
| 稍后提醒（snooze）按钮 | 高 | **采纳** | 见 5.16.4；与自动重复互补：一个是你主动推迟，一个是它自动催 |
| 演练（先验证再保存） | 高 | **采纳** | 见 5.16.3；本应用“静默失败”的特性决定了它必须能自证 |
| 全局静音时段 | 高 | **采纳** | 见 5.16.2；不做的话用户半夜只能整个关掉提醒 |
| 触发历史 500 条 + 搜索/筛选/复制/批量删除 | 中 | **部分采纳** | 已有最近 20 条只读记录；扩容到 500 条并做搜索界面，收益低于复杂度，暂不做 |
| 权限体检（逐项检测 + 跳转） | 中高 | **暂不采纳** | 需要额外做检测页与多个厂商的跳转 Intent；本轮先把四步清单写进主界面。若你实际用下来仍被杀后台，我再补这一页 |
| 多规则组（不同短信不同铃声/强度） | 中 | **不采纳** | 会把“一条规则”的数据模型扩成列表，属于架构级改动，需单独确认 |
| 联网登录 / 权益同步 | 低 | **不采纳** | 本项目定位是纯本地离线，零联网（这也是它的优点） |

> 另外记一笔：它的说明里也写了「国产手机要手动加自启动白名单、关闭电池优化，否则息屏后短信广播送不进来」——
> 这和我给你的红米四步清单是同一件事，不是我们的代码问题，是 OEM 策略。

---

## 2. 项目理解小结（请核对是否与你的理解一致）

### 2.1 定位

一个**纯本地、离线、零联网**的“短信关键词告警器”（SOS / 优先提醒）。它解决的不是“收短信”，而是“**在静音、锁屏、勿扰状态下，让特定短信以最大音量把你叫醒**”。

### 2.2 运行原理（主链路）

```
系统收到短信
  → 广播 SMS_RECEIVED（本应用常驻监听，无界面进程亦可）
  → SMSReceiver：读 SharedPreferences(contact / message)
                → 号码过滤（可选，空=不限）
                → 关键词 OR 匹配（任一命中即触发）
  → startForegroundService 携带 From/Keyword/Payload/Person
  → SMSForegroundService：
        · 建通知渠道（IMPORTANCE_HIGH）
        · 把 STREAM_ALARM 音量拉到最大（保存原值）
        · MediaPlayer 循环播放 APK 内置 raw/priority_ringtone
        · Vibrator 循环振动
        · 高优先级通知（含 STOP 动作）→ startForeground
  → 用户点 STOP / 划掉通知
        · 停铃声、停振动、恢复原音量、移除通知、stopSelf
```

### 2.3 组件职责

| 组件 | 职责 |
|---|---|
| `MainActivity` | 配置入口：号码（可选）+ 关键词（必填）；联系人选择器；权限申请；Snackbar 反馈 |
| `Onboarding` + `SliderAdapter` | 3 屏首次引导，完成后把自身组件 `COMPONENT_ENABLED_STATE_DISABLED` |
| `SettingsActivity` + `AppPreferenceFragment` | 展示当前生效配置、版本号、自动联想开关、隐私政策 |
| `MyDialogFragment` | 自动联想（读联系人）权限的说明框：SET / DISMISS / 不再显示 |
| `SMSReceiver` | 广播入口，规则匹配 |
| `SMSForegroundService` | 响铃 / 振动 / 通知 / 音量接管与还原 |

### 2.4 原有设计约束（本次全部保留）

1. 号码可留空 = 关注所有发送方；
2. 关键词多条，任一命中即触发（OR 语义）；
3. 无关键词配置则完全不监听（不打扰）；
4. 配置持久化，重启后仍生效；
5. 引导页只在首次安装出现一次；
6. 提醒必须“能停”，且停止后必须恢复用户原音量；
7. 不申请存储权限，铃声随 APK 打包；
8. 不联网、不上传。

---

## 3. 审计问题汇总

> 级别定义：**P1** = 崩溃 / 功能失效 / 数据损坏；**P2** = 质量与可维护性；**P3** = 构建与工程化。

### 3.1 P1：逻辑错误、崩溃与功能失效

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| 1 | `MainActivity.spliceNumber()` | 只保留号码**末 10 位**：`"+8613800138000"` → `"3800138000"` | **中国大陆 11 位手机号被截坏**，“限定号码”功能在国内 100% 失效（永远匹配不上） |
| 2 | `MainActivity.onTextChanged()` + `activity_main.xml` | 长度校验 `s.length() > 10` 判为非法，`counterMaxLength="10"` | 11 位手机号**根本录不进去**，与 #1 形成双重封锁 |
| 3 | `SMSReceiver.onReceive()` | `(HashSet<String>) msgPref.getStringSet(...)` | `getStringSet` 只保证返回 `Set`，实现类非 `HashSet` 时 **ClassCastException**，广播内崩溃 → “应用已停止运行” |
| 4 | `SMSReceiver.onReceive()` | `Objects.requireNonNull(msg.getOriginatingAddress())` | 发送方地址为空（部分端口短信/虚拟号）时 **NullPointerException** |
| 5 | `SMSReceiver.onReceive()` | `msg.getDisplayMessageBody().toLowerCase()` 未判空 | 正文为 null 时 **NullPointerException** |
| 6 | `SMSReceiver.onReceive()` | 整个方法无 try/catch | 任何异常都直接暴露为系统崩溃弹窗 |
| 7 | `SMSReceiver.onReceive()` | `sender.contains(number)` | 配置 `1234` 会命中 `1000012349999`，**误报**（半夜乱响） |
| 8 | `SMSForegroundService.onStartCommand()` 停止分支 | `mediaPlayer.stop()` 无状态判断 | 铃声尚未 `prepare` 完成时点 STOP → **IllegalStateException** 崩溃；`mediaPlayer` 已为 null 时 **NPE** |
| 9 | `SMSForegroundService` 音量逻辑 | `current_vol_index` 默认 `0`，仅在 `else` 分支赋值 | 若 `IOException` 发生在赋值前，STOP 时执行 `setStreamVolume(..., 0, ...)` → **把用户手机闹钟音量永久写成 0**（最隐蔽也最严重） |
| 10 | `SMSForegroundService.onStartCommand()` | `startForeground()` 放在响铃与振动**之后** | Android 8+ 要求 `startForegroundService` 后 5 秒内 `startForeground`，慢机/首次解码可能超时 → ANR |
| 11 | `SMSForegroundService` | `PendingIntent.getService(..., 0)` 未声明可变性 | targetSdk ≥31 时 **IllegalArgumentException**（当前 29 侥幸不触发，是升级路上的地雷） |
| 12 | `MainActivity.onActivityResult()` | `Cursor` 未关闭；`data` / `moveToFirst()` 未判空 | **Cursor 泄漏**；空结果时异常被 `catch(Exception)` 静默吞掉，用户无感知 |
| 13 | `MainActivity.onCreate()` | 引导页分支 `finish()` 后**继续执行**后续初始化 | 对一个正在销毁的界面做 `setContentView` / 注册监听，浪费且时序脆弱 |
| 14 | `MainActivity.onDestroy()` | `contact_number_layout.getEditText()` 无判空 | 若 onCreate 提前退出（引导分支），**NPE** |
| 15 | `MainActivity.setSmsConfig()` | 依赖成员变量 `messageStr` / `contactStr` | 权限回调前发生配置变更（旋转/重建）→ `messageStr == null` → `split` **NPE** |
| 16 | `MainActivity.checkReadContactPermission()` | 延迟 1.5s 弹 `DialogFragment`，未校验 Activity 状态与 `isStateSaved()` | 用户秒退 / 旋转屏幕 → `IllegalStateException: Can not perform this action after onSaveInstanceState` |
| 17 | `MyDialogFragment` | `(MainActivity) requireActivity()` 硬转 | 宿主变化或已销毁 → **ClassCastException** / NPE |
| 18 | 通知渠道 + MediaPlayer | 渠道 `IMPORTANCE_HIGH` 未静音，同时 MediaPlayer 播铃声 | **双重响铃**（系统通知音 + 闹钟铃声叠在一起），且渠道创建后声音属性不可由代码修改 |
| 19 | `MainActivity.onTextChanged()` | 校验规则与保存逻辑不一致（飘红但仍可保存） | 规则里可能存进脏号码 |

### 3.2 P2：代码质量

| # | 位置 | 问题 |
|---|---|---|
| 20 | 全局 | 键名 `"contact" / "message" / "contact_name"` 硬编码散落在 3 个类中，改一处漏两处 |
| 21 | `MainActivity` | `private static SharedPreferences config` 持有 Context 相关引用，生命周期错配 |
| 22 | `MainActivity.checkUserConfig()` | 方法名语义为“检查用户配置”，实际返回“自动联想是否禁用”，**命名与行为相反** |
| 23 | `TestService.java` | 342 行**全部被注释**的死文件，仍参与源码统计与阅读 |
| 24 | `MainActivity` / `SMSForegroundService` | 大量注释掉的 deprecated 实现（loader、Ringtone、storage 权限） |
| 25 | `MainActivity.onRequestPermissionsResult()` | 4 段近似重复的 Snackbar 权限提示代码 |
| 26 | `activity_main.xml` | 硬编码英文 `app:helperText="Max 10 digits"` 未走资源 |
| 27 | `MainActivity.onTextChanged()` | 用 `setBackgroundColor()` 表示禁用，破坏 `rounded_button_gradient` 样式且不可逆 |
| 28 | 全局 | 日志 TAG 混乱：`"Sms Receiver"` / `"SMSReceiver"` / `"SmsService"`，无法按统一 TAG 过滤 |
| 29 | `SettingsActivity` | 摘要只在 `onCreate` 读一次；改完配置返回仍显示旧值 |
| 30 | `Onboarding.setPosition()` | `mDotColor[position]` 无边界保护，页数 > 颜色数时 `ArrayIndexOutOfBoundsException` |
| 31 | `Onboarding` | 首屏“上一步”按钮可见但不可用（只在滑动后才隐藏） |
| 32 | `SliderAdapter.calculateInSampleSize()` | `reqWidth` 可能 ≤0，`while` 条件恒真 → **死循环** |
| 33 | 全局 | 零单元测试，改任何逻辑都只能靠真机手测 |
| 34 | `strings.xml` | 版本号 `2.9.8` 写死在资源里，与 `build.gradle` 双份维护 |

### 3.3 P3：构建与工程化

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| 35 | `build.gradle` / wrapper | AGP 3.2.1 + Gradle 4.6 | Android Studio Giraffe(2022.3) 之后**无法打开/构建**，项目实际上已“不可开发” |
| 36 | `AndroidManifest.xml` | `package="..."` 属性 | AGP 8 要求改用 `namespace`，两者并存会构建失败 |
| 37 | `AndroidManifest.xml` | 组件缺 `android:exported` 显式声明 | targetSdk ≥31 时安装/启动报错 |
| 38 | `AndroidManifest.xml` | 无 `WAKE_LOCK` | 息屏/深度休眠时铃声可能被系统掐断（与“必定叫醒”的核心目标冲突） |
| 39 | `app/build.gradle` | `material:1.3.0-alpha02`（alpha 版） | 使用 alpha 版本的 UI 库存在未知缺陷 |
| 40 | `gradle.properties` | `-Xmx1536m` | AGP 8 下易 OOM |

### 3.4 本轮自查补记（改造过程中发现并当场修掉的隐患）

| # | 位置 | 问题 | 处理 |
|---|---|---|---|
| 41 | `values/strings.xml` | `permission_tip` 文案里的 `lock screen & floating notifications` 含**未转义的裸 `&`**，属非法 XML，`aapt` 会直接编译失败 | 已改为 `&amp;`；同时把“XML 良构性”加入静态校验脚本（见 8.7） |
| 42 | `values/strings.xml` 引用的 `ring_enabled_key` / `vibrate_enabled_key` | 偏好 XML 先写了引用、资源后补，中间态会导致 `@string/xxx` 解析失败 | 已补齐（中英各 108 条，键名完全对称，脚本已校验无缺失、无单边） |
| 43 | `NotificationHelper.build()` | 若直接改成 7 参，会破坏原有 6 参调用点 | 保留 6 参重载并委托给 7 参版本，**接口向后兼容** |
| 44 | `SMSForegroundService.acquireWakeLock()` | 原来固定 10 分钟，重复提醒最长可达 40 分钟以上，锁到期后 Handler 可能被挂起 | 改为按「轮次 ×（单轮时长 + 间隔）」估算，区间 10–60 分钟，并在已持锁时按剩余时间决定是否续期 |

---

## 4. 变更总览

### 4.1 修复（对应 P1，共 19 项）
号码 11 位截断与校验、`HashSet` 强转、发送方/正文空指针、广播无兜底、`contains` 误匹配、STOP 崩溃、音量静音事故、`startForeground` 时序、PendingIntent 可变性、Cursor 泄漏、onCreate 提前返回、onDestroy NPE、`setSmsConfig` NPE、延迟弹窗时序、DialogFragment 强转、双重响铃、校验与保存不一致。

### 4.2 重构（局部，未动架构）
1. 新增 `AppConfig`：配置读写的唯一入口（键名、`Set` 类型安全、默认值集中管理）。
2. 新增 `KeywordMatcher` / `PhoneNumbers`：规则解析与匹配变为纯 Java、可单测、两侧共用。
3. 新增 `NotificationHelper`：通知/渠道/`PendingIntent`/extras 键名统一构建，服务与降级路径共用。
4. `MainActivity` 拆分：`handleTrigger` / `handleClear` / `saveConfig` / `showSnackbar` / `showPermissionSnackbar` / `showContactPickerDialog` / `setTriggerEnabled`，消除重复。
5. 删除 `TestService.java`（342 行纯注释死文件）。
6. 构建工具链现代化（AGP 8.5.2 / Gradle 8.7 / JDK 17），**运行期 targetSdk 保持 29 不变**。

### 4.3 新增功能（12 项，均贴合原场景，无花哨功能）
| 功能 | 价值 |
|---|---|
| **F1 长短信拼接后匹配** | 关键词跨包时不再漏报（验证码/长通知类短信常见） |
| **F2 命中记录（最近 20 条）+ 清空** | 不响时能立刻判断“没收到 / 没匹配 / 服务没起来” |
| **F3 通知点击打开应用 + 划掉即停 + 降级通道** | 原来点通知正文无反应；服务起不来时完全静默失败 |
| **F4 重复投递去重（3 秒窗口）** | 部分 ROM 重复投递 `SMS_RECEIVED`，原来会连响两次 |
| **F5 提醒方式可配置（响铃 / 震动独立开关）** | 会议室只需震动、夜间只需响铃，不必二选一 |
| **F6 贪睡式重复提醒（间隔 + 次数 + 单轮时长）** | 这正是你提的“没处理就 5 分钟再响一次”，且强度可控 |
| **F7 提醒进度写进通知（第 N 次 / 几分钟后再响 / 已结束）** | 重复提醒最怕“不知道还会不会响”，通知上直接给出答案 |
| **F8 匹配模式：任意 / 全部关键词** | 误报是这类应用被弃用的第一原因；「全部命中」把“到账”+“银行名”变成一条精准规则 |
| **F9 夜间静音时段（只震动）** | 不做的话用户半夜只能整体关掉提醒，第二天的重要短信一起漏掉 |
| **F10 规则演练（边输边判）** | 规则写错时本应用没有任何反馈；演练让“会不会响”在保存前就能看见 |
| **F11 稍后提醒（snooze）动作** | 此刻不方便处理时，不必被迫选择“继续吵”或“彻底放弃这次提醒” |
| **F12 锁屏全屏告警页** | 横幅会被忽略、会被折叠；全屏页必须点「停止」或「稍后提醒」才能继续用手机 |

### 4.4 界面改版（对应你补充的第 ② 点）
| 页面 | 改动 |
|---|---|
| 主界面 | 卡片化重排；主/次按钮分级；新增绿色/灰色状态条；新增底部权限与保活提示；文案从“Trigger!/启动！”改为“启用提醒” |
| 引导页 | 布局弹性化（去掉 673dp/104dp 硬编码）；指示点与按钮分离；按钮等宽 |
| 授权弹窗 | 图标定宽 72dp，文字自适应 |
| 设置页 | 统一浅灰底；标题改为「应用设置」；新增「最近命中记录」「清空命中记录」 |
| 全局 | 新增浅灰底/卡片/状态三类颜色 + 卡片与状态条背景 drawable + 三个样式；窗口背景由斜向渐变改为纯色 |

### 4.5 明确未改动
- 架构：仍是 `BroadcastReceiver + Service + SharedPreferences`，没有引入 Room/RxJava/DI/协程。
- 依赖：**没有新增任何第三方库**（只升级了已有库的版本）。
- `android.preference` 体系：虽已废弃，但功能完好，替换需同时改布局与主题，按“稳定性优先”保留不动。
- 文案与图标、引导页流程、隐私政策链接：保持原样。

### 4.6 本轮针对红米 K60 Pro 的针对性改造（对应你补充的第 ④ 点）
| 项 | 做法 |
|---|---|
| 提醒方式 | 设置页新增「提醒方式」分组：响铃开关、震动开关、单轮响铃时长、重复间隔、最多重复次数 |
| 重复提醒 | `Handler` 定时 + 局部唤醒锁；每轮结束还原闹钟音量，避免长时间占用最大音量 |
| 通知可读性 | 折叠视图右下角与子标题同时显示轮次状态（`setSummaryText` + `setSubText`） |
| 入口可发现性 | 主界面在「清除配置」下方新增「提醒方式与重复」文字按钮（原来只能从右上角菜单进设置页） |
| 状态可见性 | 主界面状态条第二行直接显示当前策略，例如「提醒方式：闹钟铃声 · 震动 · 每 5 分钟提醒一次，最多 4 次」 |
| 保活指引 | 主界面提示文案改为 HyperOS 四步清单（自启动=开 / 省电策略=无限制 / 允许锁屏与悬浮通知 / 允许接收短信） |
| 锁屏处理 | 新增全屏告警页（锁屏/息屏自动拉起），两个按钮「停止 / 稍后提醒」，返回键等同稍后提醒 |
| 规则可信度 | 新增「规则演练」，与真机使用同一套匹配入口，避免“设置写错却不自知” |

---

## 5. 分点变更详情

> 全部源码中，改动/新增处以 `// modified` 单行注释标注；纯新增文件在文件头用 `// [新增文件]` 说明设计意图。以下只列关键项，其余同类改动见源码注释。

### 5.1 【P1-1/2】号码 10 位硬编码 → 中国大陆 11 位

**代码位置**：`MainActivity.spliceNumber()`（已删除）、`MainActivity.onTextChanged()`、`app/src/main/res/layout/activity_main.xml`

**原始问题**
```java
// 原 MainActivity：只保留末 10 位
int formattedLength = formattedNum.length();
return (formattedLength > 10) ? formattedNum.substring(formattedLength - 10) : formattedNum.toString();
```
`"+8613800138000"` 规范化后长度 13，`substring(3)` = `"3800138000"` —— **首位 1 被吃掉**。
同时 `onTextChanged` 里 `s.length() > 10` 直接判非法，用户连输入都做不到。

**修改思路**：号码处理独立成 `PhoneNumbers`，按“去掉分隔符 → 去 0086/86 前缀 → 超长取末 11 位”处理；校验放宽到国际通用的 5–15 位；比对由 `contains` 改为“精确相等或后缀相等”，并设最小长度门槛防误报。

**改动代码**
```java
// PhoneNumbers.java（新增）
public static String canonical(String raw) {
    String stripped = stripSeparators(raw);
    if (stripped.isEmpty()) return "";
    String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
    if (digits.startsWith("0086") && digits.length() > 4) {
        digits = digits.substring(4);
    } else if (digits.startsWith("86") && digits.length() > CN_MOBILE_LENGTH) {
        digits = digits.substring(2);
    }
    if (digits.length() > CN_MOBILE_LENGTH) {          // 仍超长则取末 11 位
        digits = digits.substring(digits.length() - CN_MOBILE_LENGTH);
    }
    return digits;
}

public static boolean matches(String configured, String sender) {
    String configuredNumber = canonical(configured);
    if (configuredNumber.isEmpty()) return true;        // 空 = 不限号码
    String senderNumber = canonical(sender);
    if (senderNumber.isEmpty()) return false;
    if (configuredNumber.equals(senderNumber)) return true;
    return configuredNumber.length() >= MIN_SUFFIX_LENGTH
            && senderNumber.length() >= MIN_SUFFIX_LENGTH
            && (senderNumber.endsWith(configuredNumber) || configuredNumber.endsWith(senderNumber));
}
```
```java
// MainActivity.onTextChanged（修改后）
String value = s == null ? "" : s.toString().trim();
boolean invalid = !value.isEmpty() && !PhoneNumbers.isValidInput(value); // modified
```
```xml
<!-- activity_main.xml -->
app:helperText="@string/contact_helper"   <!-- 原 "Max 10 digits" 硬编码 -->
app:counterMaxLength="15"                  <!-- 原 10 -->
```

### 5.2 【P1-3/4/5/6】广播接收器全面加固

**代码位置**：`SMSReceiver.onReceive()`

**原始问题**
```java
HashSet<String> keywordContents = (HashSet<String>) msgPref.getStringSet("message", null); // 强转风险
if ((number == null) || ((Objects.requireNonNull(msg.getOriginatingAddress())).contains(number))) { // NPE
    String messageBody = msg.getDisplayMessageBody().toLowerCase(); // 正文 null -> NPE
```
且整个方法没有 try/catch —— 崩溃直接暴露给用户。

**修改思路**：外层兜底 + 全部空值安全 + 类型不再强转（走 `AppConfig.getKeywords()` 返回 `Set` 的只读副本）。

**改动代码**
```java
@Override
public void onReceive(Context context, Intent intent) {
    if (context == null || intent == null) return;
    try {
        handleSms(context, intent);
    } catch (Exception e) {          // modified: 广播内异常会直接崩溃进程
        Log.e(TAG, "handle sms failed", e);
    }
}

private String readOriginatingAddress(SmsMessage message) {   // modified: 空值安全
    String address = null;
    try { address = message.getDisplayOriginatingAddress(); } catch (Exception e) { ... }
    if (TextUtils.isEmpty(address)) {
        try { address = message.getOriginatingAddress(); } catch (Exception e) { ... }
    }
    return address == null ? "" : address;
}
```

### 5.3 【F1】长短信拼接后匹配

**代码位置**：`SMSReceiver.handleSms()`

**原始问题**：`for (SmsMessage msg : msgs) { ... }` 逐片匹配。中文短信 70 字即拆包，关键词跨包时**漏报**。

**改动代码**
```java
StringBuilder bodyBuilder = new StringBuilder();
for (SmsMessage message : messages) {
    if (message == null) continue;
    if (sender == null) sender = readOriginatingAddress(message);
    String part = message.getDisplayMessageBody();
    if (part != null) bodyBuilder.append(part);   // modified: 多包拼接
}
String body = bodyBuilder.toString();
```

### 5.4 【P1-9】“把用户闹钟静音”事故

**代码位置**：`SMSForegroundService`（音量保存/恢复）

**原始问题**
```java
private int current_vol_index;                    // 默认 0
...
} catch (IOException e) { e.printStackTrace(); }  // 异常时 current_vol_index 仍是 0
...
audioManager.setStreamVolume(AudioManager.STREAM_ALARM, current_vol_index, ...); // 写成 0 = 静音
```
另外 `isPlaying()` 为 true 的分支不会再赋值，二次触发时保存值可能已被污染。

**修改思路**：用 `volumeBoosted` 标志位表达“是否真的改过音量”，只有改过才恢复；`savedAlarmVolume = -1` 表示无效值。

**改动代码**
```java
private int savedAlarmVolume = -1;
private boolean volumeBoosted = false;

private void restoreAlarmVolume() {                       // modified
    if (!volumeBoosted || audioManager == null || savedAlarmVolume < 0) return;
    try {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
    } catch (Exception e) { ... }
    finally { volumeBoosted = false; savedAlarmVolume = -1; }
}
```

### 5.5 【P1-8】STOP 时 `IllegalStateException`

**代码位置**：`SMSForegroundService.stopAlert()`

**原始问题**
```java
mediaPlayer.stop();          // 未 prepare 完成 / 已 release -> IllegalStateException
vibratorService.cancel();    // vibratorService 可能为 null
audioManager.setStreamVolume(...); // audioManager 可能为 null
```

**改动代码**
```java
private void stopAlert() {   // modified: 全流程 try/catch
    alertActive = false;
    try {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
    } catch (Exception e) { Log.w(TAG, "pause playback failed", e); }
    try { if (vibratorService != null) vibratorService.cancel(); } catch (Exception e) { ... }
    restoreAlarmVolume();
    stopSelfSafely();
}

@Override
public void onPrepared(MediaPlayer mp) {
    if (!alertActive) return;   // modified: 停止后迟到的回调不能再启动播放
    try { mp.start(); } catch (IllegalStateException e) { ... }
}
```

### 5.6 【P1-10/11】前台服务时序与 PendingIntent 可变性

**代码位置**：`SMSForegroundService.handleStartAlert()`、`NotificationHelper.pendingIntentFlags()`

**原始问题**：`startForeground()` 在铃声+振动之后；`PendingIntent.getService(this, 0, stopIntent, 0)` 无 `FLAG_IMMUTABLE`。

**改动代码**
```java
// 1) 先挂前台通知，满足 Android 8+ “5 秒内 startForeground”
Notification notification = NotificationHelper.build(this, NotificationHelper.CHANNEL_ID_ALERT, ...);
try {
    startForeground(NotificationHelper.NOTIFICATION_ID, notification);
    foregroundStarted = true;
} catch (Exception e) {
    // 无法进入前台：降级为普通通知并立即结束服务，避免被判 ANR
    NotificationHelper.notifyDirectly(this, sender, keyword, personName, messageContent);
    stopSelf();
    return;
}
// 2) 再准备铃声与振动
```

```java
private static int pendingIntentFlags() {   // modified
    return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
}
```

### 5.7 【P1-18】双重响铃

**代码位置**：`NotificationHelper.ensureChannels()`

**原始问题**：渠道 `new NotificationChannel("123", name, IMPORTANCE_HIGH)` 未 `setSound(null, null)`，系统自己会响一次通知音；MediaPlayer 又播一次闹钟铃声。且渠道声音属性**创建后无法由代码修改**。

**修改思路**：换用新渠道 ID（`priority_alert_v2`），并显式关闭系统声音与系统振动；另建一个带声音的降级渠道 `priority_alert_fallback_v2`，仅在服务起不来时使用。

**改动代码**
```java
NotificationChannel alertChannel = new NotificationChannel(
        CHANNEL_ID_ALERT, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
alertChannel.setSound(null, null);      // modified: 关闭系统通知音，避免双重响铃
alertChannel.enableVibration(false);    // modified: 振动由 Vibrator 控制
alertChannel.setBypassDnd(true);
alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
manager.createNotificationChannel(alertChannel);

NotificationChannel fallbackChannel = new NotificationChannel(
        CHANNEL_ID_FALLBACK, ..., NotificationManager.IMPORTANCE_HIGH);
fallbackChannel.setSound(alarmSound, audioAttributes);  // 降级渠道自带声音
```

### 5.8 【P1-12/13/14/15/16/17】Activity 与 Fragment 生命周期

**代码位置**：`MainActivity.onCreate/onDestroy/onActivityResult/setSmsConfig/checkReadContactPermission`、`MyDialogFragment`

**改动代码**
```java
// onCreate：引导分支必须提前返回
if (isOnboardingEnabled()) {
    showOnboarding = true;
    startActivity(new Intent(this, Onboarding.class));
    finish();
    return;                       // modified
}

// onActivityResult：Cursor 必须关闭
try (Cursor cursor = getContentResolver().query(pickedItem, null, null, null, null)) {
    if (cursor == null || !cursor.moveToFirst()) return;
    ...
} catch (Exception e) { Log.e(TAG, "read picked contact failed", e); }

// 权限回调：从界面重新取值，不依赖成员变量
if (requestCode == PERM_SMS_REQUEST) {
    if (granted) {
        String contactRaw = getTextOf(R.id.contact_value);
        List<String> keywords = KeywordMatcher.parseKeywords(getTextOf(R.id.message_value));
        if (keywords.isEmpty()) { showSnackbar(R.string.keyword_empty); return; }
        saveConfig(contactRaw, keywords);
    }
}

// 延迟弹窗：三处状态校验
if (isFinishing() || isDestroyed() || isStateSaved()) return;
try { new MyDialogFragment().show(getSupportFragmentManager(), "AutoSuggestion"); }
catch (Exception e) { Log.e(TAG, "show permission dialog failed", e); }

// onDestroy：移除未执行的延迟任务 + 判空
uiHandler.removeCallbacksAndMessages(null);
if (contactNumberLayout != null) { ... }
```

```java
// MyDialogFragment：硬转 -> instanceof
@Nullable
private MainActivity getMainActivity() {
    Activity activity = getActivity();
    if (activity instanceof MainActivity) return (MainActivity) activity;
    Log.w(TAG, "host activity is not MainActivity, ignore callback");
    return null;
}
```

### 5.9 【P1-7 / F4】误匹配与重复投递

**改动代码**
```java
// 号码：contains -> 规范化后精确/后缀比对
if (!PhoneNumbers.matches(config.getContact(), sender)) return;

// 去重：3 秒内同发送方+同正文+同关键词视为重复投递
private boolean isDuplicate(String sender, String body, String keyword) {
    String fingerprint = (sender == null ? "" : sender) + '#' + body.length() + '#' + body.hashCode() + '#' + keyword;
    long now = System.currentTimeMillis();
    if (fingerprint.equals(lastFingerprint) && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) return true;
    lastFingerprint = fingerprint;
    lastFingerprintAt = now;
    return false;
}
```

### 5.10 【P1-19 / 支持中文关键词】关键词解析归一化

**代码位置**：`KeywordMatcher.java`（新增），`MainActivity.handleTrigger()`、`SMSReceiver`

**原始问题**：`messageStr.split("\\s*,\\s*|\\s+")` —— 中文输入法的全角逗号“，”不被识别，`"报警，求助"` 会被当成**一个**永远匹配不到的关键词。

**改动代码**
```java
public static String normalize(String raw) {
    // 全角空格/不间断空格 -> 空格；U+FF01~U+FF5E -> 半角；trim；Locale.ROOT 小写
    ...
    if (ch == '\u3000' || ch == '\u00A0') builder.append(' ');
    else if (ch >= '\uFF01' && ch <= '\uFF5E') builder.append((char) (ch - 0xFEE0));
    else builder.append(ch);
    ...
    return builder.toString().trim().toLowerCase(Locale.ROOT);
}

public static List<String> parseKeywords(String raw) {   // 支持 ，、；、全角空格、去重、上限 50 条
    ...
    String[] parts = normalized.split("[,;\\s]+");
    ...
}
```

### 5.11 【P2】配置键名与强转集中化

**代码位置**：`AppConfig.java`（新增）

```java
public static AppConfig get(Context context) {           // 用 ApplicationContext，避免静态持有 Activity
    Context appContext = context.getApplicationContext() != null
            ? context.getApplicationContext() : context;
    return new AppConfig(PreferenceManager.getDefaultSharedPreferences(appContext),
            appContext.getSharedPreferences("Settings", Context.MODE_PRIVATE));
}

public Set<String> getKeywords() {                        // 不再强转 HashSet
    Set<String> stored;
    try { stored = configPrefs.getStringSet(KEY_KEYWORDS, null); }
    catch (ClassCastException e) { stored = null; }
    if (stored == null || stored.isEmpty()) return Collections.emptySet();
    return Collections.unmodifiableSet(new LinkedHashSet<>(stored));
}
```

### 5.12 【P2-29 / F2】设置页刷新与命中记录

**代码位置**：`SettingsActivity`、`res/xml/app_preferences.xml`

```java
@Override
public void onResume() {            // modified: 回到设置页刷新摘要
    super.onResume();
    refreshActiveConfiguration();
}

boolean enabled = (Boolean) newValue;   // 前置加 instanceof 判断，避免 ClassCastException
```

新增两个偏好项：`hit_log`（只读展示，`persistent="false"`）、`clear_hit_log`（点击清空）。

### 5.13 【P3】构建链路

| 项 | 原值 | 新值 | 理由 |
|---|---|---|---|
| AGP | 3.2.1 | 8.5.2 | 新版 Android Studio 只支持 7.x/8.x |
| Gradle | 4.6 | 8.7 | AGP 8.5 要求 |
| JDK | 8 | 17 | AGP 8 要求（**需在 AS 里设置 Gradle JDK = 17**） |
| compileSdk | 29 | 34 | 新版 androidx / material 要求 |
| targetSdk | 29 | **29（不变）** | 见 1.4 第 1 条，升级会打断主链路 |
| minSdk | 23 | 23（不变） | 保持兼容面 |
| namespace | 无（manifest `package`） | `com.saiga.find.messagefinder` | AGP 8 强制要求 |
| versionCode/Name | 16 / 2.9.8 | 17 / 3.0.0 | 区分二次开发版本 |

### 5.14 【任务②】界面改版

**代码位置**：`res/layout/activity_main.xml`、`screen_onboarding.xml`、`page_layout.xml`、`dialog_layout.xml`、`res/values/styles.xml`、`colors.xml`，新增 `drawable/bg_card.xml`、`bg_status_active.xml`、`bg_status_idle.xml`，以及 `MainActivity.updateStatusUi()`

**原始问题**：三年前的设计语言——斜向渐变铺满整屏、两个按钮用绝对定位飘在中间、字段标签是带圆角底的胶囊、按钮文案是 `Trigger!`、且没有任何“当前是否生效”的反馈。

**修改思路**：不引入新依赖（不加 ConstraintLayout / Material3），用已有控件做到「浅灰底 + 白卡 + 主次按钮 + 状态条」。所有控件 id 保持原样，Java 侧只新增一个状态刷新方法。

**改动代码（结构摘要）**
```xml
<CoordinatorLayout id="user_message_layout" background="@color/surface_bg">
  <ScrollView fillViewport>
    <LinearLayout id="ui_layout" focusableInTouchMode descendantFocusability="beforeDescendants">
        <TextView  "优先提醒" 26sp bold />            <!-- 标题 -->
        <TextView  @string/app_tagline 14sp 次级色 />  <!-- 副标题：说清用途 -->

        <LinearLayout background="@drawable/bg_card" elevation="1dp" padding="16dp">
            <TextView id="contact_name" style="SectionTitle" />     <!-- 接收号码（可选） -->
            <TextInputLayout id="contact_input_layout" OutlinedBox 圆角12dp counter=15>
                <AutoCompleteTextView id="contact_value" />
            </TextInputLayout>
            <TextView id="message_string" style="SectionTitle" />   <!-- 提醒关键词（必填） -->
            <TextInputLayout OutlinedBox 圆角12dp counter=50>
                <TextInputEditText id="message_value" />
            </TextInputLayout>
        </LinearLayout>

        <TextView id="status_text" background="@drawable/bg_status_idle" />
        <MaterialButton id="trigger" style="PrimaryButtonStyle"   高54dp />  <!-- 启用提醒 -->
        <MaterialButton id="clear"   style="SecondaryButtonStyle" 高48dp />  <!-- 清除配置 -->
        <TextView id="permission_tip" 12sp 次级色 />
        <ImageView id="brand_icon" 64dp />
    </LinearLayout>
  </ScrollView>
</CoordinatorLayout>
```

```java
// MainActivity.updateStatusUi() —— 新增；onResume / 保存后 / 清除后各调用一次
private void updateStatusUi() {
    TextView statusText = findViewById(R.id.status_text);
    if (statusText == null || appConfig == null) return;
    Set<String> keywords = appConfig.getKeywords();
    if (keywords.isEmpty()) {
        statusText.setText(R.string.status_idle);
        statusText.setTextColor(getColor(R.color.status_idle_text));
        statusText.setBackgroundResource(R.drawable.bg_status_idle);
    } else {
        statusText.setText(getString(R.string.status_listening, TextUtils.join(" / ", keywords)));
        statusText.setTextColor(getColor(R.color.status_active_text));
        statusText.setBackgroundResource(R.drawable.bg_status_active);
    }
}
```

```xml
<!-- styles.xml 新增三个样式 -->
<style name="SectionTitle" parent="android:Widget.TextView">
    <item name="android:textSize">13sp</item><item name="android:textColor">@color/text_secondary</item>
    <item name="android:textStyle">bold</item><item name="android:letterSpacing">0.03</item>
</style>
<style name="PrimaryButtonStyle" parent="Widget.MaterialComponents.Button">
    <item name="backgroundTint">@color/secondaryDarkColor</item><item name="cornerRadius">14dp</item>
    <item name="android:insetTop">0dp</item><item name="android:insetBottom">0dp</item>
</style>
<style name="SecondaryButtonStyle" parent="Widget.MaterialComponents.Button.OutlinedButton">
    <item name="cornerRadius">14dp</item><item name="strokeColor">@color/card_stroke</item>
    <item name="strokeWidth">1dp</item>
</style>
```

> **为什么卡片用 shape 而不是 MaterialCardView？** 本工程用的是 **Bridge 主题**
> （`Theme.MaterialComponents.Light.DarkActionBar.Bridge`），Bridge 主题不保证
> `colorSurface` / `colorOutline` 等 Material 属性齐全。MaterialCardView 会隐式读取这些属性，
> 在部分设备/版本上可能取到异常默认值（白卡变透明、描边消失）。shape 背景零依赖、零风险。
> 同理，两个按钮用完全限定的 `MaterialButton`，不受主题 `buttonStyle` 影响，也不影响引导页的透明文字按钮。

---

### 5.15 【任务④】提醒方式与贪睡式重复提醒

这是本轮唯一的“新增功能”主体，涉及 4 层：配置层 → 服务层 → 通知层 → 界面层。逐层说明。

#### 5.15.1 配置层：把“提醒策略”变成有默认值、可容错的契约

【代码位置】`AppConfig.java`（新增 5 个键 / 5 个 getter / 1 个容错读法）、`res/values/arrays.xml` 与 `res/values-zh-rCN/arrays.xml`（新增）、`res/xml/app_preferences.xml`（新增「提醒方式」分组）

【原始问题】
1. 原工程只有“响铃 + 震动一起上、响到用户点停止”这一种行为，没有任何可调项；
2. `ListPreference` 持久化的是 `String`，而业务侧希望拿到 `int`，直接 `(Integer) getString(...)` 或 `getInt(...)` 都会在某一侧抛 `ClassCastException`；
3. 原工程已有过“`getStringSet` 强转 `HashSet`”的同类事故（见 5.11），配置读取必须默认“存储里可能是任何东西”。

【修改思路】
- 键名（`ring_enabled` / `vibrate_enabled` / `ring_duration` / `repeat_interval` / `max_repeat`）同时出现在 `app_preferences.xml` 与 `AppConfig` 常量中，**由字符串资源 `*_key` 单点定义**，不在两处手写；
- 所有区间都在 getter 里夹取（间隔 0–60 分钟、次数 0–30），即使有人在系统里改了 prefs 也不会出现“间隔 100 年”这种把定时器挂死的值；
- `readInt()` 先按 `String` 解析，失败再按 `int` 读，两路都失败才回落默认值。

【改动代码片段】

```java
// AppConfig.java
private static final String KEY_RING_ENABLED = "ring_enabled";
private static final String KEY_VIBRATE_ENABLED = "vibrate_enabled";
private static final String KEY_RING_DURATION = "ring_duration";
private static final String KEY_REPEAT_INTERVAL = "repeat_interval";
private static final String KEY_MAX_REPEAT = "max_repeat";

private static final int DEFAULT_RING_DURATION_SEC = 60;
private static final int DEFAULT_REPEAT_INTERVAL_MIN = 5;
private static final int DEFAULT_MAX_REPEAT = 3;

public boolean isRingEnabled() { return configPrefs.getBoolean(KEY_RING_ENABLED, true); }   // modified: 新增
public boolean isVibrateEnabled() { return configPrefs.getBoolean(KEY_VIBRATE_ENABLED, true); } // modified: 新增

public int getRingDurationSec() {           // modified: 新增；0 = 一直响到手动停止
    return readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC);
}

public int getRepeatIntervalMin() {         // modified: 新增；0 = 不重复
    int value = readInt(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL_MIN);
    return Math.max(0, Math.min(value, 60)); // 夹取，防异常值把定时器挂死
}

public int getMaxRepeatCount() {            // modified: 新增；0 = 不限次数
    int value = readInt(KEY_MAX_REPEAT, DEFAULT_MAX_REPEAT);
    return Math.max(0, Math.min(value, 30));
}

private int readInt(String key, int defaultValue) { // modified: 新增；兼容 String / int 两种历史形态
    String raw = configPrefs.getString(key, null);
    if (raw == null || raw.trim().isEmpty()) {
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
```

```xml
<!-- res/xml/app_preferences.xml：放在最上面的分组，用户一进设置页就能看到 -->
<PreferenceCategory android:title="@string/pref_category_reminder" android:key="@string/pref_category_reminder_key">
    <SwitchPreference android:key="@string/ring_enabled_key" android:title="@string/ring_enabled"
        android:defaultValue="true"
        android:summaryOn="@string/ring_enabled_on" android:summaryOff="@string/ring_enabled_off" />
    <SwitchPreference android:key="@string/vibrate_enabled_key" android:title="@string/vibrate_enabled"
        android:defaultValue="true"
        android:summaryOn="@string/vibrate_enabled_on" android:summaryOff="@string/vibrate_enabled_off" />
    <ListPreference android:key="@string/ring_duration_key" android:title="@string/ring_duration_title"
        android:dialogTitle="@string/ring_duration_title" android:dialogMessage="@string/ring_duration_hint"
        android:entries="@array/ring_duration_entries" android:entryValues="@array/ring_duration_values"
        android:defaultValue="60" android:summary="%s" />
    <ListPreference android:key="@string/repeat_interval_key" ... android:defaultValue="5" ... />
    <ListPreference android:key="@string/max_repeat_key" ... android:defaultValue="3" ... />
</PreferenceCategory>
```

> 一个容易被忽略的细节：`android:summary="%s"` 是 `ListPreference` 的内建能力，
> 它会把 `getSummary()` 里的 `%s` 自动替换成当前选中项的文案。
> 因此**不需要** androidx 那套 `bindPreferenceSummaryToValue()`，也就避免了为一行摘要引入 `androidx.preference` 依赖。

#### 5.15.2 服务层：把“响一次”改成“多轮状态机”

【代码位置】`SMSForegroundService.java`

【原始问题】
1. **没有“自动停”的路径**。原实现只有 `stopAlert()`（用户点停止）一条出口，`setLooping(true)` 会一直响到天荒地老；
2. **`onPrepared` 的判断条件不够**。原实现只判断 `alertActive`。一旦引入“响 N 秒后自动停”，用户在 `prepareAsync()` 期间正好跨过本轮结束点，迟到的 `onPrepared` 会让铃声在**本轮已经结束之后**突然响起，表现为“停了半秒又响”；
3. **音量恢复时机不对**。原实现只在最终停止时恢复，重复提醒场景下手机可能在十几分钟里一直停在最大闹钟音量；
4. **第二条短信会叠两套定时器**。若不做“先收尾再重开”，两轮提醒会各自持有一套 `Handler` 任务，越响越密。

【修改思路】
- 引入 4 个状态位：`alertActive`（整体是否在提醒）、`ringWindowOpen`（**本轮**是否还允许出声）、`roundCount`（已提醒轮次）、`totalRounds`（计划轮次，`Integer.MAX_VALUE` 表示不限）；
- 两个定时任务：`ringTimeoutRunnable`（本轮响铃到点 → 结束本轮）、`repeatRunnable`（间隔到点 → 开下一轮）；
- 轮次计算放在 `readReminderPolicy()` 里**每次提醒都重读一次**，改完设置不用重启应用；
- `onPrepared` 改为 `alertActive && ringWindowOpen` 双条件，从根上杜绝“结束后又响”；
- 每轮结束都调 `restoreAlarmVolume()`；下一轮开始再重新提升（`savedAlarmVolume` 会重新记录当时的真实音量，不会把 0 存进去）；
- 新短信进来时先 `cancelAllCallbacks()` + `stopRingAndVibration()`，再从头计数。

【改动代码片段】

```java
/** 本轮“允许出声”的窗口；单轮响铃到点后关闭，避免迟到的 onPrepared 又响一次。 */
private volatile boolean ringWindowOpen = false;  // modified: 新增

private void readReminderPolicy() {              // modified: 新增
    AppConfig config = AppConfig.get(this);
    ringEnabled = config.isRingEnabled();
    vibrateEnabled = config.isVibrateEnabled();
    ringDurationSec = config.getRingDurationSec();
    repeatIntervalMin = config.getRepeatIntervalMin();
    int maxRepeat = config.getMaxRepeatCount();

    if (ringDurationSec <= 0 || repeatIntervalMin <= 0) {
        totalRounds = 1;                          // 「一直响到我停止」或「不重复」→ 只提醒一轮
    } else if (maxRepeat <= 0) {
        totalRounds = Integer.MAX_VALUE;          // 0 = 不限次数
    } else {
        totalRounds = Math.max(1, maxRepeat + 1); // “最多重复 N 次”= 首次 1 轮 + 重复 N 轮
    }
}

private void startRound(int round) {              // modified: 新增
    if (!alertActive) return;
    roundCount = round;
    updateNotification(getString(R.string.notif_status_round, round));

    if (ringEnabled) {
        ringWindowOpen = true;
        if (!startAlarmPlayback()) {
            fallbackToNotificationSound();        // 铃声起不来，至少让通知响
        }
    }
    if (vibrateEnabled) {
        startVibration();
    }

    cancelRingTimeout();
    if (ringDurationSec > 0) {                    // 0 表示一直响，不挂定时器
        ringTimeoutRunnable = new Runnable() {
            @Override public void run() {
                ringTimeoutRunnable = null;
                endRound();
            }
        };
        alertHandler.postDelayed(ringTimeoutRunnable, ringDurationSec * 1000L);
    }
}

private void endRound() {                         // modified: 新增
    if (!alertActive) return;
    ringWindowOpen = false;                       // 先关窗口，再停声音
    stopRingAndVibration();
    restoreAlarmVolume();                         // 不让手机在等待期一直停在最大音量

    int finishedRound = Math.max(1, roundCount);
    if (finishedRound >= totalRounds) {
        finishAlert(finishedRound);
        return;
    }
    updateNotification(getString(R.string.notif_status_wait, finishedRound, repeatIntervalMin));
    scheduleRepeat(finishedRound + 1);
}

private void finishAlert(int rounds) {            // modified: 新增
    alertActive = false;
    ringWindowOpen = false;
    cancelAllCallbacks();
    stopRingAndVibration();
    restoreAlarmVolume();
    releasePlayer();
    releaseWakeLock();
    updateNotification(rounds > 1
            ? getString(R.string.notif_status_done, rounds)
            : getString(R.string.notif_status_done_once));
    if (foregroundStarted) {
        stopForeground(false);   // false = 保留通知供用户回看，只退出前台状态
        foregroundStarted = false;
    }
    stopSelf();
}

@Override
public void onPrepared(MediaPlayer mp) {
    // 双条件：整体在提醒 + 本轮窗口还开着。少任何一个都会出现“停了又响”
    if (!alertActive || !ringWindowOpen) {        // modified: 追加 ringWindowOpen
        return;
    }
    try { mp.start(); } catch (IllegalStateException e) { Log.e(TAG, "start playback failed", e); }
}

private void cancelAllCallbacks() {               // modified: 新增
    cancelRingTimeout();
    cancelRepeat();
    if (alertHandler != null) {
        alertHandler.removeCallbacksAndMessages(null); // 兜底清理漏网的匿名 Runnable
    }
}
```

持锁时长也随策略变化（原来写死 10 分钟，重复提醒最长可达 40 分钟）：

```java
private long computeWakeLockTimeoutMs() {         // modified: 新增
    int rounds = totalRounds == Integer.MAX_VALUE ? UNLIMITED_ROUNDS_ASSUMPTION : totalRounds;
    long perRoundMs = Math.max(ringDurationSec, 1) * 1000L + repeatIntervalMin * 60_000L;
    long totalMs = perRoundMs * Math.max(rounds, 1) + WAKE_LOCK_MARGIN_MS;
    return Math.max(MIN_WAKE_LOCK_TIMEOUT_MS, Math.min(totalMs, MAX_WAKE_LOCK_TIMEOUT_MS));
}
```

#### 5.15.3 通知层：让“还会不会响”变成可见信息

【代码位置】`NotificationHelper.java`、`res/values/strings.xml`、`res/values-zh-rCN/strings.xml`

【原始问题】重复提醒最怕“用户不知道还会不会响”：原通知从头到尾只有“收到含关键词的短信”一句，第二轮响起来时用户会以为是另一条短信。

【修改思路】给 `build()` 增加一个可选的 `status` 参数（保留原 6 参重载，**不破坏既有调用**），同时写到两处：
- `BigTextStyle.setSummaryText()` → 展开态右上角；
- `setSubText()` → 折叠态子标题（MIUI 折叠通知同样显示）。

【改动代码片段】

```java
public static Notification build(Context context, String channelId, String sender,
                                 String keyword, String personName, String body) {
    return build(context, channelId, sender, keyword, personName, body, null); // 兼容原调用
}

public static Notification build(..., CharSequence status) {   // modified: 新增 status 重载
    CharSequence summaryText = TextUtils.isEmpty(status)
            ? context.getString(R.string.notif_summary) : status;
    NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
            .setBigContentTitle(context.getString(R.string.notif_title))
            .bigText(context.getString(R.string.notif_bigtext, displayBody, displaySender))
            .setSummaryText(summaryText);
    ...
    if (!TextUtils.isEmpty(status)) {
        builder.setSubText(status);   // modified: 折叠态也能看到“第几次提醒”
    }
}
```

状态文案（中英各 4 条，`%1$d` 为轮次 / `%2$d` 为间隔分钟）：

| 键 | 中文 | 出现时机 |
|---|---|---|
| `notif_status_round` | 第 %1$d 次提醒 | 每轮开始时 |
| `notif_status_wait` | 已提醒 %1$d 次 · %2$d 分钟后再次提醒 | 本轮结束、等待下一轮 |
| `notif_status_done` | 提醒已结束（共 %1$d 次） | 达到次数上限 |
| `notif_status_done_once` | 提醒已结束 | 只提醒过一轮 |

#### 5.15.4 界面层：入口与“当前策略”摘要

【代码位置】`MainActivity.java`、`res/layout/activity_main.xml`

【原始问题】提醒策略全部藏在设置页里：
1. 设置页只能从右上角溢出菜单进入，新用户根本找不到；
2. 主界面状态条只说“监听中”，看不出“会不会重复提醒”。

【修改思路】
- 「清除配置」下方加一个 `TextButton`（`@id/reminder_settings`）直达设置页，点击包 `try/catch`；
- 状态条改成两行：第一行是监听状态 + 关键词，第二行是「提醒方式：闹钟铃声 · 震动 · 每 5 分钟提醒一次，最多 4 次」；
- 摘要文案由 `buildReminderModeSummary()` 拼接，**与 `SMSForegroundService.readReminderPolicy()` 用同一套 `AppConfig` getter**，不会出现“界面说会重复、实际不重复”的错位。

【改动代码片段】

```java
// MainActivity.java
View reminderSettings = findViewById(R.id.reminder_settings);   // modified: 新增入口
if (reminderSettings != null) {
    reminderSettings.setOnClickListener(v -> {
        try {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "open settings failed", e);
        }
    });
}

private String buildReminderModeSummary() {                     // modified: 新增
    StringBuilder summary = new StringBuilder();
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
        summary.append(maxRepeat <= 0
                ? getString(R.string.mode_repeat_unlimited, intervalMin)
                : getString(R.string.mode_repeat_limited, intervalMin, maxRepeat + 1));
    }
    return summary.toString();
}
```

```xml
<!-- activity_main.xml：主界面直达设置 -->
<com.google.android.material.button.MaterialButton
    android:id="@+id/reminder_settings"
    style="@style/Widget.MaterialComponents.Button.TextButton"
    android:layout_width="wrap_content" android:layout_height="wrap_content"
    android:layout_gravity="center_horizontal" android:layout_marginTop="4dp"
    android:minHeight="40dp"
    android:text="@string/reminder_settings_button"
    android:textColor="@color/secondaryDarkColor"
    app:icon="@drawable/ic_baseline_settings_24"
    app:iconGravity="textStart"
    app:iconTint="@color/secondaryDarkColor" />
```

---

### 5.16 【任务⑤】对标「短信铃铛」的五项补齐

#### 5.16.1 全部关键词才提醒（规则匹配模式）

【代码位置】`KeywordMatcher.java`、`SMSReceiver.java`、`AppConfig.java`、`res/xml/app_preferences.xml`

【原始问题】原实现只有“任意一个关键词命中就响”。实际用起来会把所有营销短信一起命中：想抓“到账”，结果“到账即送优惠券”也响，用户最后只能把提醒关掉——**误报是这类应用被弃用的第一原因**。

【修改思路】
- `findMatch` 增加 `matchAll` 重载，**保留原两参签名并委托**，老调用点与老单测不受影响；
- “全部命中”模式下遇到第一个不命中立刻返回 null（短路），避免长关键词表做无谓的 `contains`；
- 全空关键词被忽略，避免 `["到账", "  "]` 这种输入把 `matchAll` 变成永远不命中；
- 设置页开关默认**关**，不改变老用户行为。

【改动代码片段】

```java
public static String findMatch(Collection<String> keywords, String body) {
    return findMatch(keywords, body, false); // 保留原签名，默认“任意命中”
}

public static String findMatch(Collection<String> keywords, String body, boolean matchAll) {
    if (keywords == null || keywords.isEmpty()) return null;
    String normalizedBody = normalize(body);
    if (normalizedBody.isEmpty()) return null;
    String firstHit = null;
    int effectiveKeywords = 0;
    for (String keyword : keywords) {
        if (keyword == null) continue;
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isEmpty()) continue;
        effectiveKeywords++;
        boolean hit = normalizedBody.contains(normalizedKeyword);
        if (hit) {
            if (firstHit == null) firstHit = keyword;
            if (!matchAll) return keyword;
        } else if (matchAll) {
            return null; // 全部命中模式：缺一个立刻判定不命中
        }
    }
    if (matchAll && effectiveKeywords > 0 && firstHit != null) return firstHit;
    return null;
}
```

```java
// SMSReceiver.java
String matchedKeyword = KeywordMatcher.findMatch(
        keywords, body, config.isMatchAllEnabled()); // 支持“全部命中”模式
```

#### 5.16.2 夜间静音时段（只震动不响铃）

【代码位置】`QuietHours.java`（新增）、`AppConfig.java`、`SMSForegroundService.java`、`res/values*/arrays.xml`

【原始问题】本应用在半夜收到命中短信时会以最大音量响闹铃。用户唯一的应对办法是“关掉整个提醒”，于是第二天的重要短信也漏了——**这是“提醒强度”与“可用性”最典型的冲突**。

【修改思路】
- 判定逻辑抽成纯 Java 的 `QuietHours`，因为它涉及**跨零点**（22→7 不是 `start<=h<=end`），属不能靠肉眼保证的边界；
- 配置用五档预设（关闭 / 21–07 / 22–07 / 23–08 / 00–06），避免为两个 TimePicker 重写一套自定义 Preference；
- 服务侧只改一处：`ringEnabled = config.isRingEnabled() && !quietHoursActive`，**震动、通知、重复提醒全部不变**；
- 每一轮开始前重读策略，跨进/跨出时段自动生效。

【改动代码片段】

```java
// QuietHours.java
public static boolean isActive(int startHour, int endHour, int hour) {
    if (startHour < 0 || endHour < 0) return false;   // 未启用
    if (startHour == endHour) return false;           // 起止相同视为未启用，避免“全天静音”
    int safeHour = normalizeHour(hour);
    if (startHour < endHour) {
        return safeHour >= startHour && safeHour < endHour;   // 同一天内
    }
    return safeHour >= startHour || safeHour < endHour;        // 跨零点
}
```

```java
// SMSForegroundService.readReminderPolicy()
quietHoursActive = config.isInQuietHours();
ringEnabled = config.isRingEnabled() && !quietHoursActive;
```

#### 5.16.3 规则演练（先验证，再依赖）

【代码位置】`MainActivity.java`、`res/layout/dialog_practice.xml`（新增）、`res/layout/activity_main.xml`

【原始问题】本应用是“静默工作”的：规则写错时没有任何反馈。关键词写宽了天天误报，写严了永远不响，而用户往往要等到**真的错过一条重要短信**才知道规则有问题。

【修改思路】
- 主界面加「规则演练」入口，弹窗里粘一段示例短信，**边输边判**（`TextWatcher` 直接调用匹配），不用先找按钮；
- 复用与 `SMSReceiver` 完全相同的匹配入口与模式，保证“演练通过 = 真机也会响”；
- 结果用颜色区分：命中绿（`status_active_text`）、未命中红（`alert_accent`）；
- 正负按钮都传 `null` 接管点击，避免“点一下弹窗就关、看不到结果”。

【改动代码片段】

```java
private void evaluatePractice(String sample, TextView resultView) { // modified: 新增
    if (resultView == null || appConfig == null) return;
    Set<String> keywords = appConfig.getKeywords();
    if (keywords.isEmpty()) { resultView.setText(R.string.practice_no_keyword); return; }
    if (TextUtils.isEmpty(sample) || TextUtils.isEmpty(sample.trim())) {
        resultView.setText(R.string.practice_no_input); return;
    }
    String hit = KeywordMatcher.findMatch(keywords, sample, appConfig.isMatchAllEnabled());
    if (hit != null) {
        resultView.setText(getString(R.string.practice_hit, hit));
        resultView.setTextColor(getColor(R.color.status_active_text));
    } else {
        resultView.setText(R.string.practice_miss);
        resultView.setTextColor(getColor(R.color.alert_accent));
    }
}
```

#### 5.16.4 稍后提醒（snooze）

【代码位置】`NotificationHelper.java`、`SMSForegroundService.java`

【原始问题】原通知只有「停止」一个动作。用户看到提醒但此刻不方便处理（开会、开车、手上有油），只能二选一：让铃声继续吵到次数用完，或者点停止然后**彻底失去这次提醒**。

【修改思路】
- 通知增加「稍后提醒」动作，与「停止」使用**不同 `requestCode`**，否则 `PendingIntent` 会被复用成同一个；
- 服务新增 `ACTION_SNOOZE_ALERT`：
  - 提醒进行中 → 就地静音、取消定时器、按间隔重排下一轮（**至少再响一次**，即使已达次数上限；这一轮响完仍按原上限收尾，不会无限延长）；
  - 服务已被系统回收 → 用 PendingIntent 带来的 extras 重新进入前台并安排下一轮（不会弹空白提醒）；
- 间隔取「重复间隔」；若用户把重复设成“不重复”（0），用 5 分钟兜底，避免 `postDelayed(0)` 变成立刻再响。

【改动代码片段】

```java
private void handleSnooze(Intent intent) { // modified: 新增
    if (alertActive) { snoozeCurrentRound(); return; }
    // 服务已被回收：用 PendingIntent 带过来的内容重新起一轮
    currentSender = NotificationHelper.getFrom(intent);
    currentKeyword = NotificationHelper.getKeyword(intent);
    currentPerson = NotificationHelper.getPerson(intent);
    currentBody = NotificationHelper.getPayload(intent);
    readReminderPolicy();
    roundCount = 0;
    int delayMinutes = repeatIntervalMin > 0 ? repeatIntervalMin : FALLBACK_SNOOZE_MINUTES;
    // ... startForeground(带“已稍后提醒 · N 分钟后再次响铃”状态) ...
    alertActive = true;
    acquireWakeLock(computeWakeLockTimeoutMs());
    scheduleRepeat(1, delayMinutes);
}
```

#### 5.16.5 锁屏全屏告警页

【代码位置】`AlertActivity.java`（新增）、`res/layout/activity_alert.xml`（新增）、`AndroidManifest.xml`、`NotificationHelper.java`、`res/values/styles.xml`、`res/values/colors.xml`、`res/drawable/bg_alert_label.xml` 与 `bg_alert_card.xml`（新增）

【原始问题】高优先级通知在锁屏上只是一个横幅——横幅会被忽略、会被折叠、会在解锁后消失。对“漏看就出事”的场景（挪车、到账、告警），横幅强度不够。

【修改思路】
- 通知加 `setFullScreenIntent(..., true)`，锁屏/息屏时由系统直接拉起 `AlertActivity`；手机正在使用时只显示浮动通知，不强行打断（这是 Android 的既定策略，也符合直觉）；
- 页面只做“显示 + 两个按钮”，不持有任何定时器；铃声与重复提醒始终由 Service 负责，避免两处状态打架；
- 三个最容易做成“关不掉/卡死”的边界都处理了：
  1. **返回键 = 稍后提醒**（否则用户按返回看不见任何变化，会以为卡死）；
  2. 按钮点击后 `finishAndRemoveTask()` 并兜底 `finish()`；
  3. 服务已停止时点按钮也只会静默失败，不崩。
- 锁屏展示用官方 API（`setShowWhenLocked`/`setTurnScreenOn`，API 27+），低版本退回 Window flag 双保险。

【改动代码片段】

```java
// NotificationHelper.build(...)
builder.setFullScreenIntent(
        buildAlertScreenIntent(context, sender, keyword, personName, body), true);

private static PendingIntent buildAlertScreenIntent(Context context, String sender, String keyword,
                                                    String personName, String body) {
    Intent intent = new Intent(context, AlertActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
    putAlertExtras(intent, sender, keyword, personName, body);
    return PendingIntent.getActivity(context, 2, intent, pendingIntentFlags());
}
```

```java
// AlertActivity.showOverLockScreen()
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    setShowWhenLocked(true);
    setTurnScreenOn(true);
    KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
    if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
        keyguardManager.requestDismissKeyguard(this, null);
    }
} else {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
}
getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

// 返回键 = 稍后提醒
@Override
public void onBackPressed() {
    sendServiceAction(SMSForegroundService.ACTION_SNOOZE_ALERT);
    finishSafely();
}
```

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<activity
    android:name=".AlertActivity"
    android:excludeFromRecents="true"
    android:exported="false"
    android:launchMode="singleTop"
    android:taskAffinity="com.saiga.find.messagefinder.alert"
    android:theme="@style/AlertTheme" />
```

> **一个必须说明的取舍**：全屏页只在**锁屏/息屏**时自动弹出。手机正在被使用时，Android 会把它降级成浮动通知，
> 这是系统的既定行为（防止应用强行劫持前台），也不是我们能绕过的——绕过的做法（后台启动 Activity）
> 会在各个厂商 ROM 上被拦截甚至判为恶意行为，不作考虑。

---

## 6. 完整源码

所有源码已整合在工作区 `PriorityAlerts-v3/`，**改动与新增逐处带 `// modified` 标注**，可直接用 Android Studio 打开。

```
PriorityAlerts-v3/
├── build.gradle                     ← 改：AGP 8.5.2
├── settings.gradle                  ← 改：rootProject.name
├── gradle.properties                ← 改：Xmx 2048m + UTF-8
├── gradle/wrapper/gradle-wrapper.properties  ← 改：Gradle 8.7
└── app/
    ├── build.gradle                 ← 改：namespace / compileSdk 34 / 依赖版本
    └── src/main/
        ├── AndroidManifest.xml      ← 改：去 package、补 exported/WAKE_LOCK/foregroundServiceType
        ├── java/com/saiga/find/messagefinder/
        │   ├── AppConfig.java            ★ 新增：配置唯一入口
        │   ├── KeywordMatcher.java        ★ 新增：关键词归一化/解析/匹配（纯 Java）
        │   ├── PhoneNumbers.java          ★ 新增：号码归一化/比对（纯 Java）
        │   ├── HitLog.java                ★ 新增：命中记录
        │   ├── QuietHours.java            ★ 新增：夜间静音时段判定（纯 Java，跨零点）
        │   ├── NotificationHelper.java    ★ 新增：通知/渠道/PendingIntent
        │   ├── AlertActivity.java         ★ 新增：锁屏全屏告警页
        │   ├── MainActivity.java          ← 重写（保留原结构与交互）
        │   ├── SMSReceiver.java           ← 重写
        │   ├── SMSForegroundService.java  ← 重写
        │   ├── SettingsActivity.java      ← 改
        │   ├── MyDialogFragment.java      ← 改
        │   ├── Onboarding.java            ← 改
        │   └── SliderAdapter.java         ← 改
        ├── res/values/strings.xml         ← 改：+61 条文案（提醒策略/轮次状态/演练/全屏告警），版本号 3.0.0
        ├── res/values-zh-rCN/strings.xml  ← 改：同上（中英各 135 条，键名完全对称）
        ├── res/values/arrays.xml                  ★ 新增：单轮时长/重复间隔/最多重复/夜间时段 四组选项（英文）
        ├── res/values-zh-rCN/arrays.xml           ★ 新增：同上（中文）
        ├── res/layout/activity_main.xml        ← 重做：卡片化 + 状态条 + 主次按钮 + 演练/设置入口
        ├── res/layout/activity_alert.xml       ★ 新增：全屏告警页（深色、大按钮）
        ├── res/layout/dialog_practice.xml      ★ 新增：规则演练弹窗
        ├── res/layout/screen_onboarding.xml    ← 改：去掉 673dp 硬编码，三段式
        ├── res/layout/page_layout.xml          ← 改：内容垂直居中
        ├── res/layout/dialog_layout.xml        ← 改：图标定宽 72dp
        ├── res/xml/app_preferences.xml         ← 改：+规则与提醒方式分组（7 项）/+命中记录/+清空
        ├── res/values/colors.xml               ← 改：+9 个中性色/状态色，+5 个告警页色
        ├── res/values/styles.xml               ← 改：+3 个界面样式，+3 个告警页样式；窗口背景改纯色
        ├── res/drawable/bg_card.xml            ★ 新增：卡片背景
        ├── res/drawable/bg_status_active.xml   ★ 新增：监听中状态条
        ├── res/drawable/bg_status_idle.xml     ★ 新增：未监听状态条
        ├── res/drawable/bg_alert_label.xml     ★ 新增：告警页“优先提醒”标签
        └── res/drawable/bg_alert_card.xml      ★ 新增：告警页正文卡片
    └── src/test/java/.../
        ├── KeywordMatcherTest.java        ★ 新增：16 个用例（含“全部命中”模式）
        ├── PhoneNumbersTest.java          ★ 新增：13 个用例
        └── QuietHoursTest.java            ★ 新增：10 个用例（跨零点/起止点/未启用）
```

**已删除**：`app/src/main/java/.../TestService.java`（342 行 100% 被注释的死文件，无任何可执行逻辑）。

**未改动**：所有图片/图标/铃声资源（`*.webp` / `raw/priority_ringtone.mp3`）、`menu/settings_menu.xml`、`layout/contacts_view.xml`、`layout/settings_activity.xml`、`LICENSE`。

> 说明：`res/values/colors.xml`、`styles.xml` 只做**追加**（原有色值与样式定义一个都没删），
> 因此任何我没改到的地方都不会因为配色缺失而崩。

---

## 7. 风险提示

| 级别 | 风险 | 说明与应对 |
|---|---|---|
| **高** | **未实际编译验证** | 本机无 JDK/Android SDK。已做括号配平、资源引用全量校验与人工推演。首次构建若有报错，属语法/依赖细节，建议先把 `gradlew assembleDebug` 的输出贴给我，我按行修。 |
| **高** | **Gradle JDK 必须设为 17** | AGP 8.5 硬性要求。Android Studio：`Settings → Build → Build Tools → Gradle → Gradle JDK = 17`。用 JDK 8/11 会直接报 “Unsupported class file major version”。 |
| 中 | **targetSdk 仍是 29** | 这是**有意的取舍**：升级到 31+ 会让“短信广播 → 启动前台服务”在后台被系统拒绝，核心功能失效。如果你以后想上架应用商店（必须 34+），需要额外改造（改通知渠道直发 + 全屏 Intent，或申请 `specialUse` 类型并说明用途），这是**架构级改动**，届时请先单独确认方案。 |
| 中 | **通知渠道 ID 变更** | 由 `"123"` 改为 `priority_alert_v2`。旧渠道会残留在系统设置里（不再使用）。如果你之前在旧渠道上自定义过铃声/振动，需要到新渠道重新设置一次。**已安装用户无需重装即可生效**（新渠道会自动创建）。 |
| 中 | **号码匹配语义变化** | 由 `contains`（任意位置包含）改为“精确 / 长度≥7 的后缀”。极端场景：若你曾配置 `0123` 这类短号想靠“包含”命中，现在不再命中——这是**有意的行为修正**（原来会误报）。 |
| 中 | **“要不要从通讯录选人”只问一次** | 原来每次聚焦都弹，改为每个界面实例问一次。若你选了“否”，本次使用中不会再问，可手动输入。 |
| 中 | **重复提醒期间持有局部唤醒锁** | 为了让“5 分钟后准时再响”在息屏/深度休眠下也成立，服务会持续持有 `PARTIAL_WAKE_LOCK`（**最长 60 分钟封顶**，多轮结束或点停止立刻释放）。它不点屏幕、只阻止 CPU 休眠，耗电低于亮屏待机。若你选「最多重复 = 不限」，超过 60 分钟后唤醒锁到期，系统可能进入深度休眠导致提醒被推迟（不会丢，会延后到下次唤醒）。 |
| 中 | **MIUI / HyperOS 的后台管控才是一切的前提** | 红米 K60 Pro 上，如果「自启动」关闭或「省电策略」是“智能限制”，MIUI 会在息屏后数分钟杀掉进程：**第一轮可能响，第二轮就没了**。主界面已写入四步清单，请务必逐项设置。这是 OEM 策略，代码无法绕过。 |
| 中 | **“不限次数”模式会持续占用最大闹钟音量** | 该模式下每轮结束仍会还原音量，但整体提醒可能持续较久。若非必要，建议用默认的「最多 4 轮」。 |
| 中 | **全屏告警只在锁屏/息屏时自动弹出** | 手机正在被使用时，Android 只把它显示为浮动通知，不会强行打断当前页面。这是系统策略（防应用劫持前台），不是实现缺陷；绕过的做法会在各家 ROM 上被拦截甚至判为恶意行为。 |
| 中 | **全屏告警页是新增界面** | 首次使用请按 8.3.3 的 R16–R18 走一遍：锁屏发短信 → 是否弹出、点「停止」/「稍后提醒」/返回键是否都能退出。若你的机型上出现不弹或弹了关不掉，把机型与复现步骤发我，可改为“仅通知 + 振动加强”的降级方案。 |
| 低 | 夜间静音只在**提醒启动时**判定 | 21:59 开始的一轮提醒跨到 22:00 不会中途静音（要等下一轮才开始静音）。这是有意的：中途突然静音会让用户以为铃声坏了。 |
| 低 | 提醒轮次状态在部分 ROM 上可能被折叠显示 | 状态同时写入 `summaryText`（展开态）与 `subText`（折叠态），主流 ROM 都能展示；极少数把子标题也裁剪的 ROM 上，只能展开通知查看。不影响响铃本身。 |
| 低 | 免打扰绕过不保证 | `setBypassDnd(true)` 需要用户在系统里授予“免打扰访问权限”，未授予时系统会忽略。渠道重要性为 HIGH 时在勿扰下仍可能被拦截，取决于系统策略。 |
| 低 | `android.preference` 仍在使用 | 已废弃但功能正常；未来若升到 targetSdk 34+ 需迁移到 `androidx.preference`（届时一并处理）。 |
| 低 | 长短信拼接按广播内全部片段拼接 | 极少数 ROM 会把两条不同短信合并到一个广播，理论上可能串正文。概率极低，若遇到再按 PDU 分组。 |
| 中 | **界面属视觉层改动** | 所有控件 id 未变，业务逻辑不受影响。若你的机型出现文字截断/间距异常，把截图发我，dp 值都在布局文件里，微调很快。 |
| 低 | 文案已改 | “Trigger!/启动！”→“启用提醒/清除配置”等；英文文案同步更新。想改回原文案，只动 `strings.xml` 即可。 |
| 低 | 兼容性 | 已安装的旧版本可直接覆盖安装（applicationId/签名一致时），配置键名未变，**旧配置自动继承**。 |

---

## 8. 验证方案

### 8.1 第一层：纯 JVM 单元测试（不需要设备，30 秒）

```bash
cd PriorityAlerts-v3
./gradlew :app:testDebugUnitTest        # Windows: gradlew.bat :app:testDebugUnitTest
```
或在 Android Studio 里右键 `KeywordMatcherTest` / `PhoneNumbersTest` → Run。

**预期**：39 个用例全绿（`KeywordMatcherTest` 16 + `PhoneNumbersTest` 13 + `QuietHoursTest` 10）。重点关注这几条（对应本次修复的核心缺陷）：
- `canonical_keepsElevenDigitMobileIntact` → `13800138000` 不被截断
- `canonical_stripsCountryCode86` → `+8613800138000` / `008613800138000` 都归一成 11 位
- `matches_doesNotMatchOnPartialSubstring` → 配置 `1234` 不再命中 `1000012349999`
- `parseKeywords_supportsFullWidthComma` → `"报警，求助"` 解析出 2 个关键词
- `findMatch_matchesChineseKeyword` → 中文关键词能命中
- `findMatch_matchAllRequiresEveryKeyword` → 「全部命中」模式缺一个关键词就不响
- `isActive_crossMidnightEarlyMorning` → 22:00–07:00 在凌晨 0 点应判为静音（跨零点）

### 8.2 第二层：构建与安装

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
**预期**：无编译错误；安装成功；`versionName` 显示 3.0.0。

### 8.3 第三层：功能回归（逐条对照）

| # | 步骤 | 预期结果 | 对应修复 |
|---|---|---|---|
| 1 | 冷启动应用（首次安装） | 进入 3 屏引导；首屏**没有**“上一步”按钮；第 3 屏按钮变“完成”；点完成进入主界面 | P2-31 |
| 2 | 主界面输入号码 `13800138000` | **不再飘红**，按钮可点；计数器显示 `11/15` | P1-1/2 |
| 3 | 输入 `+86 138 0013 8000` | 不飘红（可保存） | P1-1 |
| 4 | 关键词输入 `报警，求助`（**全角逗号**） | 保存后进“设置”，当前配置的关键词显示为 `报警 求助`（两个） | P1-19 |
| 5 | 号码留空，关键词填 `报警`，点启动 | 提示“已保存配置”；按钮变“已启动！” | 原功能 |
| 6 | 用另一台手机给本机发一条含“报警”的短信 | 立即最大音量响铃 + 循环振动 + 高优先级通知（**只响一遍，不是两遍**） | P1-18 |
| 7 | 观察通知 | 通知栏有“停止”动作；**点击通知正文能打开应用** | F3 |
| 8 | 点“停止” | 铃声与振动立即停止；通知消失；服务退出 | P1-8 |
| 9 | 停止后检查系统闹钟音量 | 恢复成响铃前的值（**不能变成 0**） | P1-9 |
| 10 | 再发一条含“报警”的短信，铃声刚响起时**立刻**点停止 | 不崩溃 | P1-8 |
| 11 | 发一条**不含**关键词的短信 | 完全不响、无通知 | 原语义 |
| 12 | 设置页查看“最近命中记录” | 能看到刚才几次命中的时间/号码/关键词/正文摘要 | F2 |
| 13 | 点“清空命中记录” | Toast 提示已清空，摘要变为“暂无命中记录” | F2 |
| 14 | 号码配成 `13900139000`，用 `13800138000` 发含关键词短信 | 不响（号码不匹配） | P1-7 |
| 15 | 主界面点“清除” | 输入框清空，配置清除；再发短信不再响 | 原功能 |
| 16 | 息屏后发短信（锁屏状态） | 能响铃（WAKE_LOCK 生效） | P3-38 |
| 17 | 旋转屏幕进入设置页再返回 | 摘要显示的是最新配置（不是旧值） | P2-29 |
| 18 | 权限被拒（首次拒绝 RECEIVE_SMS） | 显示权限提示 Snackbar，点“设置权限”跳系统设置页 | 原功能 |

### 8.3.1 界面检查（对应第 ② 点）

| # | 检查项 | 预期 |
|---|---|---|
| U1 | 主界面整体 | 浅灰底 + 白色圆角卡片；标题「优先提醒」+ 一行副标题说明用途；无控件错位 |
| U2 | 未配置时的状态条 | 灰色两行：「尚未监听 · 填写至少一个关键词后点击「启用提醒」」+「提醒方式：闹钟铃声 · 震动 · 每 5 分钟提醒一次，最多 4 次」 |
| U3 | 保存后的状态条 | 变绿色：「监听中 · 关键词：报警」+ 同样的提醒方式行 |
| U4 | 号码输入非法（如 `138abc`） | 输入框飘红 + 「启用提醒」按钮变半透明且不可点 |
| U5 | 两个按钮 | 「启用提醒」实心蓝、圆角 14dp、高 54dp；「清除配置」描边无填充、高 48dp |
| U6 | 引导页 | 三屏内容垂直居中；底部指示点与按钮栏不重叠；横屏不溢出 |
| U7 | 授权弹窗 | 图标 72dp 居左、文字居右自适应，不被挤成两半 |
| U8 | 设置页 | 标题「应用设置」；浅灰底；顶部有「提醒方式」分组（2 开关 + 3 列表项）；可见「最近命中记录」「清空命中记录」两项 |
| U9 | 「提醒方式与重复」入口 | 主界面「清除配置」下方有一个带齿轮图标的文字按钮，点击能进设置页 |

### 8.3.2 提醒方式与重复提醒检查（对应第 ④ 点）

> **实测建议**：先把设置改成「单轮 30 秒 / 每 1 分钟 / 最多 1 次」，
> 这样一个完整周期只要约 2 分钟，不用干等 5 分钟一轮。全部通过后再改回默认（60 秒 / 5 分钟 / 3 次）。

| # | 操作 | 预期结果 | 对应实现 |
|---|---|---|---|
| R1 | 设置页关掉「响铃」，其余默认；发命中短信 | **不出声**，只有震动 + 通知 | 5.15.2 `ringEnabled` |
| R2 | 关掉「震动」，开「响铃」；发命中短信 | 只有铃声，**不震动** | 5.15.2 `vibrateEnabled` |
| R3 | 两个都关；发命中短信 | 只有高优先级通知（不响不震），通知正常出现 | 降级语义 |
| R4 | 单轮时长=30 秒；发命中短信 | 响 30 秒后**自动停**（不点任何按钮），通知变成「已提醒 1 次 · 5 分钟后再次提醒」 | 5.15.2 `endRound` |
| R5 | 接 R4，等满间隔（建议先设 1 分钟） | 自动再响一轮，通知变成「第 2 次提醒」 | 5.15.2 `scheduleRepeat` |
| R6 | 接 R5，达到「最多 1 次」上限 | 通知变成「提醒已结束（共 2 次）」；**服务退出前台**，通知仍保留且可划掉 | 5.15.2 `finishAlert` |
| R7 | 在等待期间（不响时）点通知上的「停止」 | 立即彻底停止，**后面的轮次不会再响** | 5.15.2 `cancelAllCallbacks` |
| R8 | 在等待期间**划掉**通知 | 同 R7（`setDeleteIntent` 生效） | F3 |
| R9 | 「最多重复」选「不限」；发命中短信后放着不管 | 每轮间隔到点就再响，**直到你点停止**；期间唤醒锁最长 60 分钟 | 5.15.2 `totalRounds = MAX_VALUE` |
| R10 | 单轮时长选「直到我手动停止」 | 一直响不停；设置页已提示此时**重复提醒不生效** | 5.15.2 `readReminderPolicy` 首分支 |
| R11 | 重复提醒进行中，再发一条命中短信 | 从「第 1 次提醒」重新计数，**不会两套定时器叠加**（不会越响越密） | 5.15.2 `handleStartAlert` 开头收尾 |
| R12 | 每轮结束后立刻看系统闹钟音量 | 已还原（不是一直停在最大），下一轮开始时再提升 | 5.15.2 `restoreAlarmVolume` 时机 |
| R13 | 息屏放置 5 分钟等第二轮 | 能准时响（唤醒锁生效） | 5.15.1 / 5.15.2 持锁 |
| R14 | 改完设置**不重启应用**，直接发短信 | 新设置立即生效 | 5.15.2 每次提醒都重读 |
| R15 | 主界面状态条与设置页对照 | 状态条上的「每 N 分钟 / 最多 N 次」与设置页一致 | 5.15.4 共用 getter |

### 8.3.3 对标「短信铃铛」五项新增的验收（对应第 ⑤ 点）

| # | 操作 | 预期结果 | 对应实现 |
|---|---|---|---|
| R16 | 锁屏（按电源键黑屏）后用另一台手机发命中短信 | 屏幕自动点亮并**弹出全屏告警页**（深色、红色「停止」按钮） | 5.16.5 |
| R17 | 在全屏页点「停止」 | 立即静音、页面关闭、通知消失 | 5.16.5 |
| R18 | 在全屏页点「稍后提醒」或**按返回键** | 立即静音、页面关闭；按设定间隔后再响一轮 | 5.16.4 / 5.16.5 |
| R19 | 亮屏解锁状态下发命中短信 | **不弹全屏页**，只有浮动通知（系统策略，属正常） | 5.16.5 |
| R20 | 设置页打开「必须包含全部关键词」，关键词填 `到账 银行` | 「规则演练」里粘“银行短信到账” → 命中；粘“到账即送券” → 未命中 | 5.16.1 / 5.16.3 |
| R21 | 关闭「必须包含全部关键词」 | 同样两段示例短信都命中（回到原语义） | 5.16.1 |
| R22 | 「规则演练」粘一条真实收到的短信 | 命中时显示绿色「命中 · 关键词「xxx」」；不命中显示红色提示 | 5.16.3 |
| R23 | 关键词为空时点「规则演练」 | 弹窗提示“请先填写至少一个关键词”，不崩溃 | 5.16.3 |
| R24 | 设置「夜间静音」= 22:00–07:00，把系统时间调到 23:00（或用 `adb shell date`），发命中短信 | **不响铃**，只有震动 + 通知（`adb logcat` 可见 `policy: ring=false(quiet hours)`） | 5.16.2 |
| R25 | 把系统时间调回 12:00，再发命中短信 | 正常响铃 | 5.16.2 |
| R26 | 「夜间静音」选「关闭」 | 任何时刻都响铃 | 5.16.2 |
| R27 | 主界面状态条 | 第二行应同时体现“任意/全部关键词”“响铃/静音”“重复策略”，以及（若开启）夜间时段 | 5.16.1 / 5.16.2 |

### 8.4 第四层：日志与去重验证

```bash
# 只看本应用的关键日志（TAG 已统一）
adb logcat -s SMSReceiver SmsService NotificationHelper HitLog Onboarding

# 关键日志应出现：
#   keyword matched: 报警, from 13800138000
#   start alert, keyword=报警
#   alarm playback started
#   stop alert
```

**去重验证**：设法让同一条短信在 3 秒内被投递两次（部分 ROM 天然会重复投递）。预期日志出现 `duplicate sms within window, skip`，且**只响一次**。

**静音渠道验证**：
```bash
adb shell dumpsys notification --noredact | grep -A3 priority_alert_v2
# 应能看到 sound=null、importance=4(HIGH)
```

### 8.5 第五层：边界与异常

| 场景 | 操作 | 预期 |
|---|---|---|
| 未配置关键词时收到短信 | 清除配置后发短信 | 无任何反应，日志 `no keyword configured, skip` |
| 正文为空的短信 | 用模拟器发空短信 | 不崩溃，日志 `empty sms body, skip` |
| 超长关键词 | 粘贴 60 字 | 自动截断到 30 字保存，不崩溃 |
| 关键词数量超过 50 | 粘贴 60 个词 | 只保留前 50 个 |
| 引导页跳过后杀进程重开 | 完成引导 → 划掉 → 重开 | 直接进主界面，不再出现引导 |
| 覆盖安装旧版本 | 旧版已配置过规则 → 装新版 | 旧配置自动继承，无需重配 |

### 8.6 若构建报错

把以下命令的完整输出发我，我按行定位修正：
```bash
gradlew.bat :app:assembleDebug --stacktrace
```

### 8.7 本轮已做的静态自检（无需设备，本机已全部跑过并通过）

本机没有 JDK/Android SDK，无法编译，因此用脚本把“编译期最容易踩的坑”先扫了一遍：

| 检查项 | 方法 | 结果 |
|---|---|---|
| XML 良构性 | 用 XML 解析器逐个解析 `res/**/*.xml` + `AndroidManifest.xml` | **0 处错误**（过程中抓出并修掉 1 处裸 `&`，见 3.4-41） |
| Java 括号/字符串配平 | 剔除注释与字符串字面量后统计 `{}`、`()` | 14 个主源码 + 3 个测试文件**全部配平** |
| 资源引用完整性 | 扫描 `@string` / `@color` / `@drawable` / `@style` / `@layout` / `@xml` / `@menu` / `@array` / `@raw` | **无缺失**（仅 `PreferenceThemeOverlay.v14.Material` 来自 appcompat 库，属正常） |
| Java → 资源引用 | 扫描 `R.string` / `R.color` / `R.drawable` / `R.id` / `R.layout` | **无缺失**（含本轮新增的 `R.id.reminder_settings`、5 个提醒策略 getter 用到的字符串） |
| 中英文本地化对称 | 比对 `values` 与 `values-zh-rCN` 的 `name` 集合 | 各 **135 条，双向差集为空** |
| 重复资源名 | 同一 `values` 文件内 `name` 去重检查 | **0 处重复**（重复会导致 `aapt` 直接失败） |

再次说明：这些只能证明“没有明显的语法/引用错误”，**不能替代真实编译**。真机行为仍需按 8.3 / 8.3.2 走一遍。

---

## 附录：本次为何不做架构级重构

判断依据：现有 `BroadcastReceiver + Service + SharedPreferences` 的三件套，对“单一规则 + 一次提醒”这个体量**完全够用**，真正的风险不在架构，而在**边界条件与生命周期**（也正是本次修的 19 个 P1 问题）。引入 Room / MVVM / DI / 协程会带来：
- APK 体积增加（Room + KSP 约 1.5–2MB）；
- 数据库迁移成本（这类应用的用户只有你一个人，收益为零）；
- 更多初始化与线程切换点 = 更多崩溃面。

如果你的后续需求扩展到“多规则分组、多通道（邮件/webhook/IM）、规则优先级”，那就是另一套架构了（可参考 SmsForwarder 的 Rule/Sender 双表设计），**到那时再单独出重构方案**，不要在本次改动里混合进行。
