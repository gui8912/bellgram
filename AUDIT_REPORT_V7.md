# 铃达 / Bellgram —— 项目源代码审计、Bug 修复与迭代优化报告（V7）

| 项 | 内容 |
|---|---|
| 审计基线 | `v4.1.2`（commit `4da1567`，已含首次启动闪退修复） |
| 本轮交付 | `v4.2.0` / `versionCode 23` |
| 审查方式 | 四条链路并行逐行静态审查 + 主代理逐条复核（不默认采信任何一条结论）+ 三项自研静态检查脚本 + JVM 单测 + CI 编译 |
| 总原则 | 稳定性优先，兼顾可读性；**不做架构级重构**；零新增依赖；原有对外接口保持兼容 |
| 报告日期 | 2026-11-06 |

> **与前面几轮报告的关系**：V5 是 12 项缺陷的独立复审，V6 是引导页闪退的 P0 定位。
> 本轮把审查范围从「Java 内部逻辑」扩展到**跨文件契约、持久化状态机、系统组件生命周期、
> Unicode/编码边界**，并明确列出「已确认但不修」的项及理由（见 §3.3、§7）。
> 审计过程中还发现并回滚了本轮自己引入的一处回归（见 §5 的 F09 补注）。

---

## 1. 同类方案调研小结

**说明**：按任务要求「假想检索」同类项目与行业实践。以下是基于公开架构资料与本项目已引用来源的
经验性归纳，**不是实时联网检索结果**，因此不给出具体 star 数、版本号等无法核实的数字。
结论只用于指导本轮修复方向，不虚构任何第三方代码来源。

### 1.1 业务场景与技术栈定位

| 维度 | 本项目 | 同类方案的典型定位 |
|---|---|---|
| 业务 | 短信关键词 → 闹铃级强提醒，未处理则 5 分钟重复 | 短信转发器、通知增强器、闹钟/提醒类 App |
| 技术栈 | 原生 Java + `SMS_RECEIVED` 静态广播 + `NotificationChannel` + 全屏 Intent + FGS + `setAlarmClock` | 同类普遍已从「常驻服务 + Handler」迁移到「系统闹钟 + 一次性任务」 |
| 核心难点 | **后台限制下的可靠唤醒**（targetSdk 36）、**不能漏报/不能停不下来**、**不能扰民** | 完全一致：权限、Doze、厂商省电、渠道不可变、FGS 类型 |

### 1.2 可借鉴的成熟做法

| 来源场景 | 做法 | 本项目的对应实现 / 本轮改进 |
|---|---|---|
| `pppscn/SmsForwarder` 类短信转发器 | 规则引擎分层（归一化 / 解析 / 匹配抽成纯 Java 类并单测）；保留转发/命中日志便于排障 | `KeywordMatcher` / `PhoneNumbers` / `RoundPolicy` + 5 个单测类；`HitLog` 环形缓冲。**本轮修了日志落盘、并发、字段转义三类缺陷（F25）** |
| AOSP 系统闹钟 `DeskClock` | `setAlarmClock()` 作为「用户可见闹钟」免精确闹钟权限；`BOOT_COMPLETED` 后重新注册；全屏告警页 + 贪睡；**音量临时提升必须能恢复** | `AlertScheduler` 已采用 `setAlarmClock`。**本轮补齐 `BootReceiver`（F16）与 `AlarmVolumeGuard`（F13）** —— 这两处正是原架构漏掉的成熟做法 |
| 「短信铃铛」类通知增强 App | 锁屏全屏告警页、必须点「停止/稍后」才能退出；全部关键词匹配；夜间静音 | `AlertActivity` / `match_all` / `QuietHours` 已在 v3-v4 落地；**本轮修了「已结束通知还能复活会话」（F08）** |
| Android 官方《Notifications / FGS types / Schedule alarms / Edge-to-edge》 | 渠道创建后不可变；`IMMUTABLE` PendingIntent；FGS 类型权限；`WindowInsetsCompat`；通知 trampoline 限制 | 前几轮已合规。**本轮补了渠道创建的失败隔离与存在性校验（F01/F02）、API 23-25 的声音兜底（F03）、引导页 insets（F26）** |
| 通用健壮性实践 | 配置读取对类型漂移做兜底；共享状态读写加锁；`commit()` vs `apply()` 按调用场景选择；Unicode 按 code point 截断 | **本轮 F06/F07/F17/F18/F24/F25 全部源于这一类实践缺位** |

### 1.3 需要规避的坑（本轮逐条对照）

1. **通知渠道创建失败会被系统静默吞掉**：`notify()` 到未创建的渠道不抛异常，只写一条 `No Channel found`，
   表现为「短信匹配上了却完全没反应」。→ 渠道创建必须分步 try，发送前必须校验。
2. **`setAlarmClock` 重启即失效**：系统重启清空全部 `AlarmManager` 闹钟，持久化「会话状态」不等于
   持久化「待触发闹钟」。→ 必须有开机恢复入口。
3. **降级路径不要带全屏 Intent**：播放失败 → 补一声通知 → 若该通知又拉起告警页 → 再次尝试播放失败，
   形成自触发循环。→ 降级通知应是「纯补一声」。
4. **修改系统全局设置必须可回滚**：`setStreamVolume(STREAM_ALARM, max)` 是持久写入，
   进程被强杀时 `onDestroy()` 不执行。→ 原音量必须落盘。
5. **字符白名单 / 哈希指纹 / 截断** 三类「看起来在防御，实际在改写或误判」的伪修复：
   校验器放行但归一化丢弃字符、用 `hashCode()` 判等、按 UTF-16 `char` 截断。
6. **划掉通知 ≠ 停止提醒**：用户随手划掉横幅属于「不处理」，而需求是「不处理就 5 分钟后再响」。
7. **组件状态不能只信 `PackageManager`**：`setComponentEnabledSetting` 在受管设备/部分 ROM 会失败。
8. **edge-to-edge 要覆盖所有页面**，尤其是首次安装必经的引导页（targetSdk 35+ 已无退出开关）。

### 1.4 本轮据调研确定的改进方向

只做三类事：**补齐成熟方案里本项目漏掉的可靠性环节**（重启恢复、音量回滚、渠道校验）、
**修掉前几轮修复自身引入的不一致**（15 位校验 vs 11 位截断、`*#` 白名单残留、配置复核误拦 snooze）、
**把跨文件契约类缺陷纳入静态检查**。没有引入任何新依赖、新页面或花哨功能。

---

## 2. 项目理解小结

### 2.1 定位

个人自用、不对外商用的 Android 应用。用户（红米 K60 Pro）配置「关注的发件号码（可选）+ 关键词（必填）」，
当收到的短信命中规则时，用**闹铃级**而不是普通短信提示音的强度提醒：响铃、震动、锁屏全屏告警页；
只要用户没有明确点「停止」，就按 5 分钟间隔重复提醒（可设上限、可设「一直到我处理」）。
名称「铃达 / Bellgram」，包名 `com.saiga.find.messagefinder` 保持不变（兼容升级数据）。

### 2.2 运行原理（v4 起的通知驱动架构）

```
SMS_RECEIVED 广播
  └─ SMSReceiver：号码过滤 → 长短信拼接 → 关键词匹配 → 去重 → 命中日志
       └─ AlertDispatcher.onSmsMatched：取消旧闹钟 → 开新会话 → 发「带闹铃音 + 全屏 Intent」通知
            ├─ 锁屏/息屏：系统拉起 AlertActivity（用户可见）
            │    └─ startForegroundService（唯一合法启动点）→ SMSForegroundService 播放本轮
            │         └─ 响满时长 → setAlarmClock 挂下一轮 → stopSelf
            ├─ 亮屏：系统只显示悬浮通知（HUN），用户点通知才走到 AlertActivity
            └─ 兜底：无论服务是否启动，通知发出时就已挂好下一轮闹钟
                 └─ RepeatAlarmReceiver → onRepeatAlarm → 再发一轮通知
终止：用户点「停止」/「稍后提醒」/ 返回键，或轮次用尽
```

**为什么不从短信广播直接起服务**：targetSdk 31+ 的后台启动 FGS 限制，豁免清单不含 `SMS_RECEIVED`。
**为什么用 `setAlarmClock`**：属用户可见闹钟，免 `SCHEDULE_EXACT_ALARM` 权限，Doze 下可靠。
**为什么换 `mediaPlayback`**：`shortService` 在 Android 14+ 约 3 分钟超时，撑不住 5 分钟间隔。

### 2.3 输入 / 输出与约束

| 项 | 内容 |
|---|---|
| 输入 | 短信（`SMS_RECEIVED`）、用户配置（号码/关键词/提醒策略）、通知按钮与告警页点击、系统闹钟广播、开机广播 |
| 输出 | 高优先级通知（两个渠道）、全屏告警页、闹铃音频 + 循环震动、命中日志、状态栏闹钟图标 |
| 硬约束 | 不删可用逻辑；原有调用方式兼容；不随意改配置默认值；新增依赖先评估；破坏性改动必须标注 |
| 环境 | `minSdk 23 / targetSdk 36 / compileSdk 36 / AGP 8.11.1 / Gradle 8.13 / JDK 17`；普通笔记本 clone 后 `./gradlew assembleDebug` 即可 |
| 依赖 | 全部为 Google 官方 AndroidX + Material + `circleimageview`；**本轮零新增依赖** |

### 2.4 理解一致性确认

本轮所有改动都不改变「规则 → 强提醒 → 未处理就重复」这条主链路，不新增业务概念，
不改动存储文件名与既有键名（新增的键只有 `onboarding_done_v4`、`snoozed_round`、
`alarm_volume_guard_v4` 三个内部标记）。旧用户覆盖升级后号码、关键词、提醒策略、命中日志全部保留。

---

## 3. 审计问题汇总

### 3.1 已确认并修复（34 项，按优先级）

| 编号 | 级别 | 问题 | 后果 |
|---|---|---|---|
| F01 | P0 | `ensureChannels()` 两个渠道 + 删旧渠道共用一个 `try`，异常被整体吞掉 | 定制 ROM 上 `setBypassDnd` 抛异常 → 有声渠道可能从未创建 → 通知被系统静默丢弃，**提醒 100% 失效且无报错** |
| F02 | P0 | `post()` 发通知前不校验渠道是否存在 | 同上，失败时 `postRound` 仍打「已发送」日志，排障无线索 |
| F03 | P1 | minSdk 23 覆盖 API 23-25，但这三代没有渠道，builder 未设 `setSound` | 老设备上「第一声」完全静音，注释里假定的兜底是空的 |
| F04 | P1 | 播放/前台启动失败的降级通知 `notifyDirectly` 带全屏 Intent | 失败 → 发通知 → 拉起告警页 → 再失败，**自触发无限循环**，通知刷屏、闹铃反复响一声 |
| F05 | P1 | 所有通知（含「已结束」）都带 STOP/SNOOZE 按钮；`setDeleteIntent` = 停止整个会话 | 死会话可被复活；随手划掉横幅会取消后续全部轮次，与核心需求冲突 |
| F06 | P1 | 去重是「读-比较-两次写」，仅用两个 `volatile`；指纹用 `body.hashCode()` | 并发投递时同一短信响两次；哈希碰撞时 3 秒内两条不同短信被误吞（漏报） |
| F07 | P1 | 会话状态迁移 `cancel → 改状态 → post → schedule` 全链无锁 | 短信与闹钟广播交错时轮次错乱、`FLAG_UPDATE_CURRENT` 覆盖同一 PendingIntent → 丢轮 |
| F08 | P1 | `snoozeInternal` 无条件 `setActive(true)` | 从「已结束」通知点稍后提醒会复活已停止的会话 |
| F09 | P1 | 闹钟到点不复核实时配置 | 用户关掉「重复」后，已挂的闹钟仍会再响一轮 |
| F10 | P2 | `requestServiceStop` 对已销毁服务仍 `startService` | 一次无意义的 `onCreate/onDestroy` 冷启动；后台还会被系统拒绝 |
| F11 | P1 | `onNewIntent` 无条件重启服务；`handleStartAlert` 无幂等 | 反复点通知会不断重置本轮（取消定时器 + `MediaPlayer.reset()` + 从头播），这一轮永远响不完 |
| F12 | P1 | `OnErrorListener` 返回 `true` 却只打日志 | 播放中异步出错后本轮彻底静音，服务仍占前台直到超时 |
| F13 | P1 | 闹钟音量原值只存服务实例字段，靠 `onDestroy()` 恢复 | 进程被杀时系统闹钟音量**被永久改成最大**，且无恢复入口（对用户设备的持久副作用） |
| F14 | P1 | `handleLocalStop` 用 `STOP_FOREGROUND_DETACH` | 点「停止」后通知反而留在通知栏，还带着操作按钮 |
| F15 | P2 | `startAlarmPlayback()` 失败分支未归还已提升的音量 | 配置「一直响」时全程把系统音量钉在最大 |
| F16 | **P1** | 无 `RECEIVE_BOOT_COMPLETED` / 开机接收器 | 设备重启清空闹钟 → 「一直提醒到处理」的会话**永久静默失效**且不自愈 |
| F17 | P0 | 除 `readInt()` 外所有配置读取点缺 `ClassCastException` 兜底 | 脏键/迁移后 `SMSReceiver` 每次静默丢短信，表现为「改过设置后再也不提醒」 |
| F18 | P0 | `getKeywords()` 的 CCE 兜底把旧数据当「未配置」 | 旧版以字符串存的关键词**静默全丢**，界面显示「未配置」，用户无线索 |
| F19 | P2 | `getRingDurationSec()` 缺另两个 int 读取器都有的范围钳制 | 脏配置 `999999` 会让 FGS/WakeLock 挂近 12 天；负值立即结束本轮 |
| F20 | P2 | `parseQuietHour` 把 `99` 归一化成 `3` | 脏配置被静默改写成「每天凌晨 3 点不响铃」，与类注释承诺相反 |
| F21 | P1 | 字符白名单残留 `*`/`#`，但 `stripSeparators` 会丢弃它们 | v5「校验通过但配置被静默改写」的缺陷没修干净 |
| F22 | P1 | `canonical()` 无条件取末 11 位，而校验上限已放宽到 15 位 | 两个不同 13 位号码被压成同一个 → **误报** |
| F23 | P1 | 后缀匹配双向且要求两侧 ≥7 位 | 短片段误命中长号码；`10086`/`95588` 带 `86` 前缀时**规则静默失效** |
| F24 | P1 | 关键词不认顿号/句号/零宽字符；按 UTF-16 `char` 截断 | 「报警、求助」变成永不命中的单条关键词；emoji 跨截断边界留下孤立代理项 |
| F25 | P1 | `HitLog`：`apply()` 在广播里、`clear/dump` 未加锁、`sender/keyword` 未单行化、`char` 截断 | 日志丢失、清空失效、记录被拆行挤出缓冲、写入非法 UTF-16 |
| F26 | P1 | `Onboarding` 是唯一没做 edge-to-edge 的页面 | targetSdk 35+ 下三键导航遮挡「完成」按钮，**首次安装可能卡在引导页** |
| F27 | P1 | 引导完成只靠 `setComponentEnabledSetting(DISABLED)`，失败被吞 | 受管设备/部分 ROM 上每次冷启动都重新进引导页 |
| F28 | P1 | `POST_NOTIFICATIONS` 永久拒绝分支没有「去设置」Action | 通知是主链路唯一入口，被永久拒绝后应用内无恢复路径 |
| F29 | P2 | 权限回调分支绕过 `PhoneNumbers.isValidInput()` 直接 `saveConfig` | 请求权限期间改动输入框 → 脏号码静默写入，规则失效 |
| F30 | P2 | Snackbar / 权限结果回调无生命周期保护 | 已退出的 Activity 仍会读取界面并写入配置 |
| F31 | P2 | 旋转重建丢失 3 个 UI 标志；「不再显示」把 `autoSuggDisabled` 置真反而触发另一个弹框 | 反复弹「是否选择联系人」，语义反噬 |
| F32 | P2 | `showOnboarding` 是 `static` 且唯一置 true 的分支立刻 `finish()` 返回 | 注释宣称的「引导刚结束不抢焦点」保护恒不生效 |
| F33 | P2 | 自动联想开关写进两个 prefs 文件；`onResume` 不校准 | 绕过设置页修改时两份状态分叉，界面显示旧值 |
| F34 | P2 | 隐私政策用 `http://` 而 `usesCleartextTraffic=false`；清单留着已废弃的 `showOnLockScreen` | 明文外链被拦截；无效属性造成误导 |

### 3.2 复核后判定为误报 / 无需修改（逐条排除，避免为了凑数而改）

| 项 | 判定依据 |
|---|---|
| 通知按钮 PendingIntent 串台 | `REQ_STOP=11` / `REQ_SNOOZE=12` / `REQ_CONTENT=10` / `REQ_FULLSCREEN=13` / 闹钟 `41`/`42` 互不相同，且 action 不同 |
| `AlertScheduler.cancel()` 用 round=1 取消任意轮次 | PendingIntent 等价性**忽略 extras**，正确 |
| `FLAG_IMMUTABLE` 导致 `EXTRA_ROUND` 失效 | 不影响创建时自带的 extras，`RepeatAlarmReceiver` 能读到 |
| 「服务里 Handler 泄漏」「未注销 registerReceiver」 | `onDestroy` 已 `removeCallbacksAndMessages(null)`；本应用无动态注册 |
| 字符串资源中英键缺失 | 154 键一一对应，8 个数组 / 38 item 完全一致；作为 Preference key 的字符串值未翻译 |
| `getStringSet` 返回值被修改污染缓存 | `getKeywords()` 返回 `Collections.unmodifiableSet(new LinkedHashSet<>(...))`，`save()` 传新 Set |
| `SimpleDateFormat` 线程不安全 | `format()` 每次 new 局部实例，非共享字段 |
| `readInt()`、`QuietHours` 跨零点、`RoundPolicy` 轮次规则 | 逐条核对单测断言与实现，均自洽 |

### 3.3 已确认但本轮**不修**（附理由，见 §7）

`U01` 亮屏 / FSI 未拉起时本轮只有渠道的一声闹铃，无 MediaPlayer 持续播放；
`U02` 部分 ROM 把长短信 PDU 拆成多个广播时跨片关键词漏报；
`U03` 无音频焦点管理，来电/蓝牙打断后不自动恢复；
`U04` `ringDurationSec=0` 无硬上限，WakeLock 30 分钟后到期可能静默；
`U05` 静音时段是本轮一次性快照，跨分钟边界本轮内不切换；
`U06` 用户把渠道重要性调成 `IMPORTANCE_NONE` 时无显式提示；
`U07` 跨时区 / DST 下命中日志时间显示漂移；
`U08` 通知 ID 123 被普通通知与 FGS 通知共用的真机行为；
`U09` 厂商省电策略对 `setAlarmClock` 准时率的影响；
`U10` `canUseFullScreenIntent()` 返回 false 时 HUN 的可达性。

**共性理由**：要么改法属于架构级（U01，需引入新的后台 FGS 启动豁免，与「稳定性优先 + 约束 3」冲突），
要么前提依赖具体 ROM / 系统行为，**必须先真机取证再改**，否则容易把现在能用的链路改坏。
本轮把它们全部转进 §8 的真机验收清单，而不是拍脑袋改代码。

---

## 4. 变更总览

### 4.1 修复项（34 项，见 §3.1 与 §5）

按链路分组：
- **通知与唤醒链路 5 项**（F01-F05）：渠道创建隔离、发送前校验、API<26 声音、降级去 FSI、动作/划掉语义
- **状态机与调度 6 项**（F06-F11）：去重原子化、会话锁、死会话防护、配置复核、停止兜底、播放幂等
- **播放与系统状态 4 项**（F12-F15）：异步错误收尾、音量跨进程守护、停止撤通知、失败归还音量
- **重启恢复 1 项**（F16）：新增 `BootReceiver`
- **配置与数据 9 项**（F17-F25）：类型兜底、旧数据迁移、范围钳制、号码/关键词/日志三类边界
- **界面与合规 9 项**（F26-F34）：引导页 insets 与完成标记、权限恢复入口、回调校验、生命周期、状态保存、单一数据源、链接与废弃属性

### 4.2 重构项

**局部重构 3 处，无架构级重构**：
1. `NotificationHelper.ensureChannels()` 拆成 `createQuietChannel` / `createAudibleChannel` / `tryBypassDnd` 三个方法（失败隔离）。
2. `NotificationHelper.build/post` 增加 `withActions` 参数，原有 3 个 `build` 重载、2 个 `post` 重载**全部保留并委托**到完整实现 —— 对外接口兼容。
3. `AppConfig` 抽出 `safeGetString` / `safeGetBoolean`，把散落的裸读取收敛到两个入口。

按约束 3，本轮**未提交任何架构级重构方案，也未改动主链路结构**。

### 4.3 新增功能（均贴合原场景，无花哨功能）

| 项 | 说明 | 代价 |
|---|---|---|
| 开机 / 应用更新后恢复未处理完的提醒 | `BootReceiver` 监听 `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` | 新增 `RECEIVE_BOOT_COMPLETED`（normal 级，安装即授予），无界面、无网络 |
| 系统闹钟音量跨进程守护 | `AlarmVolumeGuard`，`MainActivity` 与 Service 启动时各检查一次 | 一个极小的 SharedPreferences 文件，正常收尾即清除 |
| 会话内「用户主动稍后提醒」标记 | `AlertSession.markSnoozed/consumeSnoozed` | 一个布尔键，一次性消费 |
| 边界单测 8 条 | `PhoneNumbersTest` +5、`KeywordMatcherTest` +3 | 仅测试代码 |

### 4.4 文件清单

- **修改**：`app/build.gradle`、`AndroidManifest.xml`、15 个 main Java、2 个测试 Java、
  `screen_onboarding.xml`、`values/strings.xml`、`values-zh-rCN/strings.xml`、`app_preferences.xml`
- **新增**：`AlarmVolumeGuard.java`、`BootReceiver.java`
- **新增（开发期工具，不进 APK）**：`tools/patch_v420_batch*.py`（本轮补丁，幂等断言）、
  `tools/mark_modified.py`（改动标注）
- **依赖变化**：无。`dependencies` 块一行未动。
- **版本号**：`versionCode 22 → 23`、`versionName 4.1.2 → 4.2.0`，界面显示版本号同步。

---

## 5. 分点变更详情

> 每项按【代码位置】+【原始问题】+【修改思路】+【改动代码片段】给出。
> 完整文件见 §6；所有改动行/代码块均带 `// modified`（XML 在合法位置用 `<!-- modified -->`）。

### F01 / F02　通知渠道创建与发送前校验

【代码位置】`NotificationHelper.java` → `ensureChannels()` / `createQuietChannel()` / `createAudibleChannel()` / `post()`
【原始问题】两个渠道的创建、旧渠道删除被同一个 `try` 包住，`setBypassDnd()` 在部分 ROM 抛异常时，
异常发生在「静音渠道已建、有声渠道未建」之间，catch 只打日志；之后 `postRound()` 恒用
`CHANNEL_ID_AUDIBLE` 发通知，而该渠道根本不存在。Android 8+ 对未创建渠道的通知**静默丢弃**，
于是短信匹配成功、日志打「已发送」，用户却什么都没收到。
【修改思路】每个渠道独立 try；把非关键的 `setBypassDnd` 单独兜底，保证 `createNotificationChannel()`
一定有机会执行；`post()` 发送前用 `getNotificationChannel()` 校验，缺失则重建一次，
仍缺失退回系统默认渠道，保证至少能显示。

```java
// modified: 【v4.2 独立复审】原实现把两个渠道的创建 + 删旧渠道全塞进一个 try。
createQuietChannel(manager, context);   // modified: 渠道创建相互隔离
createAudibleChannel(manager, context); // modified: 渠道创建相互隔离
...
private static void tryBypassDnd(NotificationChannel channel) { // modified: 新增
    try { channel.setBypassDnd(true); } catch (Exception e) { Log.w(TAG, "setBypassDnd unsupported on this ROM", e); }
}
...
String effectiveChannel = channelId; // modified: 渠道不存在时 notify() 会被系统静默丢弃
if (!channelExists(app, channelId)) { // modified
    ensureChannels(app); // modified
    if (!channelExists(app, channelId)) { // modified
        effectiveChannel = NotificationChannel.DEFAULT_CHANNEL_ID; // modified
    }
}
```

### F03　API 23-25 的「第一声」兜底

【代码位置】`NotificationHelper.build(...)`
【原始问题】`ensureChannels()` 在 `SDK_INT < O` 直接 return，渠道上配的闹铃音在这三代系统上根本不存在；
builder 只设了 `PRIORITY_HIGH` / `CATEGORY_ALARM`，没有 `setSound` / `setVibrate`。
【修改思路】在 `SDK_INT < O` 或渠道缺失时补 builder 级声音与震动；API 26+ 且渠道正常时
builder 的声音会被渠道设置覆盖，不会双重响铃。

```java
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channelExists(context, channelId)) { // modified
    Uri legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); // modified
    if (legacySound == null) { // modified
        legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); // modified
    }
    builder.setSound(legacySound, AudioAttributes.USAGE_ALARM); // modified
    builder.setVibrate(new long[]{0, 400, 300, 400}); // modified
}
```

### F04　降级通知不再带全屏 Intent

【代码位置】`NotificationHelper.notifyDirectly(...)`
【原始问题】该方法只在「播放失败 / `startForeground` 失败」时调用，原来传 `withFullScreenIntent=true`，
会拉起 `AlertActivity` → 再次 `startForegroundService` → 再次失败 → 再发通知，形成自触发循环。
【修改思路】降级只是补一声，去掉 FSI 与动作按钮。

```java
// modified: 【v4.2 独立复审】原实现这里传 withFullScreenIntent=true。
post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, false, false); // modified
```

### F05　通知动作与「划掉」语义

【代码位置】`NotificationHelper.build(..., boolean withActions)` / `AlertDispatcher.postQuiet(...)`
【原始问题】所有通知（含「等待中 / 已稍后 / 已结束」）都挂 STOP/SNOOZE；`setDeleteIntent` 指向停止，
随手划掉横幅就取消后续全部轮次，与「不处理就 5 分钟后再响」直接冲突。
【修改思路】新增 `withActions` 参数（原有重载全部保留并委托，接口兼容）；状态通知传 false；
彻底移除 `setDeleteIntent`。想停止请点明确的「停止」按钮。

```java
if (withActions) { // modified: 只有真正要叫人的一轮带按钮
    builder.addAction(R.drawable.ic_block_24dp, context.getString(R.string.stop), buildStopIntent(context)); // modified
    builder.addAction(0, context.getString(R.string.notif_snooze), buildSnoozeIntent(context)); // modified
}
...
// postQuiet（状态通知）
NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT, // modified
        session.getFrom(), session.getKeyword(), session.getPerson(), // modified
        session.getBody(), status, false, false); // modified: 无 FSI、无动作
```

### F06　去重原子化与完整指纹

【代码位置】`SMSReceiver.isDuplicate(...)`
【原始问题】`volatile` 只保证可见性，不保证「比较 + 两个字段分两次写」原子；并发投递时两个线程
可能都读到旧值双双放行，或读到「新指纹 + 旧时间戳」。指纹用 `body.length() + body.hashCode()`，
`hashCode()` 是可构造碰撞的 32 位哈希，3 秒内两条不同短信会被误吞（漏报）。
【修改思路】单把 `DEDUP_LOCK` 把整段收成原子操作；指纹改用完整正文 + `\0` 分隔。

```java
private static final Object DEDUP_LOCK = new Object(); // modified
String fingerprint = (sender == null ? "" : sender) + '\0' + body + '\0' + keyword; // modified
synchronized (DEDUP_LOCK) { // modified
    if (fingerprint.equals(lastFingerprint) && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) { // modified
        return true; // modified
    }
    lastFingerprint = fingerprint; // modified
    lastFingerprintAt = now; // modified
    return false; // modified
}
```

### F07　会话状态机加锁

【代码位置】`AlertDispatcher` 全部 public / private 入口
【原始问题】`cancel 闹钟 → 改会话 → post → schedule` 是长链且无锁；短信广播与闹钟广播交错时，
A 读到旧会话、B 开新会话、A 再把新会话的 round 改成旧轮次，并用 `FLAG_UPDATE_CURRENT`
覆写同一个 PendingIntent，导致某一轮丢失或错轮。
【修改思路】所有入口声明 `static synchronized`（静态方法锁 `AlertDispatcher.class`，共享同一把锁）；
调用链内部无回环，不会死锁。改动只在方法签名，业务逻辑一行未动。

```java
public static synchronized void onSmsMatched(Context context, String sender, String keyword, // modified
                                            String person, String body) { // modified
public static synchronized void onRepeatAlarm(Context context, int round) { // modified
public static synchronized void stop(Context context) { // modified
public static synchronized void snooze(Context context, int delayMinutes) { // modified
private static synchronized void snoozeInternal(...) { // modified
public static synchronized void finish(...) { // modified
```

### F08　已结束会话不可复活

【代码位置】`AlertDispatcher.snoozeInternal(...)`
【原始问题】无条件 `session.setActive(true)`；「已结束」通知原来也带稍后提醒按钮，点一下死会话复活。
【修改思路】开头校验 `isActive()`，不活跃直接返回。

```java
if (!session.isActive()) { // modified: 已结束的会话不能被「稍后提醒」复活
    Log.i(TAG, "session already finished, ignore snooze"); // modified
    return; // modified
}
```

### F09　闹钟到点复核实时配置（含本轮回归修正）

【代码位置】`AlertDispatcher.onRepeatAlarm(...)` / `AlertSession.markSnoozed()`
【原始问题】已注册进 `AlarmManager` 的闹钟不受配置变更影响，用户把重复间隔改成 0 后到点仍会再响一轮。
【修改思路】用实时配置复核 `RoundPolicy.hasNextRound`。**补注（本轮自己引入并回滚的回归）**：
第一版无条件复核会把用户主动点的「稍后提醒」也拦掉（用户设了不重复时，snooze 用兜底间隔挂闹钟，
到点却因 `interval=0` 被判 finish）。因此给 `AlertSession` 加一次性 `snoozed_round` 标记，
由 `snoozeInternal` 写入、`onRepeatAlarm` 读取并消费，只豁免「用户主动要的那一轮」。

```java
boolean userSnoozed = session.consumeSnoozed(); // modified: 区分「自动重复」与「用户主动」
if (!userSnoozed) { // modified
    AppConfig config = AppConfig.get(app); // modified
    if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total, // modified
            config.getRingDurationSec(), config.getRepeatIntervalMin())) { // modified
        finish(app, Math.max(1, round - 1)); // modified
        return; // modified
    }
}
...
session.markSnoozed(); // modified: 【v4.2】标记下一轮不受配置复核影响
```

### F10　停止服务前判断存活

【代码位置】`AlertDispatcher.requestServiceStop(...)` / `SMSForegroundService.isRunning()`
【原始问题】服务已 `stopSelf` 后 `startService` 会重新创建一次，纯空转；Android 8+ 后台还会拒绝。
【修改思路】Service 维护 `static volatile sRunning`，`onCreate` 置真 / `onDestroy` 置假；
调度器只在服务确实存活时发 `ACTION_STOP_ALERT`。

```java
if (!SMSForegroundService.isRunning()) { // modified
    Log.d(TAG, "alert service not running, skip STOP action"); // modified
    return; // modified
}
```

### F11　同轮播放幂等

【代码位置】`AlertActivity.onNewIntent(...)` / `SMSForegroundService.handleStartAlert(...)`
【原始问题】`onCreate` 已有 `savedInstanceState == null` 防旋转重建，但 `onNewIntent` 无条件
`startAlertService()`；用户反复点通知会让本轮定时器被取消、`MediaPlayer.reset()`、从头播放。
【修改思路】Activity 侧记录 `lastStartedRound`，仅轮次变化时重启；Service 侧用 `alertActive` 幂等兜底。

```java
int incomingRound = AlertSession.get(this).getRound(); // modified
if (incomingRound != lastStartedRound) { // modified
    lastStartedRound = incomingRound; // modified
    startAlertService(); // modified: 仅新一轮重启
}
...
if (alertActive) { // modified: 同一轮重复触发不再重置播放
    Log.d(TAG, "alert round already playing, ignore duplicate START"); // modified
    return; // modified
}
```

### F12 / F15　异步错误收尾与失败归还音量

【代码位置】`SMSForegroundService.startAlarmPlayback()` 的 `OnErrorListener` / `catch` 分支
【原始问题】`onError` 返回 `true` 表示「应用自行处理」，但只打日志；MediaPlayer 进入 Error 状态后
本轮彻底静音。`prepareAsync` 同步失败分支也未归还已提升的系统音量。
【修改思路】`onError` 里关播放窗口、停震动、释放播放器、归还音量、补一声通知音；
catch 分支补 `restoreAlarmVolume()`。

```java
ringWindowOpen = false; // modified
stopRingAndVibration(); // modified
releasePlayer(); // modified
restoreAlarmVolume(); // modified
fallbackToNotificationSound(); // modified: 至少补一声，不让本轮彻底静默
return true; // modified
...
} catch (Exception e) { // modified
    releasePlayer(); // modified
    restoreAlarmVolume(); // modified: 音量可能已提升成功
    return false; // modified
}
```

### F13　闹钟音量跨进程守护（新增 `AlarmVolumeGuard.java`）

【代码位置】`AlarmVolumeGuard.java`（新增）、`SMSForegroundService` / `MainActivity` / `Onboarding`
【原始问题】原音量只存实例字段，靠 `onDestroy()` 恢复；进程被低内存回收 / 强杀 / 关机时不执行，
而 `setStreamVolume(STREAM_ALARM, max)` 是**持久化**的系统设置，用户手机闹钟音量被永久改大且无恢复入口。
【修改思路】提升前 `commit()` 落盘原值，恢复成功后清除标记；任意组件启动时先检查残留标记并还原一次。

```java
static void remember(Context context, int savedVolume) { // modified: 新增
    prefs(context).edit().putInt(KEY_SAVED, Math.max(0, savedVolume)).commit(); // modified
}
static void restoreIfStale(Context context) { // modified: 新增
    int saved = prefs(context).getInt(KEY_SAVED, NO_VALUE); // modified
    if (saved == NO_VALUE) { return; } // modified
    AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); // modified
    if (am != null) { am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0); } // modified
    p.edit().remove(KEY_SAVED).commit(); // modified: 无论成败都清标记
}
```

### F14　点「停止」后撤掉通知

【代码位置】`SMSForegroundService.handleLocalStop()`
【原始问题】用 `STOP_FOREGROUND_DETACH`，官方语义是「服务销毁后通知仍保留」；
`AlertDispatcher.stop()` 先 cancel 再请求停止，服务随后 DETACH 又把带按钮的通知留在通知栏。
【修改思路】本地停止（用户明确结束）改 `REMOVE`；`endRound()` 的「等待中」通知仍保留 `DETACH`。

```java
stopSelfSafely(true); // modified: 用户点停止 = REMOVE，不再残留可操作的通知
```

### F16　重启后恢复提醒（新增 `BootReceiver.java`）

【代码位置】`BootReceiver.java`（新增）、`AndroidManifest.xml`
【原始问题】`setAlarmClock` 是系统内存中的闹钟，重启即清空。会话状态虽落盘，但没有组件在开机后被唤醒，
也没有记录「下一次触发时刻」，于是「一直提醒到处理」的会话在重启后彻底静默且不自愈。
【修改思路】监听 `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`（覆盖安装同样清闹钟），
读 `AlertSession`，若仍 active 则取消残留并走 `onRepeatAlarm(round+1)` 补发一轮——
它内部会用实时配置与总轮次复核，超限自动结束。**只发通知，不启动 FGS**，符合后台限制。

```java
if (!Intent.ACTION_BOOT_COMPLETED.equals(action) // modified
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { return; } // modified
if (!session.isActive()) { return; } // modified
int nextRound = session.getRound() + 1; // modified
AlertScheduler.cancel(app); // modified
AlertDispatcher.onRepeatAlarm(app, nextRound); // modified: 复用既有轮次规则
```

```xml
<!-- modified: 【v4.2】系统重启会清空 AlarmManager 闹钟 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<receiver android:name=".BootReceiver" android:enabled="true" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

### F17 / F18　配置读取兜底与旧数据迁移

【代码位置】`AppConfig.safeGetString/safeGetBoolean/migrateLegacyKeywords()`
【原始问题】`readInt()` 的注释已经写明「键被写成别的类型时 `getString` 会抛 CCE，
导致收到短信后再也不提醒」，但防护只覆盖 3 个 int 键，其余 7 处全是裸调用。
`getKeywords()` 的 CCE 兜底把旧版以字符串存的关键词直接当成「未配置」返回空集合，
用户关键词静默全丢且界面显示「未配置」。
【修改思路】抽通用安全读取器覆盖全部 `getString/getBoolean`；CCE 时尝试按 String 读回、
用 `KeywordMatcher.parseKeywords()` 解析并 `commit()` 一次性迁移成 Set。

```java
private String safeGetString(String key, String defaultValue) { // modified: 新增
    try { return configPrefs.getString(key, defaultValue); } // modified
    catch (ClassCastException e) { return defaultValue; } // modified
}
private Set<String> migrateLegacyKeywords() { // modified: v4.2 新增
    String legacy = configPrefs.getString(KEY_KEYWORDS, null); // modified
    List<String> parsed = KeywordMatcher.parseKeywords(legacy); // modified
    Set<String> migrated = new LinkedHashSet<>(parsed); // modified
    configPrefs.edit().putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(migrated)).commit(); // modified
    return migrated; // modified
}
```

### F19 / F20　数值配置钳制

【代码位置】`AppConfig.getRingDurationSec()` / `parseQuietHour(...)`

```java
int value = readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC); // modified
return Math.max(0, Math.min(value, 120)); // modified: 与 arrays.xml 合法值域对齐
...
// modified: 越界一律按「未启用」处理，不再 normalizeHour 静默改成另一个时段
return hour >= 0 && hour <= 23 ? hour : -1; // modified
```

### F21 / F22 / F23　号码校验与比对

【代码位置】`PhoneNumbers.hasOnlyFormatChars / canonical / matches`
【原始问题】v5 白名单残留 `*`/`#`（`stripSeparators` 会丢弃 → 静默改写）；
`canonical` 无条件取末 11 位与 15 位校验上限冲突；后缀匹配双向 + 7 位门槛。
【修改思路】移除 `*#`；保留完整数字串；后缀匹配改成「较长侧 = 较短侧 + 至多 4 位前缀」，
较短侧门槛降到 5 位以支持 `10086`/`95588`。

```java
// modified: 【v4.2】移除 '*' / '#'（USSD 补充符会被静默丢弃）
...
// modified: 保留完整数字串，不再无条件截末 11 位
return digits; // modified
...
String longer = configuredNumber.length() >= senderNumber.length() // modified
        ? configuredNumber : senderNumber; // modified
String shorter = configuredNumber.length() >= senderNumber.length() // modified
        ? senderNumber : configuredNumber; // modified
int prefixLength = longer.length() - shorter.length(); // modified
return shorter.length() >= MIN_INPUT_LENGTH && prefixLength <= 4 // modified
        && longer.endsWith(shorter); // modified
```

### F24　关键词归一化与码点截断

【代码位置】`KeywordMatcher.normalize / parseKeywords`
【原始问题】中文顿号 U+3001、句号 U+3002、零宽空格 U+200B、BOM U+FEFF 全部原样保留，
「报警、求助」变成一个永不命中的关键词；截断按 UTF-16 `char`，emoji 跨边界留下孤立代理项。
【修改思路】归一化时把 CJK 标点映射为 `,`、零宽/Unicode 空白映射为空格；
分隔符改预编译 `Pattern`；截断按 `codePointCount` / `offsetByCodePoints`。

```java
if (ch == '\u3001' || ch == '\u3002' || ch == '\uFF0E') { // modified
    builder.append(','); // modified: CJK 标点 -> 分隔符
} else if (ch == '\u200B' || ch == '\uFEFF' || ch == '\u2060' // modified
        || ch == '\u200E' || ch == '\u200F') { // modified
    builder.append(' '); // modified: 零宽字符 / BOM -> 分隔符
} else if (ch == '\u3000' || ch == '\u00A0' // modified
        || Character.isSpaceChar(ch) || Character.isWhitespace(ch)) { // modified
    builder.append(' '); // modified
}
...
if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) { // modified
    keyword = keyword.substring(0, keyword.offsetByCodePoints(0, MAX_KEYWORD_LENGTH)); // modified
}
```

### F25　命中日志落盘、并发与编码

【代码位置】`HitLog.add / dump / clear / format / singleLine`
【原始问题】`apply()` 在 `BroadcastReceiver` 里只更新内存，进程被回收就丢；
`clear/dump` 未与 `add` 共用锁；只有 `body` 单行化，`sender/keyword` 含换行会把一条记录拆成多行
挤空 20 条缓冲；按 `char` 截断会写入非法 UTF-16。
【修改思路】`commit()` 同步落盘；三个出口统一 `synchronized (LOCK)`；
所有字段走同一个 `singleLine(value, maxLen)`（码点安全）。

```java
prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).commit(); // modified
synchronized (LOCK) { // modified: 与 add()/dump() 共用同一把锁
    prefs.edit().remove(KEY_HIT_LOG).commit(); // modified: 同步落盘
}
private static String singleLine(String value, int maxLength) { // modified: v4.2 重写
    String text = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim(); // modified
    return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…"; // modified
}
```

### F26 / F27　引导页可用性与完成标记

【代码位置】`Onboarding.onCreate / finishOnboarding`、`MainActivity.isOnboardingEnabled`、
`screen_onboarding.xml`、`AppConfig.isOnboardingDone/setOnboardingDone`
【原始问题】引导页是唯一没做 edge-to-edge 的 Activity，三键导航会盖住「完成」按钮（首次安装必经路径）；
引导完成只靠 `setComponentEnabledSetting(DISABLED)`，失败被吞后每次冷启动重进引导页。
【修改思路】根布局加 id 并 `EdgeToEdgeHelper.apply(...)`；完成时先 `commit()` 一个
`onboarding_done_v4` 标记，组件禁用降级为加固；`isOnboardingEnabled()` 以标记为主判定。

```java
EdgeToEdgeHelper.apply(this, R.id.onboarding_root); // modified
AppConfig.get(this).setOnboardingDone(true); // modified: 先落盘，组件禁用失败也不影响
...
if (AppConfig.get(this).isOnboardingDone()) { return false; } // modified
```

### F28 / F29 / F30 / F31 / F32　主界面生命周期与输入校验

【代码位置】`MainActivity` 多处
【修改思路与片段】
```java
// 通知权限永久拒绝：给带「去设置」Action 的长驻 Snackbar
if (shouldExplainDenied(Manifest.permission.POST_NOTIFICATIONS)) { // modified
    showPermissionSnackbar(R.string.notif_permission_denied); // modified
}
// 权限回调补号码校验（原来绕过 handleTrigger 的校验直接 saveConfig）
if (!TextUtils.isEmpty(contactRaw) && !PhoneNumbers.isValidInput(contactRaw)) { // modified
    showSnackbar(R.string.input_format_error); return; // modified
}
// 权限结果与 Snackbar 生命周期保护
if (isFinishing() || isDestroyed()) { return; } // modified
// 旋转重建恢复三个 UI 标志
isContactPicked = savedInstanceState.getBoolean("state_contact_picked", false); // modified
// 「不再显示」同时置位会话标志，避免语义反噬
contactPickerAsked = true; // modified
// 引导来源改为实例字段 + Intent extra，取代恒为 false 的 static 标记
private boolean fromOnboarding = false; // modified
```

### F33 / F34　设置页单一数据源与合规细节

```java
// SettingsActivity.onResume 每次可见时用 AppConfig 校准开关，避免双份 prefs 分叉
autoPref.setOnPreferenceChangeListener(null); // modified
syncAutoSuggestionState(autoPref); // modified
autoPref.setOnPreferenceChangeListener(this); // modified
```
```xml
<!-- app_preferences.xml：关闭框架自动持久化，由 AppConfig 单一托管 -->
android:persistent="false" <!-- 在元素属性中，标注见文件内注释 -->
<string name="privacy_url">https://unchartedtech.work/privacy-policy</string> <!-- modified: 明文外链改 https -->
<!-- AndroidManifest.xml：移除已废弃且语义无关的 android:showOnLockScreen -->
```

### 5.1 本轮新增单测（8 条）

`PhoneNumbersTest`：`isValidInput_rejectsUssdSupplementChars`、`canonical_keepsLongInternationalNumberIntact`、
`matches_doesNotCollapseDifferentLongNumbers`、`matches_shortServiceNumberToleratesCountryPrefix`、
`matches_rejectsTooLongPrefixDifference`。
`KeywordMatcherTest`：`parseKeywords_supportsCjkPunctuation`、`parseKeywords_stripsZeroWidthCharacters`、
`parseKeywords_truncatesByCodePoint`。

---
## 6. 完整整合后的全部源代码

**范围说明**：以下给出**整合后的全部项目源码**——`app/src/main/java/` 下全部 Java 文件、
`app/src/test/java/` 下全部单测、`AndroidManifest.xml`、`app/build.gradle`，以及本轮改动过的
XML/资源文件。图片、音频、`.webp`、mipmap 位图等二进制资源无法以文本呈现，见仓库对应目录；
Gradle wrapper、`.github/workflows/`、`.gitattributes`、keystore 等未改动文件亦见仓库。

**标注约定**：本次新增/修改的代码行与代码块均以单行注释 `// modified` 标注；
XML 因属性之间不允许插入注释，在合法位置以 `<!-- modified -->` 标注并在 §5 逐条列明；
未改动的历史代码保留其原有的版本标注（如 `// modified: 【独立复审修正】`），便于区分改动轮次。
逐行精确对照可用 `git diff <v4.1.2-commit> HEAD`。

### 6.1 `app/build.gradle`（构建配置）

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

// modified: 【签名固定】固定签名材料的路径。
//
// 背景（这是实测出来的，不是推测）：GitHub Actions 的 runner 每次都是全新机器，
// AGP 会在 ~/.android/ 下自动生成 debug.keystore —— 每次都是**不同的 key**。
// 我比对过连续两次 CI 构建出的 APK，CERT.RSA 的 SHA-256 分别是 dce46633… 和
// bbb44ad0…，即签名不同。后果是：新版 APK 装不进已装旧版的手机，系统提示
// 「应用未安装」，必须卸载重装，而卸载会丢掉号码、关键词、命中记录全部配置。
//
// 因此仓库里固定一份自用签名 keystore/bellgram.jks，本地构建与 CI 共用同一个 key，
// 从此覆盖升级不再需要卸载。首次时该文件不存在（CI 会先生成并上传），
// 此时自动退回 AGP 默认 debug 签名，保证构建不会因此失败。
def fixedKeystore = rootProject.file('keystore/bellgram.jks')

android {
    // modified: AGP 8 要求显式 namespace
    namespace 'com.saiga.find.messagefinder'

    // modified: 29 -> 34 -> 36（Android 16；需安装 SDK Platform 36）
    compileSdk 36

    // modified: 固定签名。这是**个人自用**签名，不是发布密钥，未用于任何应用商店；
    // 口令明文放置是有意的——private 仓库 + 保证本地与 CI 签名完全一致可复现。
    // 如果将来要上架，请另建正式密钥并改用 CI Secrets / 本地 properties 读取。
    signingConfigs {
        if (fixedKeystore.exists()) {
            personal {
                storeFile fixedKeystore
                storePassword 'bellgram'
                keyAlias 'bellgram'
                keyPassword 'bellgram'
                // minSdk 23：保留 v1 以兼容老 ROM 的安装校验，同时签 v2
                v1SigningEnabled true
                v2SigningEnabled true
            }
        }
    }

    defaultConfig {
        applicationId "com.saiga.find.messagefinder"
        // 保持向后兼容：Android 6.0 起
        minSdk 23
        // modified: 29 -> 36（本轮核心合规项，理由与配套架构改造见文件头与报告 §5.1）
        targetSdk 36
        // modified: v4.2.0 = 全量独立复审（30+ 项缺陷修复），versionCode 22 -> 23
        versionCode 23 // modified: 本轮复审发布
        versionName "4.2.0" // modified: 本轮复审发布
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 调试包不做混淆，便于查看堆栈
            // modified: 有固定 keystore 就用它，保证 CI 产物能覆盖升级；否则用默认 debug 签名
            if (fixedKeystore.exists()) {
                signingConfig signingConfigs.personal
            }
        }
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            // modified: 同上，release 也走固定签名
            if (fixedKeystore.exists()) {
                signingConfig signingConfigs.personal
            }
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

### 6.2 `app/src/main/AndroidManifest.xml`（清单）

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
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

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
    <!-- modified: 【v4.2】系统重启会清空 AlarmManager 闹钟，需要开机广播重新注册，
         否则「一直提醒到处理」的会话在重启后永久静默失效。normal 级权限，安装即授予。 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
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

        <!-- modified: 全屏告警页。除了代码里的 setShowWhenLocked/setTurnScreenOn，
             这里再补 manifest 属性——MIUI / HyperOS 上有 ROM 会忽略纯代码设置，
             导致锁屏拉起时页面被锁屏盖住、铃声在响却看不到任何界面。
             v4.2：移除已废弃且语义无关的 android:showOnLockScreen（它实际是
             "为所有用户显示"的旧名，不控制锁屏展示），保留真正生效的两个属性。
             configChanges：避免旋转屏幕重建页面（重建会再次启动播放服务，铃声从头开始）。 -->
        <activity
            android:name=".AlertActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleTop"
            android:showWhenLocked="true"
            android:taskAffinity="com.saiga.find.messagefinder.alert"
            android:theme="@style/AlertTheme"
            android:turnScreenOn="true"
            tools:targetApi="o_mr1" />

        <!-- modified: 【v4.2】重启 / 覆盖安装后恢复未处理完的提醒会话。
             exported=true 是因为这两个都是系统广播（发送方不是本应用）。 -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

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

### 6.3 `app/src/main/java\com\saiga\find\messagefinder\AlarmVolumeGuard.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4.2：闹钟音量的跨进程守护。
//
// 背景（独立复审发现）：服务播放前会把系统 STREAM_ALARM 音量临时提到最大，
// 原实现只把「原音量」存在服务的实例字段里，靠 onDestroy() 恢复。
// 但进程被低内存回收 / 用户强杀 / 关机时 onDestroy() 根本不执行，
// 而 setStreamVolume() 改的是**持久化**的系统设置 —— 用户手机的闹钟音量
// 会被永久钉在最大，且应用侧再无恢复入口。这是对用户设备的持久性副作用。
//
// 做法：提升前把原音量同步落盘；恢复成功后立刻清除标记；
// 应用下次任意组件启动时先检查残留标记并恢复一次。
// 未复制第三方代码。

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Log;

final class AlarmVolumeGuard { // modified

    private static final String TAG = "AlarmVolumeGuard"; // modified: 新增
    private static final String PREF_NAME = "alarm_volume_guard_v4"; // modified: 新增
    private static final String KEY_SAVED = "saved_alarm_volume"; // modified: 新增
    private static final int NO_VALUE = -1; // modified: 新增

    private AlarmVolumeGuard() { // modified: 新增
        // 工具类不允许实例化
    }

    private static SharedPreferences prefs(Context context) { // modified: 新增
        Context app = context.getApplicationContext() != null // modified
                ? context.getApplicationContext() : context; // modified
        return app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE); // modified
    }

    /** 提升音量前调用：同步落盘，保证进程被杀后标记仍在。 */
    static void remember(Context context, int savedVolume) { // modified: 新增
        try { // modified
            prefs(context).edit().putInt(KEY_SAVED, Math.max(0, savedVolume)).commit(); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "remember alarm volume failed", e); // modified
        }
    }

    /** 恢复成功后调用，清除标记。 */
    static void clear(Context context) { // modified: 新增
        try { // modified
            prefs(context).edit().remove(KEY_SAVED).commit(); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "clear alarm volume mark failed", e); // modified
        }
    }

    /**
     * 若上一次播放没走完正常收尾（进程被杀），立刻把系统闹钟音量还原。
     * 在 MainActivity.onCreate 与 SMSForegroundService.onCreate 都调用，双保险。
     */
    static void restoreIfStale(Context context) { // modified: 新增
        SharedPreferences p; // modified
        int saved; // modified
        try { // modified
            p = prefs(context); // modified
            saved = p.getInt(KEY_SAVED, NO_VALUE); // modified
        } catch (Exception e) { // modified
            return;
        }
        if (saved == NO_VALUE) { // modified
            return;
        }
        try { // modified
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); // modified
            if (am != null) { // modified
                am.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0); // modified
                Log.i(TAG, "restored alarm volume left by killed process: " + saved); // modified
            }
        } catch (Exception e) { // modified
            Log.w(TAG, "restore stale alarm volume failed", e); // modified
        } finally { // modified
            // modified: 无论恢复成功与否都清标记，避免每次启动都写一次系统设置
            try { // modified
                p.edit().remove(KEY_SAVED).commit(); // modified
            } catch (Exception ignored) { // modified
                // 已尽力恢复，忽略清除失败
            }
        }
    }
}
```

### 6.4 `app/src/main/java\com\saiga\find\messagefinder\AlertActionReceiver.java`（主源码）

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

### 6.5 `app/src/main/java\com\saiga\find\messagefinder\AlertActivity.java`（主源码）

```java
package com.saiga.find.messagefinder;

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
    /** modified: 【v4.2】记录本实例已经为哪一輪启动过服务，用于 onNewIntent 去重。 */
    private int lastStartedRound = -1; // modified

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
        // modified: 【独立复审修正】原实现无条件调用。屏幕旋转等配置变更会重建本页，
        // 重建后再次 startForegroundService 会让服务进入新一轮 handleStartAlert，
        // 表现就是「手机一转，正在响的闹铃从头开始」。只有首次创建才需要启动。
        if (savedInstanceState == null) {
            lastStartedRound = AlertSession.get(this).getRound(); // modified: 登记已启动轮次
            startAlertService(); // modified: 用户可见状态下启动前台服务，属于系统豁免
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop + 全屏 Intent 重复触发时复用本实例，只需刷新内容
        setIntent(intent);
        bindContent(intent);
        // modified: 【v4.2 独立复审】原实现无条件 startAlertService()。
        // 全屏告警页已显示、铃声正在响时，用户点通知横幅/正文会走 onNewIntent，
        // 服务因此重新 handleStartAlert：取消本轮定时器、reset MediaPlayer、从头播放，
        // 反复点通知就能让这一轮永远响不完。只有轮次真的变了才需要重新启动。
        int incomingRound = AlertSession.get(this).getRound(); // modified
        if (incomingRound != lastStartedRound) { // modified
            lastStartedRound = incomingRound; // modified
            startAlertService(); // modified: 仅新一轮重启
        } else { // modified
            Log.d(TAG, "same round re-delivered, keep current playback"); // modified
        }
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
                // modified: 【独立复审修正】原实现还调用了
                // keyguardManager.requestDismissKeyguard(this, null)。
                // 该 API 会弹出「解锁以继续」的凭据界面，与「必须点停止才能继续用手机」
                // 的设计目标正好相反，AOSP 系统闹钟也不这么做——它只用
                // setShowWhenLocked + setTurnScreenOn，把页面盖在锁屏之上而不解绑锁屏。
                // 因此这里移除该调用（manifest 同时补了 showWhenLocked 属性兜底部分 ROM）。
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

### 6.6 `app/src/main/java\com\saiga\find\messagefinder\AlertDispatcher.java`（主源码）

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

    // modified: 【v4.2】一次会话状态迁移是「cancel 闹钟 -> 改会话 -> 发通知 -> 挂新闹钟」
    // 的长链，原来各入口完全无锁。短信广播与闹钟广播并发时会出现：A 读到旧会话、
    // B 开启新会话、A 再把新会话的 round 改成旧轮次，并用 FLAG_UPDATE_CURRENT
    // 覆写同一个 PendingIntent，导致某一轮闹钟丢失或错轮。
    // 所有 public 入口都声明 synchronized（静态方法锁 AlertDispatcher.class，共享同一把锁），
    // 保证整条迁移不可分割；调用链内部没有回环，不会死锁。

    private AlertDispatcher() {
        // 工具类不允许实例化
    }

    /**
     * 收到一条命中规则的短信：开启新会话并发出第一轮提醒。
     */
    public static synchronized void onSmsMatched(Context context, String sender, String keyword, // modified
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
    public static synchronized void onRepeatAlarm(Context context, int round) { // modified: 新增
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
            // modified: 【v4.2】用户在这轮等待期间把「重复间隔」改成 0 或关掉响铃时，
            // 已注册进 AlarmManager 的闹钟不会自动撤销，到点仍会触发。原实现不复核配置，
            // 于是「我明明关了重复，它又响了一轮」。这里用实时配置再判一次。
            //
            // 但有一类例外必须放行：用户主动点的「稍后提醒」。AppConfig 里 interval=0 时
            // snooze 会用兜底间隔挂闹钟，若无条件复核就会把用户明确要求的这一轮也拦掉，
            // 属于本次修复自己引入的回归。故用一次性标记区分「自动重复」与「用户主动」。
            boolean userSnoozed = session.consumeSnoozed(); // modified: 区分来源
            if (!userSnoozed) { // modified
                AppConfig config = AppConfig.get(app); // modified
                if (!RoundPolicy.hasNextRound(Math.max(1, round - 1), total, // modified
                        config.getRingDurationSec(), config.getRepeatIntervalMin())) { // modified
                    Log.i(TAG, "repeat disabled by current config, finish session"); // modified
                    finish(app, Math.max(1, round - 1)); // modified
                    return;
                }
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
    public static synchronized void stop(Context context) { // modified: 新增
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
    public static synchronized void snooze(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, true);
    }

    /**
     * 供服务自身处理 v3 残留通知的「稍后提醒」时使用。
     * 服务已经自行停止，不需要（也不应该）再给自己发一次 startService，
     * 否则 stopSelf 之后又收到启动请求会触发一次无意义的 onCreate / onDestroy。
     */
    public static synchronized void snoozeFromService(Context context, int delayMinutes) { // modified: 新增
        snoozeInternal(context, delayMinutes, false);
    }

    private static synchronized void snoozeInternal(Context context, int delayMinutes, // modified
                                       boolean stopService) { // modified: 新增
        if (context == null) {
            return;
        }
        try {
            Context app = app(context);
            final int safeDelay = Math.max(1, delayMinutes);
            AlertSession session = AlertSession.get(app);
            // modified: 【v4.2 独立复审】原实现无条件 setActive(true)。
            // 「已结束」通知也带「稍后提醒」按钮，用户点一下就会把 stop() 过的死会话
            // 重新激活并挂新闹钟，状态机出现「死会话复活」。这里先校验。
            if (!session.isActive()) { // modified
                Log.i(TAG, "session already finished, ignore snooze"); // modified
                return;
            }
            AlertScheduler.cancel(app); // modified
            int nextRound = session.getRound() + 1;
            session.ensureRound(nextRound);
            AlertScheduler.scheduleNext(app, safeDelay, nextRound);
            session.markSnoozed(); // modified: 【v4.2】标记下一轮不受配置复核影响
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
    public static synchronized void finish(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, true);
    }

    /** 服务自己收尾时调用，避免给自己发 STOP 造成无意义的重启。 */
    public static synchronized void finishFromService(Context context, int rounds) { // modified: 新增
        finishInternal(context, rounds, false);
    }

    private static synchronized void finishInternal(Context context, int rounds, // modified
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
    public static synchronized void markWaiting(Context context, int finishedRound, // modified
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
        // modified: 【独立复审修正】只有这里传 true。全屏 Intent 是「叫人」的手段，
        // 只应出现在真正要提醒的那一轮；状态更新（等待中/已稍后/已结束）绝不能带，
        // 否则用户明明已经点过「停止」，锁屏却又弹出一个「提醒已结束」的全屏页。
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_AUDIBLE,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status, true);
        Log.d(TAG, "posted alert notification, round=" + round);
    }

    /** 发一条静音渠道的通知（只更新文案，不再出声，也不弹全屏页）。 */
    private static void postQuiet(Context app, CharSequence status) { // modified: 新增
        AlertSession session = AlertSession.get(app);
        // modified: 【v4.2】状态通知（等待中 / 已稍后 / 已结束）不挂操作按钮，
        // 避免用户从「已结束」通知上把会话复活；划掉也不再终止会话。
        NotificationHelper.post(app, NotificationHelper.CHANNEL_ID_ALERT,
                session.getFrom(), session.getKeyword(), session.getPerson(),
                session.getBody(), status, false, false); // modified
    }

    /**
     * 让正在播放的服务停止。
     *
     * <p>正常情况下服务此时就在前台运行，应用处于「有前台服务」状态，
     * 追加一个 action 是允许的，服务会走 {@code handleLocalStop()} 优雅收尾
     * （归还音量、释放 MediaPlayer、停止震动、保留通知）。
     *
     * <p>【独立复审修正】原实现只做 {@code startService} 并吞掉异常，存在一个真实死局：
     * 用户把「单轮响铃时长」设为「一直响到手动停止」时，{@code endRound()} 永远不会被调用，
     * 一旦这个 {@code startService} 被系统拒绝（部分 ROM 在应用完全后台时会拒），
     * 铃声就会**无限响下去**，只能强杀进程。
     * 因此这里补一条兜底：{@code stopService} 不受后台启动限制，必定能终止服务，
     * 而 {@code onDestroy()} 里已经做了完整的资源清理（音量、MediaPlayer、震动、WakeLock）。
     */
    private static void requestServiceStop(Context app) { // modified: 增加 stopService 兜底
        // modified: 【v4.2 独立复审】服务已经 stopSelf 之后，startService 会把它
        // 重新创建一次（onCreate -> ACTION_STOP -> stopSelf -> onDestroy），纯属空转；
        // 且 Android 8+ 在应用完全后台时会直接拒绝。只在服务确实存活时发 STOP action。
        if (!SMSForegroundService.isRunning()) { // modified
            Log.d(TAG, "alert service not running, skip STOP action"); // modified
            return;
        }
        try {
            Intent intent = new Intent(app, SMSForegroundService.class);
            intent.setAction(SMSForegroundService.ACTION_STOP_ALERT);
            app.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "graceful stop rejected, fall back to stopService", e);
            try {
                // 兜底路径：直接停止服务。不受 Android 8+ 后台启动限制影响。
                app.stopService(new Intent(app, SMSForegroundService.class));
            } catch (Exception stopError) {
                Log.e(TAG, "stopService also failed", stopError);
            }
        }
    }

    private static Context app(Context context) {
        return context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
    }
}
```

### 6.7 `app/src/main/java\com\saiga\find\messagefinder\AlertScheduler.java`（主源码）

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
        // modified: 【独立复审修正】原实现直接 context.getApplicationContext()，
        // 返回值理论上可能为 null（测试桩 / 极端 Context 包装），
        // 而后续 getSystemService 在 try 之外，NPE 不会被降级逻辑接住。
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
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
            Context app = context.getApplicationContext() != null
                    ? context.getApplicationContext() : context;
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

### 6.8 `app/src/main/java\com\saiga\find\messagefinder\AlertSession.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4 架构重构：一次「提醒会话」的共享状态。
// 为什么需要它：targetSdk 36 下短信广播不能再启动前台服务，主链路改为
// 「通知 -> 全屏页 -> 单轮服务 -> 精确闹钟 -> 再发通知」。这些组件可能分属不同进程、
// 也可能在闹钟到来时进程已被回收，轮次与短信内容必须持久化，不能放在服务内存里。
//
// 设计参考：Android 官方《Background work / Schedule alarms》中「把任务状态与
// 触发者解耦」的做法，以及系统闹钟应用 Clock 用 SharedPreferences 记录下一次响铃状态的思路。
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
    /** modified: 【v4.2】标记下一轮由用户主动「稍后提醒」触发，不参与配置复核。 */
    private static final String KEY_SNOOZED = "snoozed_round"; // modified

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
                .remove(KEY_SNOOZED) // modified: 新会话清掉上一轮的 snooze 标记
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

    /** 标记「下一轮是用户主动稍后提醒要的」，即使配置改成不重复也必须响。 */
    public void markSnoozed() { // modified: v4.2 新增
        prefs.edit().putBoolean(KEY_SNOOZED, true).commit(); // modified
    }

    /** 读取并一次性消费该标记。 */
    public boolean consumeSnoozed() { // modified: v4.2 新增
        boolean value = prefs.getBoolean(KEY_SNOOZED, false); // modified
        if (value) { // modified
            prefs.edit().remove(KEY_SNOOZED).commit(); // modified
        }
        return value; // modified
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

### 6.9 `app/src/main/java\com\saiga\find\messagefinder\AppConfig.java`（主源码）

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
    /** modified: 【v4.2】引导完成的持久化标记，不依赖 PackageManager 组件禁用是否成功。 */
    private static final String KEY_ONBOARDING_DONE = "onboarding_done_v4"; // modified

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

    // modified: 【v4.2 独立复审】readInt() 的注释已经写明「键被写成别的类型时
    // getString 会抛 ClassCastException，导致收到短信后再也不提醒」，但防护只覆盖了
    // 3 个 int 键；getContact / getContactName / isRingEnabled / isVibrateEnabled /
    // isMatchAllEnabled / parseQuietHour / 自动联想开关全是裸调用，同样在短信链路上。
    // 这里把同一防护抽成通用读取器，覆盖所有 getString / getBoolean。
    private String safeGetString(String key, String defaultValue) { // modified: 新增
        try { // modified
            return configPrefs.getString(key, defaultValue); // modified
        } catch (ClassCastException e) { // modified
            return defaultValue; // modified
        }
    }

    private boolean safeGetBoolean(String key, boolean defaultValue) { // modified: 新增
        return safeGetBoolean(configPrefs, key, defaultValue); // modified
    }

    private static boolean safeGetBoolean(SharedPreferences prefs, String key, // modified
                                          boolean defaultValue) { // modified: 新增
        try { // modified
            return prefs.getBoolean(key, defaultValue); // modified
        } catch (ClassCastException e) { // modified
            return defaultValue; // modified
        }
    }

    /**
     * @return 用户配置的号码，未配置时返回空串（永不为 null，调用方无需判空）
     */
    public String getContact() { // modified: null -> 空串，消除调用侧 NPE
        String value = safeGetString(KEY_CONTACT, null); // modified: 类型不匹配兜底
        return value == null ? "" : value.trim();
    }

    /**
     * @return 用户配置的联系人姓名，未配置时返回空串
     */
    public String getContactName() {
        String value = safeGetString(KEY_CONTACT_NAME, null); // modified: 类型不匹配兜底
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
            // modified: 【v4.2 独立复审】原实现 catch 后直接 stored = null，
            // 把「旧版本把 message 存成逗号分隔 String」当成「用户从未配置」，
            // 返回空集合后 SMSReceiver 直接丢弃每一条短信：关键词静默全丢、
            // 界面还显示「未配置」，用户没有任何线索。这里做一次性迁移。
            stored = migrateLegacyKeywords(); // modified
        }
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(stored));
    }

    /**
     * 把旧版以字符串保存的关键词迁移成 Set。
     *
     * @return 迁移后的集合；无法迁移返回 null（由调用方按「未配置」处理）
     */
    private Set<String> migrateLegacyKeywords() { // modified: v4.2 新增
        try { // modified
            String legacy = configPrefs.getString(KEY_KEYWORDS, null); // modified
            if (legacy == null || legacy.trim().isEmpty()) { // modified
                return null; // modified
            }
            List<String> parsed = KeywordMatcher.parseKeywords(legacy); // modified
            if (parsed.isEmpty()) { // modified
                return null; // modified
            }
            Set<String> migrated = new LinkedHashSet<>(parsed); // modified
            // modified: 用 commit 同步落盘，避免下次读仍触发迁移；传新 Set 避免污染缓存
            configPrefs.edit().putStringSet(KEY_KEYWORDS, new LinkedHashSet<>(migrated)).commit(); // modified
            return migrated; // modified
        } catch (Exception e) { // modified
            // 键存的既不是 Set 也不是 String（例如 Integer）：确实无法恢复
            return null; // modified
        }
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
        return safeGetBoolean(featurePrefs, KEY_AUTO_SUGGESTION_DISABLED, true); // modified: 类型兜底
    }

    /**
     * 设置自动联想开关。
     *
     * @param disabled true 表示关闭自动联想（改用系统通讯录选择器）
     */
    public void setAutoSuggestionDisabled(boolean disabled) {
        featurePrefs.edit().putBoolean(KEY_AUTO_SUGGESTION_DISABLED, disabled).apply();
    }

    /** @return 用户是否已经走完首次引导 */
    public boolean isOnboardingDone() { // modified: v4.2 新增
        return safeGetBoolean(featurePrefs, KEY_ONBOARDING_DONE, false); // modified
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
        featurePrefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).commit(); // modified
    }

    // ==================== 二次开发新增：提醒方式与重复策略 ====================

    /**
     * @return 是否响铃（默认 true）
     */
    public boolean isRingEnabled() { // modified: 新增
        return safeGetBoolean(KEY_RING_ENABLED, true); // modified: 类型兜底
    }

    /**
     * @return 是否震动（默认 true）
     */
    public boolean isVibrateEnabled() { // modified: 新增
        return safeGetBoolean(KEY_VIBRATE_ENABLED, true); // modified: 类型兜底
    }

    /**
     * @return 单次响铃时长（秒）；0 表示一直响到用户手动停止
     */
    public int getRingDurationSec() { // modified: 新增
        // modified: 【v4.2】另两个 int 读取器都做了范围钳制，唯独这里没有。
        // 脏配置写入 999999 会让前台服务与 WakeLock 挂近 12 天；负值会立即结束本轮。
        // 与 arrays.xml 的合法值域 {0,30,60,120} 对齐，上限取 120。
        int value = readInt(KEY_RING_DURATION, DEFAULT_RING_DURATION_SEC); // modified
        return Math.max(0, Math.min(value, 120)); // modified
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
        // modified: 【独立复审修正】原实现把 getString 放在 try 之外，
        // 一旦该键被写成 int/boolean（旧版本迁移、手工改 prefs、其它分支 putInt），
        // getString 自身就会抛 ClassCastException——而它是「收到短信」链路上的调用，
        // 异常会一路冒到广播接收器，表现为「配置里改过设置之后再也不提醒」。
        // 因此把类型不匹配的兜底提到最外层，任何异常都退化为默认值。
        String raw;
        try {
            raw = configPrefs.getString(key, null);
        } catch (ClassCastException e) {
            // 该键存的是非字符串类型，按整型再读一次；仍失败则用默认值
            try {
                return configPrefs.getInt(key, defaultValue);
            } catch (ClassCastException intError) {
                return defaultValue;
            }
        }
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
        return safeGetBoolean(KEY_MATCH_ALL, false); // modified: 类型兜底
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
        // modified: 【v4.2】显式声明「按设备本地时区、与语言无关」，避免隐式依赖
        // 默认 Locale 造成跨区域行为差异；DST 跳变的影响见审计报告风险章节。
        int hour = java.util.Calendar.getInstance( // modified
                java.util.TimeZone.getDefault(), java.util.Locale.ROOT) // modified
                .get(java.util.Calendar.HOUR_OF_DAY); // modified
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
        String raw = safeGetString(KEY_QUIET_HOURS, DEFAULT_QUIET_HOURS); // modified: 类型兜底
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
            // modified: 【v4.2】原实现把 99 / 25 这类越界值 normalizeHour 成 3 / 1，
            // 等于把一份脏配置静默改写成「每天凌晨 3 点不响铃」，与类注释承诺的
            // 「无法解析的值按未启用处理，避免永远不响铃」相反。越界一律返回 -1。
            return hour >= 0 && hour <= 23 ? hour : -1; // modified
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
```

### 6.10 `app/src/main/java\com\saiga\find\messagefinder\BootReceiver.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件 / modified] v4.2：设备重启 / 应用更新后恢复未处理完的提醒会话。
//
// 独立复审发现的确定性缺口：重复提醒完全依赖 AlarmManager.setAlarmClock()，
// 而系统重启会清空所有闹钟。会话状态（active/round/内容）虽然落了盘，
// 但没有任何组件在开机后被唤醒去重新注册，也没有记录「下一次该什么时候响」。
// 结果是：用户设了「一直提醒到处理」，中途重启手机后提醒彻底静默失效，
// 且 active=true 会一直残留到下一条新短信才被覆盖。
//
// 做法参考 Android 官方《Schedule alarms / 重启后重新注册闹钟》与系统闹钟
// (DeskClock) 的 BOOT_COMPLETED 处理思路：开机后读持久化状态并重建闹钟。
// 只借鉴做法，未复制第三方代码。
//
// 注意：这里**只发通知**（沿用现有主链路），不启动前台服务 ——
// targetSdk 31+ 对后台启动 FGS 有限制；通知的全屏 Intent 会按既有路径拉起告警页。

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver { // modified: 新增

    private static final String TAG = "BootReceiver"; // modified: 新增

    @Override
    public void onReceive(Context context, Intent intent) { // modified: 新增
        if (context == null || intent == null) { // modified
            return;
        }
        String action = intent.getAction(); // modified
        // modified: MY_PACKAGE_REPLACED 同样会清掉本应用的闹钟（覆盖安装 / 应用更新）
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) // modified
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) { // modified
            return;
        }
        try { // modified
            Context app = context.getApplicationContext() != null // modified
                    ? context.getApplicationContext() : context; // modified
            AlertSession session = AlertSession.get(app); // modified
            if (!session.isActive()) { // modified
                Log.d(TAG, "no active alert session, nothing to restore"); // modified
                return;
            }
            // modified: 没有持久化「下一次触发时刻」，无法精确补算剩余时间；
            // 直接补发下一轮通知（内含闹铃音 + 全屏 Intent），再由调度器挂好后续闹钟。
            // onRepeatAlarm 内部会用实时配置与总轮次复核，超限则自动结束会话。
            int nextRound = session.getRound() + 1; // modified
            AlertScheduler.cancel(app); // modified
            AlertDispatcher.onRepeatAlarm(app, nextRound); // modified
            Log.i(TAG, "restored pending alert after " + action + ", round=" + nextRound); // modified
        } catch (Exception e) { // modified
            // 广播接收器抛异常会弹「应用已停止运行」
            Log.e(TAG, "restore alert session failed", e); // modified
        }
    }
}
```

### 6.11 `app/src/main/java\com\saiga\find\messagefinder\EdgeToEdgeHelper.java`（主源码）

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

### 6.12 `app/src/main/java\com\saiga\find\messagefinder\HitLog.java`（主源码）

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
            // modified: 【v4.2】apply() 只更新内存、把磁盘写入排进异步队列；
            // AOSP 只在 Activity/Service stop 时等待落盘，BroadcastReceiver 返回后
            // 进程随时可被回收。这里是唯一落在短信广播链路上的写操作，
            // 改 commit() 同步落盘（20 条短文本，开销可忽略）。
            prefs.edit().putString(KEY_HIT_LOG, TextUtils.join(LINE_SEPARATOR, lines)).commit(); // modified
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
        // modified: 【v4.2】原实现只有 add() 加锁，clear()/dump() 裸读写；
        // 「点清空」与「来短信」并发时，add() 可能把清空前的旧记录写回，清空失效。
        synchronized (LOCK) { // modified
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context); // modified
            String stored = prefs.getString(KEY_HIT_LOG, ""); // modified
            return stored == null ? "" : stored; // modified
        }
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
        synchronized (LOCK) { // modified: 【v4.2】与 add()/dump() 共用同一把锁
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context); // modified
            prefs.edit().remove(KEY_HIT_LOG).commit(); // modified: 同步落盘
        }
    }

    private static String format(String sender, String keyword, String body) {
        String time = new SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(new Date());
        // modified: 【v4.2】原实现只对 body 做单行化，sender / keyword 仅 trim()。
        // 畸形 SP 号或历史关键词里含换行时，一条记录会被 stored.split("\n")
        // 拆成多行，20 条环形缓冲被迅速挤空，设置页里字段也错位。
        String safeSender = singleLine(sender, 24); // modified
        String safeKeyword = singleLine(keyword, 30); // modified
        if (safeSender.isEmpty()) { // modified
            safeSender = "未知号码"; // modified
        }
        if (safeKeyword.isEmpty()) { // modified
            safeKeyword = "-"; // modified
        }
        return time + FIELD_SEPARATOR + safeSender + FIELD_SEPARATOR + "[" + safeKeyword + "] "
                + singleLine(body, SNIPPET_MAX_LENGTH); // modified
    }

    /** 单行化 + 码点安全截断。所有写入记录的字段都必须经过这里。 */
    private static String singleLine(String value, int maxLength) { // modified: v4.2 重写
        if (value == null) { // modified
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim(); // modified
        if (text.codePointCount(0, text.length()) <= maxLength) { // modified
            return text; // modified
        }
        // modified: 按 code point 截，避免切断 emoji 的 surrogate pair 写入非法 UTF-16
        return text.substring(0, text.offsetByCodePoints(0, maxLength)) + "…"; // modified
    }
}
```

### 6.13 `app/src/main/java\com\saiga\find\messagefinder\KeywordMatcher.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件] 关键词解析与匹配的唯一入口。
// 设计参考：pppscn/SmsForwarder（https://github.com/pppscn/SmsForwarder）的“规则引擎”分层思路——
// 把“文本归一化 / 规则解析 / 规则匹配”从广播接收器中抽成独立、可单测的纯 Java 类，
// 只借用其分层思想与归一化做法，未复制其代码；本项目的匹配语义在原“任意关键词包含”的基础上，
// 新增“全部关键词包含”模式（对齐短信铃铛的规则设置），默认行为与原版完全一致。
// 之所以抽出来：原实现把拆分与匹配逻辑直接写在 SMSReceiver / MainActivity 里，
// 存在中文关键词拆分为空串、全角标点不兼容、无法单元测试三个问题。

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern; // modified: 【v4.2】预编译分隔符，避免每次 split 都 compile

/**
 * 关键词归一化与匹配工具。
 *
 * <p>归一化规则（关键字与短信正文走同一套规则，保证两侧可比）：
 * <ol>
 *   <li>全角 ASCII（！-～，U+FF01-U+FF5E）转半角，全角数字同样处理；</li>
 *   <li>全角空格 U+3000、不间断空格 U+00A0 统一成普通空格；</li>
 *   <li>去掉首尾空白；</li>
 *   <li>统一转小写（{@link Locale#ROOT}，避免土耳其语等区域的 i/ı 问题）。</li>
 * </ol>
 *
 * <p>该类不依赖任何 Android API，可直接用 JVM 单元测试覆盖（见 KeywordMatcherTest）。
 */
public final class KeywordMatcher {

    /** 单条关键词最大长度，超出截断，避免用户粘贴长文本把匹配变成“几乎全命中”。 */
    public static final int MAX_KEYWORD_LENGTH = 30;
    /** 关键词数量上限，防止误粘贴整段文本导致匹配开销与存储膨胀。 */
    public static final int MAX_KEYWORDS = 50;

    /** 分隔符：逗号、分号、空白（归一化后全角与 CJK 标点已变半角，这里只需处理半角）。 */
    private static final Pattern SEPARATOR_PATTERN = // modified: 由 String 改为预编译 Pattern
            Pattern.compile("[,;\\s]+"); // modified

    private KeywordMatcher() {
        // 工具类不允许实例化
    }

    /**
     * 文本归一化：全角转半角、统一空格、去首尾空白、转小写。
     *
     * @param raw 原始文本，允许为 null
     * @return 归一化后的文本，永不为 null（入参为 null 时返回空串）
     */
    public static String normalize(String raw) { // modified: 新增统一归一化入口
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            // modified: 【v4.2】中文用户高频输入「报警、求助」，顿号 U+3001 /
            // 句号 U+3002 原本被原样保留，整串变成一个永不命中的关键词；
            // 从微信/网页复制粘贴带进来的零宽空格 U+200B、BOM U+FEFF 同理。
            // 类注释本就宣称要统一 Unicode 空白，这里把规则补全。
            if (ch == '\u3001' || ch == '\u3002' || ch == '\uFF0E') { // modified
                builder.append(',');       // CJK 标点 -> 分隔符 // modified
            } else if (ch == '\u200B' || ch == '\uFEFF' || ch == '\u2060' // modified
                    || ch == '\u200E' || ch == '\u200F') { // modified
                builder.append(' ');       // 零宽字符 / BOM -> 分隔符 // modified
            } else if (ch == '\u3000' || ch == '\u00A0' // modified
                    || Character.isSpaceChar(ch) || Character.isWhitespace(ch)) { // modified
                // 全角空格 / 不间断空格 / 各类 Unicode 排版空格 -> 普通空格
                builder.append(' ');
            } else if (ch >= '\uFF01' && ch <= '\uFF5E') {
                // 全角 ASCII -> 半角 ASCII（含全角逗号、全角数字、全角字母）
                builder.append((char) (ch - 0xFEE0));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析用户输入的关键词串。支持半角/全角逗号、分号、空格分隔，自动去重、去空、截断。
     *
     * <p>原实现 {@code messageStr.split("\\s*,\\s*|\\s+")} 的缺陷：
     * 中文用户习惯输入全角逗号“，”，split 不认，整串会变成一个永远匹配不到的关键词。
     *
     * @param raw 用户输入，允许为 null
     * @return 去重后的关键词列表（保持用户输入顺序），永不为 null
     */
    public static List<String> parseKeywords(String raw) { // modified: 全角分隔符兼容 + 去重 + 上限保护
        List<String> result = new ArrayList<>();
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return result;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        String[] parts = SEPARATOR_PATTERN.split(normalized); // modified: 复用预编译 Pattern
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String keyword = part.trim();
            if (keyword.isEmpty()) {
                continue;
            }
            // modified: 【v4.2】原实现按 UTF-16 char 截断，emoji / 增补平面汉字
            // 正好跨在边界上时会留下孤立代理项，写进 SharedPreferences 的 XML 后
            // 显示成乱码，部分 OEM 的 XML 编码器甚至会抛异常。改按 code point 计。
            if (keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) { // modified
                keyword = keyword.substring(0, keyword.offsetByCodePoints(0, MAX_KEYWORD_LENGTH)); // modified
            }
            unique.add(keyword);
            if (unique.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        result.addAll(unique);
        return result;
    }

    /**
     * 在短信正文中查找第一个命中的关键词（“任意命中”语义）。
     *
     * @param keywords 已归一化的关键词集合（{@link #parseKeywords} 的产物，或历史版本遗留的小写集合）
     * @param body     短信正文，允许为 null
     * @return 命中的关键词（原样返回集合中的元素，便于在通知与日志里展示）；未命中返回 null
     */
    public static String findMatch(Collection<String> keywords, String body) {
        return findMatch(keywords, body, false); // modified: 保留原签名，默认“任意命中”
    }

    /**
     * 在短信正文中查找命中的关键词，支持“任意命中 / 全部命中”两种模式。
     *
     * <p>为什么要支持“全部命中”：参照短信铃铛的规则设置，只写“到账”会把所有广告都命中，
     * 而“到账 + 银行名”能把误报压到几乎为零。这是“提醒太吵”与“提醒太松”之间最实用的旋钮。
     *
     * @param keywords 已归一化的关键词集合
     * @param body     短信正文，允许为 null
     * @param matchAll true 表示要求**全部**关键词都出现；false 表示任意一个出现即可
     * @return 命中的关键词（“全部命中”时返回集合中的第一个，仅用于展示）；未命中返回 null
     */
    public static String findMatch(Collection<String> keywords, String body,
                                   boolean matchAll) { // modified: 新增，支持“全部命中”
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        String normalizedBody = normalize(body);
        if (normalizedBody.isEmpty()) {
            return null;
        }
        String firstHit = null;
        int effectiveKeywords = 0;
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            // 兼容历史数据：老版本手工写入的关键词可能未归一化（含大写/全角）
            String normalizedKeyword = normalize(keyword);
            if (normalizedKeyword.isEmpty()) {
                continue;
            }
            effectiveKeywords++;
            boolean hit = normalizedBody.contains(normalizedKeyword);
            if (hit) {
                if (firstHit == null) {
                    firstHit = keyword;
                }
                if (!matchAll) {
                    return keyword;
                }
            } else if (matchAll) {
                // 全部命中模式：只要缺一个就可以立刻判定不命中
                return null;
            }
        }
        if (matchAll && effectiveKeywords > 0 && firstHit != null) {
            return firstHit;
        }
        return null;
    }
}
```

### 6.14 `app/src/main/java\com\saiga\find\messagefinder\MainActivity.java`（主源码）

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
    private Button clearButton;
    private CoordinatorLayout userMessageLayout;
    private TextInputLayout contactNumberLayout;

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
        super.onResume(); // modified: 【独立复审修正】原实现把 super.onResume() 放在最后，不符合生命周期约定
        // 让根布局先拿到焦点，避免横竖屏切换时输入法自动弹出
        View root = findViewById(R.id.ui_layout);
        if (root != null) {
            root.requestFocus();
        }
        updateStatusUi(); // modified: 从设置页返回时同步状态
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

        EditText contactView = findEditText(R.id.contact_value);
        EditText messageView = findEditText(R.id.message_value);
        // modified: 【独立复审修正】原实现直接调用 getText().clear()，
        // findViewById 返回 null（布局被裁剪 / 换肤替换控件）时会 NPE。
        if (messageView != null && messageView.getText() != null) {
            messageView.getText().clear();
        }
        if (contactView != null && contactView.getText() != null) {
            contactView.getText().clear();
            contactView.clearFocus();
        }
        if (messageView != null) {
            messageView.clearFocus();
        }

        clearButton.setText(R.string.cleared);
        trigButton.setText(R.string.trigger_button);
        setTriggerEnabled(true);
        updateStatusUi(); // modified
    }

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
        // modified: 【v4.2】同文件其它弹窗都有 isFinishing 校验，唯独 Snackbar 没有。
        // 已 finish 的 Activity 上 userMessageLayout 仍非 null，会继续执行后续保存逻辑。
        if (isFinishing() || isDestroyed()) { // modified
            return;
        }
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
```

### 6.15 `app/src/main/java\com\saiga\find\messagefinder\MyDialogFragment.java`（主源码）

```java
package com.saiga.find.messagefinder;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
// modified: 【独立复审修正】原 import 是 android.app.AlertDialog（平台实现），
// 却传入 R.style.MyDialogTheme（parent = Theme.AppCompat.*）。平台 AlertDialog 在
// AppCompat 主题下样式不生效，个别 ROM 还会因 LayoutInflater Factory 冲突抛
// IllegalStateException。与 MainActivity 统一改用 androidx 版本。
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

/**
 * 自动联想功能的授权说明框。
 *
 * <p>修改要点：原实现对宿主 Activity 做硬类型转换
 * {@code (MainActivity) requireActivity()}，当 Activity 已销毁/重建（旋转屏幕、进程回收后恢复）
 * 时会抛 ClassCastException 或 IllegalStateException。这里统一改为
 * {@code instanceof} 判断 + 安全取用，拿不到宿主就直接关闭对话框。
 */
public class MyDialogFragment extends DialogFragment {

    private static final String TAG = "AutoSuggestionDialog";

    public MyDialogFragment() {
        // 空构造方法：Fragment 重建时系统需要
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity(), R.style.MyDialogTheme);
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        builder.setView(inflater.inflate(R.layout.dialog_layout, null));
        builder.setPositiveButton(R.string.dialog_set, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.requestContactPermission();
                }
            }
        });
        builder.setNegativeButton(R.string.dialog_dismiss, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 用户选择“关闭”：后续改用系统通讯录选择器，不再弹本对话框
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.setPopup(true);
                }
            }
        });
        builder.setNeutralButton(R.string.dialog_dont_show, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 用户选择“不再显示”：持久化关闭自动联想
                MainActivity activity = getMainActivity();
                if (activity != null) {
                    activity.setUserConfig(true);
                }
            }
        });
        return builder.create();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        MainActivity activity = getMainActivity();
        if (activity != null) {
            activity.setPopup(false);
        }
    }

    /**
     * 安全获取宿主 Activity。
     *
     * @return 宿主是 MainActivity 时返回它，否则返回 null（不抛异常）
     */
    @Nullable
    private MainActivity getMainActivity() { // modified: 用 instanceof 取代硬类型转换
        Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            return (MainActivity) activity;
        }
        Log.w(TAG, "host activity is not MainActivity, ignore callback");
        return null;
    }
}
```

### 6.16 `app/src/main/java\com\saiga\find\messagefinder\NotificationHelper.java`（主源码）

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
import androidx.core.content.ContextCompat;

/** 优先级提醒通知构建器。 */
public final class NotificationHelper {

    private static final String TAG = "NotificationHelper";

    /**
     * 静音渠道：服务已接手播放铃声时使用，防止双重响铃。
     *
     * <p>【独立复审修正】原实现沿用 v3 的 {@code priority_alert_v2}，这是错的：
     * 通知渠道的声音属性**创建后不可修改**，而 v3 是用「带提示音」创建的该渠道。
     * 老用户覆盖安装后，v4 里 {@code setSound(null, null)} 对已存在的渠道完全不生效，
     * 结果是「通知音 + MediaPlayer」双重响铃——正是本次改造要消除的问题。
     * 因此静音渠道也必须换新 ID，并在 {@link #ensureChannels} 里顺手删掉旧渠道。
     */
    public static final String CHANNEL_ID_ALERT = "priority_alert_quiet_v4"; // modified: 换新 ID，旧渠道声音不可变
    /** 带闹铃音的主提醒渠道：v4 新 ID，避免旧渠道声音不可变的历史包袱。 */
    public static final String CHANNEL_ID_AUDIBLE = "priority_alert_audible_v4"; // modified: 新增
    /** v3 遗留渠道，仅用于清理（声音属性创建后不可改，留着只会在系统设置里多出一条）。 */
    private static final String CHANNEL_ID_LEGACY_V3 = "priority_alert_v2"; // modified: 新增
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
        // modified: 【v4.2 独立复审】原实现把两个渠道的创建 + 删旧渠道全塞进一个 try。
        // 部分定制 ROM 的 setBypassDnd() 会抛异常，一旦发生在「静音渠道已创建、
        // 有声渠道未创建」之间，异常被整体吞掉，之后 postRound() 恒用有声渠道发通知，
        // 而该渠道根本不存在 —— Android 8+ 会静默丢弃通知，主链路 100% 失效且无报错。
        // 现在每个渠道独立 try，且把非关键的 setBypassDnd 单独包起来，
        // 保证 createNotificationChannel() 这一步一定有机会执行。
        createQuietChannel(manager, context);   // modified: 渠道创建相互隔离
        createAudibleChannel(manager, context); // modified: 渠道创建相互隔离
        try { // modified
            // modified: 清掉 v3 遗留的旧渠道（声音属性创建后不可改，留着只会在系统设置里多出一条）
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V3); // modified
        } catch (Exception e) { // modified
            Log.w(TAG, "delete legacy channel failed", e); // modified
        }
    }

    private static void createQuietChannel(NotificationManager manager, Context context) { // modified: 新增
        try {
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERT,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription(context.getString(R.string.channel_desc));
            alertChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alertChannel.setSound(null, null); // modified: 服务播放铃声时不出通知音
            alertChannel.enableVibration(false);
            // modified: 非关键属性单独兜底，不能让它阻断渠道创建（部分定制 ROM 会抛异常）
            tryBypassDnd(alertChannel); // modified
            manager.createNotificationChannel(alertChannel);
        } catch (Exception e) { // modified
            Log.e(TAG, "create quiet channel failed", e); // modified
        }
    }

    private static void createAudibleChannel(NotificationManager manager, Context context) { // modified: 新增
        try { // modified
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
            tryBypassDnd(audibleChannel); // modified: 同上，单独兜底
            manager.createNotificationChannel(audibleChannel);
        } catch (Exception e) { // modified
            Log.e(TAG, "create audible channel failed", e); // modified
        }
    }

    private static void tryBypassDnd(NotificationChannel channel) { // modified: 新增
        try { // modified
            channel.setBypassDnd(true); // modified
        } catch (Exception e) {
            Log.w(TAG, "setBypassDnd unsupported on this ROM", e); // modified
        }
    }

    /** 渠道是否真实存在。创建失败 / 被用户在系统设置里删掉时返回 false。 */
    private static boolean channelExists(Context context, String channelId) { // modified: 新增
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { // modified
            return true; // API 25 及以下没有渠道概念 // modified
        }
        try { // modified
            NotificationManager manager = // modified
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE); // modified
            return manager != null && manager.getNotificationChannel(channelId) != null; // modified
        } catch (Exception e) { // modified
            return false; // modified
        }
    }

    /** 构建提醒通知（不带轮次状态）。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body) {
        return build(context, channelId, sender, keyword, personName, body, null, false, true); // modified
    }

    /** 构建提醒通知，并附带一行「第几次提醒 / 几分钟后再响 / 已结束」。 */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status) {
        return build(context, channelId, sender, keyword, personName, body, status, false, true); // modified
    }

    /**
     * 构建提醒通知。
     *
     * @param withFullScreenIntent 是否附带全屏 Intent（锁屏/息屏时自动拉起告警页）。
     *                             **只有「真正要叫人」的那一轮才传 true**。
     *                             服务的前台通知、以及「等待中 / 已稍后 / 已结束」这类
     *                             纯状态更新必须传 false——否则会出现「已经点过停止，
     *                             锁屏却又弹出一个写着『提醒已结束』的全屏页」，
     *                             甚至与服务前台通知互相触发形成重复拉起。
     */
    public static Notification build(Context context, String channelId, String sender,
                                     String keyword, String personName, String body,
                                     CharSequence status,
                                     boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        return build(context, channelId, sender, keyword, personName, body, status, // modified
                withFullScreenIntent, true); // modified
    }

    /**
     * 完整构建入口。
     *
     * @param withActions 是否附带「停止 / 稍后提醒」按钮。
     *                    「等待中 / 已稍后 / 已结束」这类纯状态通知传 false ——
     *                    否则用户能从一条「已结束」通知上点「稍后提醒」，把已停止的会话复活。
     */
    public static Notification build(Context context, String channelId, String sender, // modified
                                     String keyword, String personName, String body, // modified
                                     CharSequence status, boolean withFullScreenIntent, // modified
                                     boolean withActions) { // modified: v4.2 新增动作开关
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
                // modified: 原写法 getColor(resId, null) 第二个参数传 null，
                // 在部分 ROM / API 23 上 theme.resolveAttribute 会 NPE；改用兼容入口。
                .setColor(ContextCompat.getColor(context, R.color.secondaryDarkColor))
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
        // modified: 【v4.2】minSdk 23 覆盖 API 23-25，这三代系统没有通知渠道，
        // 渠道上配的闹铃音完全不存在；渠道创建失败时同理。补 builder 级声音/震动，
        // 保证「第一声」在任何情况下都不会变成一条静音横幅。
        // API 26+ 且渠道正常时 builder 的声音会被渠道设置覆盖，不会造成双重响铃。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channelExists(context, channelId)) { // modified
            Uri legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); // modified
            if (legacySound == null) { // modified
                legacySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); // modified
            }
            builder.setSound(legacySound, AudioAttributes.USAGE_ALARM); // modified
            builder.setVibrate(new long[]{0, 400, 300, 400}); // modified
        }
        if (!TextUtils.isEmpty(status)) {
            builder.setSubText(status);
        }
        // modified: 【v4.2 独立复审】原实现给所有通知（含「已结束」状态通知）都挂按钮，
        // 且把「划掉通知」等同于「停止整个会话」。后者与项目核心需求
        // 「用户不处理就 5 分钟后再提醒」直接冲突：随手划掉横幅会取消后续全部轮次。
        // 现在：只有真正要叫人的一轮带按钮；划掉通知不再改变会话状态，
        // 想彻底停止请点明确的「停止」按钮或告警页里的停止。
        if (withActions) { // modified
            builder.addAction(R.drawable.ic_block_24dp, // modified
                    context.getString(R.string.stop), buildStopIntent(context)); // modified
            builder.addAction(0, // modified
                    context.getString(R.string.notif_snooze), buildSnoozeIntent(context)); // modified
        }

        // modified: 仅当调用方明确要求、且系统确实允许时才挂全屏 Intent。
        // 未授予时不设置，让系统退化为悬挂通知（HUN），用户点击进入告警页；
        // 强行设置不仅无效，部分 ROM 还会抛异常。
        if (withFullScreenIntent && canUseFullScreenIntent(context)) {
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
                            String person, String body, CharSequence status) {
        // 默认不带全屏 Intent：绝大多数调用都是「更新状态」而不是「叫人」。
        post(context, channelId, sender, keyword, person, body, status, false, true); // modified
    }

    /**
     * 发（或更新）通知，显式控制是否附带全屏 Intent。
     *
     * @param withFullScreenIntent true = 锁屏/息屏时自动拉起告警页（仅用于真正要提醒的那一轮）
     */
    public static void post(Context context, String channelId, String sender, String keyword,
                            String person, String body, CharSequence status,
                            boolean withFullScreenIntent) { // modified: 新增 FSI 开关
        post(context, channelId, sender, keyword, person, body, status, // modified
                withFullScreenIntent, true); // modified
    }

    /** 完整发通知入口，可同时控制全屏 Intent 与通知动作。 */
    public static void post(Context context, String channelId, String sender, String keyword, // modified
                            String person, String body, CharSequence status, // modified
                            boolean withFullScreenIntent, // modified
                            boolean withActions) { // modified: v4.2 新增动作开关
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(app);
            if (!manager.areNotificationsEnabled()) {
                // 用户关了通知权限 / 通知总开关。此时 notify() 只会静默失败，
                // 提前返回并留下日志，便于从 logcat 判断「为什么没提醒」。
                Log.w(TAG, "notifications disabled by user/system, skip posting");
                return;
            }
            ensureChannels(app);
            // modified: 【v4.2】渠道不存在时 notify() 会被系统静默丢弃且不抛异常。
            // 再建一次；仍失败则退回系统默认渠道，至少保证通知能显示出来。
            String effectiveChannel = channelId; // modified
            if (!channelExists(app, channelId)) { // modified
                ensureChannels(app); // modified
                if (!channelExists(app, channelId)) { // modified
                    effectiveChannel = NotificationChannel.DEFAULT_CHANNEL_ID; // modified
                    Log.w(TAG, "target channel missing, fall back to default channel"); // modified
                }
            }
            manager.notify(NOTIFICATION_ID,
                    build(app, effectiveChannel, sender, keyword, person, body, status, // modified
                            withFullScreenIntent, withActions)); // modified
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
        // modified: 【v4.2 独立复审】原实现这里传 withFullScreenIntent=true。
        // 它只在「播放失败 / startForeground 失败」的降级路径被调用，若再带全屏 Intent，
        // 系统会拉起 AlertActivity，而 AlertActivity 又会 startForegroundService 再失败一次，
        // 形成「失败 -> 发通知 -> 拉页 -> 再失败」的无限循环（通知刷屏、闹铃反复响一声）。
        // 它只是补一声，不需要也不应该再拉页面；动作按钮同样去掉。
        post(context, CHANNEL_ID_AUDIBLE, sender, keyword, personName, body, null, false, false); // modified
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

### 6.17 `app/src/main/java\com\saiga\find\messagefinder\Onboarding.java`（主源码）

```java
package com.saiga.find.messagefinder;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;
import androidx.viewpager.widget.ViewPager;

/**
 * 首次安装时的引导页（3 屏）。完成后把自身组件禁用，之后不再出现。
 *
 * <p>修改要点：
 * <ul>
 *   <li>指示点颜色取模，避免页数变化后数组越界（原实现 pageCount 与颜色数强绑定）；</li>
 *   <li>初始状态下“上一步”按钮隐藏（原来第一屏会显示一个不可用的“上一步”）；</li>
 *   <li>“完成”时即使组件禁用失败也不影响进入主界面。</li>
 * </ul>
 */
public class Onboarding extends AppCompatActivity {

    private static final String TAG = "Onboarding";

    private ViewPager slideView;
    private LinearLayout dotLayout;
    private SliderAdapter pageAdapter;
    private Button prevButton;
    private Button nextButton;
    private TextView[] mDots;
    /** 当前页索引。 */
    private int mCurrentPage;

    /** 每屏对应的强调色。 */
    private final int[] mDotColor = {
            R.color.lightOrangeColor, R.color.lightBlueColor, R.color.lightPinkColor
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.OnboardingTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_onboarding);
        // modified: 【v4.2 独立复审】本页是唯一没做 edge-to-edge 的 Activity。
        // targetSdk 35+ 内容默认绘制到系统导航栏之下，而 button_bar 只有 16dp 底部 padding，
        // 三键导航 / 手势导航会盖住「完成」按钮 —— 这是首次安装必经路径，
        // 被遮挡等于用户根本进不了主界面。与 MainActivity / SettingsActivity 对齐补上 insets。
        EdgeToEdgeHelper.apply(this, R.id.onboarding_root); // modified
        // modified: 进程被杀时可能残留被抬高的系统闹钟音量，任意组件启动都尝试还原一次
        AlarmVolumeGuard.restoreIfStale(this); // modified

        slideView = findViewById(R.id.slideView);
        dotLayout = findViewById(R.id.navigator_menu);
        prevButton = findViewById(R.id.PrevButton);
        nextButton = findViewById(R.id.NextButton);

        pageAdapter = new SliderAdapter(this);
        slideView.setAdapter(pageAdapter);
        addDotsIndicator();
        // 初始状态：第一屏，隐藏“上一步”
        prevButton.setVisibility(View.INVISIBLE); // modified: 修复首屏显示无效“上一步”
        nextButton.setVisibility(View.VISIBLE);
        setPosition(0);
        slideView.addOnPageChangeListener(pageListener);

        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentPage != mDots.length - 1) {
                    slideView.setCurrentItem(mCurrentPage + 1, true);
                } else {
                    finishOnboarding(v);
                }
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentPage != 0) {
                    slideView.setCurrentItem(mCurrentPage - 1);
                }
            }
        });
    }

    /**
     * 结束引导：进入主界面并禁用引导页组件。
     *
     * @param sourceView 触发按钮，用于共享元素动画
     */
    private void finishOnboarding(View sourceView) { // modified: 从匿名内部类抽出
        // modified: 【v4.2】先落盘「已完成」，再尝试禁用组件。
        // 组件禁用失败时这个标记仍能保证 MainActivity 不再把用户送回引导页。
        AppConfig.get(this).setOnboardingDone(true); // modified
        try {
            Intent launchMain = new Intent()
                    .setComponent(new ComponentName(sourceView.getContext(), MainActivity.class));
            launchMain.putExtra(MainActivity.EXTRA_FROM_ONBOARDING, true); // modified: 标记来源
            ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(Onboarding.this);
            startActivity(launchMain, options.toBundle());
        } catch (Exception e) {
            // 动画资源异常时退化为普通跳转，不能因此卡在引导页
            Log.e(TAG, "launch main activity with transition failed", e);
            Intent plain = new Intent(this, MainActivity.class); // modified
            plain.putExtra(MainActivity.EXTRA_FROM_ONBOARDING, true); // modified: 标记来源
            startActivity(plain); // modified
        }
        try {
            // 加固：禁用自身。失败也不影响（主判定已交给持久化标记）
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, Onboarding.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            Log.e(TAG, "disable onboarding component failed", e);
        }
        finish();
    }

    /** 在页面底部生成与页数一致的指示点。 */
    private void addDotsIndicator() {
        mDots = new TextView[pageAdapter.getCount()];
        for (int i = 0; i < mDots.length; i++) {
            mDots[i] = new TextView(this);
            mDots[i].setId(1000 + i);
            mDots[i].setText(HtmlCompat.fromHtml("&#9679;", HtmlCompat.FROM_HTML_MODE_COMPACT));
            mDots[i].setTextColor(getColor(R.color.primaryTextColor));
            dotLayout.addView(mDots[i]);
        }
    }

    /**
     * 更新指示点与按钮颜色。
     *
     * @param position 当前页索引
     */
    public void setPosition(int position) {
        if (mDots == null || mDots.length == 0) {
            return;
        }
        for (TextView dot : mDots) {
            dot.setTextColor(getColor(R.color.primaryTextColor));
        }
        int safePosition = Math.max(0, Math.min(position, mDots.length - 1));
        // 取模：即使未来新增页面而忘记补颜色，也不会数组越界
        int colorRes = mDotColor[safePosition % mDotColor.length]; // modified: 防数组越界
        mDots[safePosition].setTextColor(getColor(colorRes));
        nextButton.setTextColor(getColor(colorRes));
    }

    private final ViewPager.OnPageChangeListener pageListener = new ViewPager.OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            // 无需处理
        }

        @Override
        public void onPageSelected(int position) {
            mCurrentPage = position;
            setPosition(position);
            if (position == 0) {
                prevButton.setVisibility(View.INVISIBLE);
                nextButton.setVisibility(View.VISIBLE);
                nextButton.setText(R.string.next); // modified: 回到首屏时恢复“下一步”文案
            } else if (position == mDots.length - 1) {
                prevButton.setVisibility(View.VISIBLE);
                nextButton.setText(R.string.finish);
            } else {
                prevButton.setVisibility(View.VISIBLE);
                nextButton.setVisibility(View.VISIBLE);
                prevButton.setText(R.string.prev);
                nextButton.setText(R.string.next);
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            // 无需处理
        }
    };
}
```

### 6.18 `app/src/main/java\com\saiga\find\messagefinder\PermissionChecker.java`（主源码）

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

### 6.19 `app/src/main/java\com\saiga\find\messagefinder\PhoneNumbers.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件] 电话号码归一化与比对。
// 背景：原实现 spliceNumber() 把号码硬编码为“10 位”，并直接 substring(末尾 10 位)，
// 这对中国大陆 11 位手机号是致命的——"+8613800138000" 会被截成 "3800138000"，
// 与短信发送方号码永远不相等，导致“限定号码”功能在国内完全失效。
// 这里改为：去分隔符 -> 去国际区号(0086/86) -> 统一取末 11 位（中国手机号长度），比对时再兼容后缀匹配。
// 纯 Java 实现，无 Android 依赖，可用单元测试覆盖（见 PhoneNumbersTest）。

/**
 * 电话号码处理工具。
 */
public final class PhoneNumbers {

    // modified: 【v4.2】移除 MIN_SUFFIX_LENGTH（原 7 位门槛把 10086 / 95588 挡在门外）。
    // 现在由 matches() 用「较短侧 >= MIN_INPUT_LENGTH 且长度差 <= 4」统一约束。
    /** 单号码最大长度（E.164 上限 15 位）。 */
    private static final int MAX_INPUT_LENGTH = 15;
    /** 单号码最小长度（预留 5 位短号，如 10086）。 */
    private static final int MIN_INPUT_LENGTH = 5;
    /** 中国大陆手机号长度。 */
    private static final int CN_MOBILE_LENGTH = 11;

    private PhoneNumbers() {
        // 工具类不允许实例化
    }

    /**
     * 去掉空格、横线、括号等分隔符，只保留数字和首位可能存在的 "+"。
     * 同时把全角数字（０-９）转成半角数字。
     *
     * @param raw 原始号码，允许为 null
     * @return 仅含数字与可能的首位 "+"，永不为 null
     */
    public static String stripSeparators(String raw) { // modified: 新增，替代原 spliceNumber 的前半段逻辑
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                builder.append(ch);
            } else if (ch >= '\uFF10' && ch <= '\uFF19') {
                // 全角数字 -> 半角数字
                builder.append((char) (ch - 0xFEE0));
            } else if (ch == '+' && builder.length() == 0) {
                builder.append(ch);
            }
            // 其余字符（空格、-、()、*、#、字母、汉字）一律丢弃
        }
        return builder.toString();
    }

    /**
     * 号码里允许出现的“格式字符”：数字、全角数字、加号、连字符、点、半角/全角括号、
     * 空格（含不换行空格与全角空格），以及 * / #（服务号、USSD 补充符）。
     *
     * <p>不在白名单内的一律视为非法输入（字母、汉字、逗号、分机号等）。
     */
    private static boolean hasOnlyFormatChars(String raw) { // modified: 新增
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            boolean allowed = (ch >= '0' && ch <= '9')
                    || (ch >= '\uFF10' && ch <= '\uFF19')          // 全角数字
                    || ch == '+' || ch == '-' || ch == '.'
                    || ch == '(' || ch == ')' || ch == '\uFF08' || ch == '\uFF09'
                    // modified: 【v4.2】移除 '*' / '#'。它们是 USSD 补充符，
                    // stripSeparators 会直接丢弃，夹在数字中间时会把两段数字拼接起来
                    // 改写成另一个号码 —— 与 v5 修掉的「校验通过但配置被静默改写」
                    // 是同一个缺陷，只是残留了两个字符。
                    || ch == ' ' || ch == '\u00A0' || ch == '\u3000';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验用户输入的号码是否合法（空串代表“不限号码”，由调用方单独判断）。
     *
     * @param raw 用户输入
     * @return 合法返回 true；空串返回 false（调用方需先判断是否允许为空）
     */
    public static boolean isValidInput(String raw) { // modified: 由“最多 10 位”改为国际通用的 5-15 位
        // modified: 【独立复审修正】先做字符白名单校验，不能只看 stripSeparators 之后的长度。
        // 原因：stripSeparators 会静默丢弃所有非数字字符，于是
        //   "1380013800a"           -> 被当成 "1380013800" 判为合法；
        //   "138 0013 8000 转 1234" -> 被当成 15 位号码判为合法，canonical 再取末 11 位
        //                              就变成了另一个号码。
        // 而 MainActivity.saveConfig() 保存的是**用户原始输入串**，所以这属于
        // “校验通过、但配置被静默改写”：用户以为配了 A 号码，实际匹配的是 B 号码，
        // 短信永远不响且查不出原因。校验器的职责是拒绝，不是替用户改。
        if (raw == null || !hasOnlyFormatChars(raw)) {
            return false;
        }
        // modified: '+' 只允许至多一个，且必须在所有数字之前。
        // 否则 stripSeparators 会因为“不在首位就不保留”而把它丢掉，同样属于静默改写。
        boolean seenDigit = false;
        int plusCount = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '+') {
                plusCount++;
                if (seenDigit) {
                    return false;
                }
            } else if (ch >= '0' && ch <= '9') {
                seenDigit = true;
            } else if (ch >= '\uFF10' && ch <= '\uFF19') {
                seenDigit = true;
            }
        }
        if (plusCount > 1) {
            return false;
        }

        String stripped = stripSeparators(raw);
        if (stripped.isEmpty()) {
            return false;
        }
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (digits.isEmpty()) {
            return false;
        }
        // modified: 走到这里 digits 必然是纯数字（字符白名单已保证），只需校验长度
        return digits.length() >= MIN_INPUT_LENGTH && digits.length() <= MAX_INPUT_LENGTH;
    }

    /**
     * 规范化号码，用于“配置的号码”与“短信发送方号码”之间的比对。
     *
     * <p>处理内容：去分隔符、去 0086/+86 前缀、超长时取末 11 位（中国大陆手机号长度）。
     *
     * @param raw 原始号码，允许为 null
     * @return 规范化后的号码；无法解析时返回空串
     */
    public static String canonical(String raw) { // modified: 修正原实现“只留末 10 位”导致国内号码被截断的问题
        String stripped = stripSeparators(raw);
        if (stripped.isEmpty()) {
            return "";
        }
        String digits = stripped.startsWith("+") ? stripped.substring(1) : stripped;
        if (digits.startsWith("0086") && digits.length() > 4) {
            digits = digits.substring(4);
        } else if (digits.startsWith("86") && digits.length() > CN_MOBILE_LENGTH) {
            digits = digits.substring(2);
        }
        // modified: 【v4.2 独立复审】原实现无条件「超过 11 位就取末 11 位」。
        // v5 把 isValidInput 上限放宽到 E.164 的 15 位后，两个不同的 13 位号码
        // （1234567890123 / 9934567890123）都会被截成同一个末 11 位而误判为相同号码，
        // 造成误报。现在保留完整数字串；国家码/区号前缀差异交给 matches() 的后缀匹配处理。
        return digits;
    }

    /**
     * 判断短信发送方是否匹配用户配置的号码。
     *
     * <p>原实现用 {@code sender.contains(number)}：配置 "1234" 会命中 "1000012349999"，
     * 属于明显的误报来源。这里改为“完全相等，或长度足够时的后缀相等”。
     *
     * @param configured 用户配置的号码；为空表示不限号码，直接返回 true
     * @param sender     短信发送方号码
     * @return 匹配返回 true
     */
    public static boolean matches(String configured, String sender) { // modified: contains -> 精确/后缀匹配
        String configuredNumber = canonical(configured);
        if (configuredNumber.isEmpty()) {
            return true;
        }
        String senderNumber = canonical(sender);
        if (senderNumber.isEmpty()) {
            return false;
        }
        if (configuredNumber.equals(senderNumber)) {
            return true;
        }
        // modified: 【v4.2】原实现是「双向后缀 + 两侧都 >= 7 位」：
        //   ① 双向后缀让「配置 11 位、发送方 9 位且是其后缀」也命中，误报面过宽；
        //   ② 7 位门槛把 10086 / 95588 这类最常监听的服务号挡在门外，
        //      而短信侧常返回 +8610086，于是短号规则静默失效。
        // 改为：只允许「较长一侧 = 较短一侧 + 至多 4 位前缀」这一种合法情形。
        String longer = configuredNumber.length() >= senderNumber.length() // modified
                ? configuredNumber : senderNumber; // modified
        String shorter = configuredNumber.length() >= senderNumber.length() // modified
                ? senderNumber : configuredNumber; // modified
        int prefixLength = longer.length() - shorter.length(); // modified
        return shorter.length() >= MIN_INPUT_LENGTH // modified
                && prefixLength <= 4 // modified
                && longer.endsWith(shorter); // modified
    }
}
```

### 6.20 `app/src/main/java\com\saiga\find\messagefinder\QuietHours.java`（主源码）

```java
package com.saiga.find.messagefinder;

// [新增文件] 夜间静音时段（免打扰窗口）判定。
// 参照：短信铃铛的“全局静音时段（22:00 到 07:00，可开关），开启后这段时间只震动不响铃”。
// 为什么单独抽成一个纯 Java 类：
//  1) 时段判定涉及“跨零点”这种最容易写错的边界（22:00-07:00 不是 start<=h<=end），
//     放在 Service 里只能靠真机等半夜才能验证；
//  2) 抽出来之后可以用 JVM 单元测试把 0 点、起止点、全天、关闭等边界一次性覆盖。

/**
 * 夜间静音时段判定工具。
 *
 * <p>语义：在 [{@code startHour}, {@code endHour}) 区间内视为静音时段。
 * 当 {@code startHour > endHour} 时表示**跨零点**（例如 22 → 7 表示 22:00 到次日 07:00）。
 * {@code startHour == endHour} 视为“未启用”，任何时刻都不静音（避免“全天静音”这种致命配置）。
 */
public final class QuietHours {

    /** 表示“未启用静音时段”。 */
    public static final int DISABLED_START = -1;
    /** 表示“未启用静音时段”。 */
    public static final int DISABLED_END = -1;

    private QuietHours() {
        // 工具类不允许实例化
    }

    /**
     * 判断当前小时是否落在静音时段内。
     *
     * @param startHour 起始小时（含），0-23；与 endHour 同为 -1 表示未启用
     * @param endHour   结束小时（不含），0-23
     * @param hour      待判断的小时，0-23
     * @return true 表示此刻应静音（只震动不响铃）
     */
    public static boolean isActive(int startHour, int endHour, int hour) { // modified: 新增
        if (startHour < 0 || endHour < 0) {
            return false; // 未启用
        }
        if (startHour == endHour) {
            return false; // 起止相同视为未启用，避免“全天静音”
        }
        int safeHour = normalizeHour(hour);
        if (startHour < endHour) {
            // 同一天内：如 01:00 - 06:00
            return safeHour >= startHour && safeHour < endHour;
        }
        // 跨零点：如 22:00 - 07:00，落在 [22,24) 或 [0,7)
        return safeHour >= startHour || safeHour < endHour;
    }

    /**
     * 把任意小时值收敛到 0-23，防止脏数据导致判定异常。
     *
     * @param hour 原始小时
     * @return 0-23 之间的值
     */
    public static int normalizeHour(int hour) { // modified: 新增
        int value = hour % 24;
        return value < 0 ? value + 24 : value;
    }

    /**
     * 生成用于界面展示的时段文案，例如 {@code "22:00–07:00"}。
     *
     * @param startHour 起始小时
     * @param endHour   结束小时
     * @return 展示用文案；未启用时返回空串
     */
    public static String describe(int startHour, int endHour) { // modified: 新增
        if (startHour < 0 || endHour < 0 || startHour == endHour) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%02d:00–%02d:00", startHour, endHour);
    }
}
```

### 6.21 `app/src/main/java\com\saiga\find\messagefinder\RepeatAlarmReceiver.java`（主源码）

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

### 6.22 `app/src/main/java\com\saiga\find\messagefinder\RoundPolicy.java`（主源码）

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

### 6.23 `app/src/main/java\com\saiga\find\messagefinder\SMSForegroundService.java`（主源码）

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

    /** modified: 【v4.2】服务存活标志，供 AlertDispatcher 判断是否需要发 STOP action。 */
    private static volatile boolean sRunning = false; // modified
    /** modified: 【v4.2】本轮是否正在播放，用于拒绝同轮重复 START_ALERT。 */
    public static boolean isRunning() { // modified
        return sRunning; // modified
    }

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
        sRunning = true; // modified: 【v4.2】登记存活
        // modified: 上次进程被杀时可能把系统闹钟音量永久改成了最大，先还原
        AlarmVolumeGuard.restoreIfStale(this); // modified
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
        // modified: 【v4.2 独立复审】同一轮的 START_ALERT 重复到达会取消本轮定时器、
        // reset MediaPlayer、从头播放（用户反复点通知 / FSI 重复投递时本轮永远响不完）。
        // 这里做本轮幂等，配合 AlertActivity.onNewIntent 的轮次去重形成双保险。
        if (alertActive) { // modified
            Log.d(TAG, "alert round already playing, ignore duplicate START"); // modified
            return;
        }
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
        //
        // modified: 【独立复审修正】这里显式传 false。服务的前台通知只承担「保持前台 + 显示状态」，
        // 绝不能再带全屏 Intent——否则服务每次 startForeground 都会尝试拉起告警页，
        // 与 AlertActivity 形成互相触发的循环（锁屏场景下会反复弹页）。
        Notification notification = NotificationHelper.build(this,
                NotificationHelper.CHANNEL_ID_ALERT, currentSender, currentKeyword,
                currentPerson, currentBody,
                getString(R.string.notif_status_round, currentRound), false);
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
        // modified: 【v4.2 独立复审】原实现用 DETACH，官方语义是「服务销毁后通知仍保留」。
        // AlertDispatcher.stop() 先 cancel 通知再请求停止，服务随后 DETACH 会把
        // 一条仍带「停止 / 稍后提醒」按钮的通知重新留在通知栏，用户点了停止却没停干净。
        // 本地停止属于「用户明确结束」，应当 REMOVE；endRound 的等待通知仍用 DETACH。
        stopSelfSafely(true); // modified
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
                        // modified: 【v4.2】同步落盘，进程被杀后仍可恢复（onDestroy 不一定执行）
                        AlarmVolumeGuard.remember(this, savedAlarmVolume); // modified
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
                    // modified: 【v4.2 独立复审】返回 true 表示「应用自行处理」，
                    // 原实现除打日志外什么都没做：MediaPlayer 进入 Error 状态、
                    // setLooping 失效，本轮彻底静音，服务却继续占前台直到超时。
                    // 现在显式收尾并补一声通知音，至少让用户知道有事发生。
                    ringWindowOpen = false; // modified
                    stopRingAndVibration(); // modified
                    releasePlayer(); // modified
                    restoreAlarmVolume(); // modified
                    fallbackToNotificationSound(); // modified
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
            // modified: 【v4.2】音量可能已经提升成功，失败分支必须立刻归还，
            // 否则「一直响到手动停止」配置下系统闹钟音量会被钉在最大。
            restoreAlarmVolume(); // modified
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
            AlarmVolumeGuard.clear(this); // modified: 【v4.2】正常收尾，清除跨进程残留标记
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
        sRunning = false; // modified: 【v4.2】注销存活标志
        Log.d(TAG, "alert service destroyed");
    }

    private static String fallback(String primary, String secondary) {
        return primary == null || primary.isEmpty() ? (secondary == null ? "" : secondary) : primary;
    }
}
```

### 6.24 `app/src/main/java\com\saiga\find\messagefinder\SMSReceiver.java`（主源码）

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

    /**
     * 上一次处理过的短信指纹与时间戳，用于去重。
     *
     * <p>modified: 【独立复审修正】原注释写「广播接收器为单实例」是不准确的——
     * 系统可能为不同广播创建不同实例，真正起作用的是这两个 **静态** 字段。
     * 既然跨实例共享，就必须考虑可见性：部分 ROM 会在非主线程分发广播，
     * 加上 volatile 保证「写后立即对下一次读取可见」，避免去重偶发失效。
     */
    private static volatile String lastFingerprint = null;
    private static volatile long lastFingerprintAt = 0L;
    /** modified: 【v4.2】volatile 只保证可见性，不保证「比较 + 两次写」原子。并发投递时去重会失效。 */
    private static final Object DEDUP_LOCK = new Object();

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
        // modified: 【v4.2 独立复审】原指纹用 body.length() + body.hashCode()。
        // String.hashCode() 是可构造碰撞的 32 位哈希，3 秒窗口内两条内容不同、
        // 长度相同且哈希相同的短信会被误判为「重复投递」而静默吞掉（漏报）。
        // 改用完整正文参与比较，'\0' 作分隔避免字段拼接歧义。
        String fingerprint = (sender == null ? "" : sender) + '\0' + body + '\0' + keyword;
        long now = System.currentTimeMillis();
        // modified: 原实现把「比较」和「两个字段的写入」拆成三步，且两个字段分两次写。
        // 部分 ROM 在线程池里并发投递广播时，两个线程可能都读到旧值而双双放行，
        // 或读到「新指纹 + 旧时间戳」的错误组合。这里用一把锁把整段收成原子操作。
        synchronized (DEDUP_LOCK) {
            if (fingerprint.equals(lastFingerprint)
                    && (now - lastFingerprintAt) < DUPLICATE_WINDOW_MS) {
                return true;
            }
            lastFingerprint = fingerprint;
            lastFingerprintAt = now;
            return false;
        }
    }
}
```

### 6.25 `app/src/main/java\com\saiga\find\messagefinder\SettingsActivity.java`（主源码）

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
        // modified: 【独立复审修正】原实现无条件 replace。旋转屏幕 / 深色模式切换等配置变更时
        // Activity 会重建，FragmentManager 已经自动恢复了旧的 PreferenceFragment，
        // 这里再 replace 一个新的，会出现「两份 Preference 叠加 / 刚改的值被旧实例覆盖」。
        // 标准写法：只在首次创建时添加。
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
            // modified: 【v4.2】自动联想开关由 AppConfig（"Settings" 文件）托管，
            // 而 syncAutoSuggestionState 原先只在 onCreatePreferences 调用一次；
            // 通过 MyDialogFragment 改过开关、或旋转后 Fragment 被复用，
            // 界面会显示旧的默认值。每次可见时用真实配置校准一次。
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

### 6.26 `app/src/main/java\com\saiga\find\messagefinder\SliderAdapter.java`（主源码）

```java
package com.saiga.find.messagefinder;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

// feeds the pages to the ViewPager aka Onboarding activity for first time users
public class SliderAdapter extends PagerAdapter {


    private Context mContext;
    private float screenDensity;
    private final static String TAG = "FTISetup";

    public SliderAdapter(Context context){
        mContext = context;
        // fetch the user configured device density -- determine the dpi
        screenDensity = mContext.getResources().getDisplayMetrics().density;
    }

    public int[] mDrawableIcons = {
            R.drawable.message2,
            R.drawable.priority_notifications,
            R.drawable.privacy
    };

    public int[] mPageTitle = {
            R.string.OnboardingTitle1,
            R.string.OnboardingTitle2,
            R.string.OnboardingTitle3
    };

    public int[] mPageDescs = {
            R.string.OnboardingDesc1,
            R.string.OnboardingDesc2,
            R.string.OnboardingDesc3
    };

    public int[] mPageTitleColor = {
            R.color.lightOrangeColor,
            R.color.lightBlueColor,
            R.color.lightPinkColor
    };

    public int[] mPageDescColor = {
            R.color.lightOrangeColor,
            R.color.lightBlueColor,
            R.color.lightPinkColor
    };

    @Override
    public int getCount() {
        // to return the number of pages
        // modified: 【独立复审修正】原实现只取 mPageTitle.length。四个平行数组一旦长度不一致，
        // instantiateItem 里就会越界崩溃。取最小长度可以从源头保证索引安全。
        return Math.min(Math.min(mPageTitle.length, mPageDescs.length),
                Math.min(mDrawableIcons.length, mPageTitleColor.length));
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        // modified: 【崩溃修复 v4.1.2】原实现为 `view == (RelativeLayout)object`。
        // page_layout.xml 的根节点在界面重做时已由 RelativeLayout 改为 LinearLayout，
        // 这行强转会在 ViewPager 首次布局时抛 ClassCastException，导致引导页（首次启动
        // 的第一个界面）直接闪退。PagerAdapter 的判定只需要引用相等，不依赖具体类型。
        return view == object;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        // inflates our pages for the view pager, for each onboarding screen
        // modified: 【独立复审修正】原实现把 getSystemService 的结果直接赋给成员变量并立刻 inflate，
        // 极端情况下（Context 异常/系统服务不可用）会 NPE。改为局部变量 + 兜底链，
        // 并且不再把 LayoutInflater 存成成员（每次复用同一个实例没有收益，还多一份状态）。
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (inflater == null) {
            inflater = LayoutInflater.from(mContext);
        }
        if (inflater == null) {
            Log.e(TAG, "layout inflater unavailable, position " + position);
            return new View(mContext);
        }
        View view = inflater.inflate(R.layout.page_layout, container, false);
        ImageView mPageIcon = view.findViewById(R.id.boarding_image);
        TextView mPageCaption = view.findViewById(R.id.title_textView);
        TextView mPageSummary = view.findViewById(R.id.summaryView);
        // this fixes the one of the major crashes in redmi based / low memory based phones
        // initially the images were inflated in memory based on the original image size,
        // this caused to consume more app memory at app startup for first time users
        // causing crashes making the app totally unusable
        // This new change addresses the issue by inflating the image to the size of the image view
        // based on users device screen density and image view to be displayed (dp values),
        // so that the ram memory occupied will be very much less. This is one of the recommended
        // workflow for this issue
        Log.d(TAG,"Reported Screen Density " + String.valueOf(screenDensity));
        // width of the image in pixels to be inflated in memory
        // some adjustment calculations are to be done to take in account of the error in screen
        // density calculation, this will help in sub sample size
        int requested_width = (int) (180 * (screenDensity-0.5) );
        // 防御：极端密度下算出的宽高可能 <=0，会让 calculateInSampleSize 里出现除零/死循环
        if (requested_width < 1) {
            requested_width = 1;
        } // modified: 上下界保护
        // modified: 【独立复审修正】原实现直接用 position 索引四个平行数组。
        // getCount() 取自 mPageTitle，只要将来有人给标题数组多加一页却忘了补
        // 颜色/图标数组，就会在运行期 ArrayIndexOutOfBoundsException（引导页直接崩）。
        // 这里把索引收敛到各数组的安全范围。
        int safePosition = Math.max(0, Math.min(position, mPageTitle.length - 1));
        int colorIndex = safePosition % mPageTitleColor.length;

        // height and width of our circular image view is same
        int requested_height = requested_width;
        // very efficient way of loading images in memory
        Bitmap pageIcon = decodeSampledBitmapFromResource(mContext.getResources(),
                mDrawableIcons[safePosition % mDrawableIcons.length],
                requested_width, requested_height);
        if (pageIcon != null) {
            mPageIcon.setImageBitmap(pageIcon); // modified: 解码失败时保持原状，不再传入 null
        } else {
            Log.w(TAG, "decode page icon failed, position " + position);
        }
        //@deprecated
     //   mPageIcon.setImageResource(mDrawableIcons[position]);
        mPageCaption.setTextColor(mContext.getColor(mPageTitleColor[colorIndex]));
        mPageSummary.setTextColor(mContext.getColor(mPageDescColor[colorIndex]));
        mPageCaption.setText(mPageTitle[safePosition]);
        mPageSummary.setText(mPageDescs[safePosition]);
        container.addView(view);
        return view;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        // marks the end of the onboarding screen
        // modified: 【崩溃修复 v4.1.2】同上，强转 RelativeLayout 会 CCE，改为按 View 移除
        container.removeView((View) object);
    }

    /**
     * 按目标尺寸降采样解码资源图片。
     *
     * <p>【代码溯源 / modified】本方法与下面的 {@link #calculateInSampleSize} 直接沿用
     * Android 官方培训文档《Loading Large Bitmaps Efficiently》中的示例实现
     * （https://developer.android.com/topic/performance/graphics/load-bitmap ，
     * 章节 "Read Bitmap Dimensions and Type in Memory" / "Load a Scaled Down Version into Memory"），
     * 原上游项目（saiga006/message-finder, GPL-3.0）即已引入，用于避免引导页大图在低端机上 OOM。
     * 使用场景：ViewPager 每页的圆形图标；用途：把 3 张大图按屏幕密度降采样后再进内存。
     * 本次仅补充上述来源标注，未改动算法逻辑；上界/下界保护与空结果处理是本项目自行添加的防御。
     */
    public static Bitmap decodeSampledBitmapFromResource(Resources res, int resId,
                                                         int reqWidth, int reqHeight) {

        // First decode with inJustDecodeBounds=true to check dimensions
        final BitmapFactory.Options options = new BitmapFactory.Options();


        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            // we use gpu memory instead of main memory, so this further reduce the chance of crash
            // in the memory profiler test, this method reduced the initial memory consumption by
            // ~60% (220MB reduced to 80MB)
            options.inPreferredConfig = Bitmap.Config.HARDWARE;
        }
        else {
            // this method reduced memory usage by 50% (220MB reduced to 112MB)
            // we use different bit format / representation for individual pixel
            // instead of 32 bits,  we make it to 8 bits to save memory
            // but it degrades the quality, so dither flag is set to compensate
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
        }
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, resId, options);

        // Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        Log.d(TAG,"insample size " + options.inSampleSize);
        // Decode bitmap with inSampleSize set, after configuring all options retrieve the bitmap
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res, resId, options);
    }

    public static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of original image, got from injustdecodebounds == false
        final int height = options.outHeight;
        final int width = options.outWidth;
        Log.d(TAG,"Size of the image height " + height + " width " + width);
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize(which decides the downsampling factor of image)
            // value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }
}
```

### 6.27 `app/src/main/res/layout/screen_onboarding.xml`（引导页布局）

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 二次开发（modified）：引导页改为“内容区 + 指示点 + 按钮栏”三段式纵向堆叠。
     去掉原 ViewPager 写死的 673dp 高度（小屏/横屏会溢出），并把指示点与按钮分离，避免相互遮挡。 -->
<!-- modified: 【v4.2】给根布局加 id，供 EdgeToEdgeHelper 施加系统栏 insets。 -->
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/onboarding_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/onboarding"
    android:theme="@style/OnboardingTheme"
    tools:context=".Onboarding">

    <androidx.viewpager.widget.ViewPager
        android:id="@+id/slideView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_above="@+id/navigator_menu"
        android:layout_alignParentTop="true" />

    <!-- 页面指示点 -->
    <LinearLayout
        android:id="@+id/navigator_menu"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_above="@+id/button_bar"
        android:layout_centerHorizontal="true"
        android:divider="@null"
        android:gravity="center"
        android:minHeight="36dp"
        android:orientation="horizontal"
        android:paddingStart="8dp"
        android:paddingEnd="8dp" />

    <!-- 底部按钮栏：上一页 / 下一页 -->
    <LinearLayout
        android:id="@+id/button_bar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_alignParentBottom="true"
        android:orientation="horizontal"
        android:paddingStart="8dp"
        android:paddingTop="4dp"
        android:paddingEnd="8dp"
        android:paddingBottom="16dp">

        <Button
            android:id="@+id/PrevButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:background="@android:color/transparent"
            android:fontFamily="sans-serif-medium"
            android:padding="16dp"
            android:text="@string/prev"
            android:textColor="@color/primaryTextColor"
            android:visibility="invisible" />

        <Button
            android:id="@+id/NextButton"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:background="@android:color/transparent"
            android:fontFamily="sans-serif-medium"
            android:padding="16dp"
            android:text="@string/next"
            android:textColor="@color/lightOrangeColor" />
    </LinearLayout>
</RelativeLayout>
```

### 6.28 `app/src/main/res/xml/app_preferences.xml`（设置页偏好）

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
        <!-- modified: 【v4.2】该开关由 AppConfig 单一托管。
             原来 AndroidX 框架还会自动把 autosuggestion_key 写进默认 prefs，
             与 "Settings" 文件里的 autoSuggDisabled 形成两套可分裂的状态。 -->
        <SwitchPreferenceCompat android:title="@string/auto_suggestion_feature"
            android:defaultValue="false"
            android:persistent="false"
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

### 6.29 `app/src/main/res/values/strings.xml`（字符串（默认））

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
    <string name="current_app_version">4.2.0</string> <!-- modified: 同步界面版本号 -->
    <string name="app_privacy_policy">Privacy Policy</string>
    <string name="privacy_url">https://unchartedtech.work/privacy-policy</string> <!-- modified: usesCleartextTraffic=false 下 http 明文外链会被拦截 -->
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

### 6.30 `app/src/main/res/values-zh-rCN/strings.xml`（字符串（中文））

```xml
<resources>
    <string name="app_name">铃达</string>
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
    <string name="current_app_version">4.2.0</string> <!-- modified: 同步界面版本号 -->
    <string name="app_privacy_policy">隐私政策</string>
    <string name="privacy_url">https://unchartedtech.work/privacy-policy</string> <!-- modified: usesCleartextTraffic=false 下 http 明文外链会被拦截 -->
    <string name="user_guidance">点击查看应用隐私政策</string>

    <string name="auto_suggestion_feature">自动联想</string>
    <string name="auto_suggestion_key">autosuggestion_key</string>
    <string name="auto_suggestion_on">当前已启用，重启应用后该功能生效</string>
    <string name="auto_suggestion_off">当前未启用，开启将申请读取联系人权限</string>

    <string name="dialog_text">是否为联系人号码启用自动联想功能？</string>
    <string name="helper_text_keyword">多个关键词用逗号或空格分隔，任一命中即响铃</string>

    <string name="OnboardingTitle1">"输入关键词"</string>
    <string name="OnboardingTitle2">"铃达"</string>
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
    <string name="permission_tip">红米 / HyperOS 必做四步：① 设置 → 应用设置 → 应用管理 → 铃达 → 自启动 = 开；② 省电策略 = 无限制；③ 通知管理 → 允许锁屏通知与悬浮通知；④ 权限 → 允许「接收短信」。缺任一项，MIUI 都可能在后台杀掉进程，闹铃不会响。</string>

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
    <string name="alert_title">铃达</string>
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

### 6.31 `app/src/test/java\com\saiga\find\messagefinder\ExampleUnitTest.java`（单元测试）

```java
package com.saiga.find.messagefinder;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }
}
```

### 6.32 `app/src/test/java\com\saiga\find\messagefinder\KeywordMatcherTest.java`（单元测试）

```java
package com.saiga.find.messagefinder;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse; // modified: v4.2 新增用例
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link KeywordMatcher} 的单元测试。
 *
 * <p>运行方式：{@code gradlew test} 或 Android Studio 里右键本文件 Run。
 * 该测试不依赖 Android 设备/模拟器。
 */
public class KeywordMatcherTest {

    @Test
    public void parseKeywords_splitsOnCommaAndSpace() {
        List<String> keywords = KeywordMatcher.parseKeywords("报警, 求助 help");
        assertEquals(Arrays.asList("报警", "求助", "help"), keywords);
    }

    @Test
    public void parseKeywords_supportsFullWidthComma() {
        // 中文输入法下最常见的全角逗号，原实现无法识别
        List<String> keywords = KeywordMatcher.parseKeywords("报警，求助");
        assertEquals(Arrays.asList("报警", "求助"), keywords);
    }

    @Test
    public void parseKeywords_supportsFullWidthSpaceAndSemicolon() {
        List<String> keywords = KeywordMatcher.parseKeywords("报警　求助；help");
        assertEquals(Arrays.asList("报警", "求助", "help"), keywords);
    }

    @Test
    public void parseKeywords_dedupAndLowercase() {
        List<String> keywords = KeywordMatcher.parseKeywords("ALERT alert Alert");
        assertEquals(1, keywords.size());
        assertEquals("alert", keywords.get(0));
    }

    @Test
    public void parseKeywords_emptyInput() {
        assertTrue(KeywordMatcher.parseKeywords(null).isEmpty());
        assertTrue(KeywordMatcher.parseKeywords("").isEmpty());
        assertTrue(KeywordMatcher.parseKeywords("  ,  , ").isEmpty());
    }

    @Test
    public void parseKeywords_truncatesTooLongKeyword() {
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < KeywordMatcher.MAX_KEYWORD_LENGTH + 20; i++) {
            longWord.append('a');
        }
        List<String> keywords = KeywordMatcher.parseKeywords(longWord.toString());
        assertEquals(1, keywords.size());
        assertEquals(KeywordMatcher.MAX_KEYWORD_LENGTH, keywords.get(0).length());
    }

    @Test
    public void findMatch_matchesChineseKeyword() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警", "求助"));
        assertEquals("报警", KeywordMatcher.findMatch(keywords, "家里进贼了，赶紧报警！"));
    }

    @Test
    public void findMatch_isCaseInsensitive() {
        Set<String> keywords = new HashSet<>(Arrays.asList("alert"));
        assertEquals("alert", KeywordMatcher.findMatch(keywords, "URGENT ALERT from server"));
    }

    @Test
    public void findMatch_normalizesFullWidthBody() {
        // 短信正文可能是全角字母（部分端口短信会这样发）
        Set<String> keywords = new HashSet<>(Arrays.asList("alert"));
        assertEquals("alert", KeywordMatcher.findMatch(keywords, "ＡＬＥＲＴ"));
    }

    @Test
    public void findMatch_returnsNullWhenNoKeywordHit() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警"));
        assertNull(KeywordMatcher.findMatch(keywords, "今天天气不错"));
    }

    @Test
    public void findMatch_handlesNullAndEmptyInputs() {
        Set<String> keywords = new HashSet<>(Arrays.asList("报警"));
        assertNull(KeywordMatcher.findMatch(null, "报警"));
        assertNull(KeywordMatcher.findMatch(new HashSet<String>(), "报警"));
        assertNull(KeywordMatcher.findMatch(keywords, null));
        assertNull(KeywordMatcher.findMatch(keywords, ""));
    }

    @Test
    public void findMatch_toleratesLegacyUnnormalizedKeyword() {
        // 老版本可能把大写关键词原样存进 SharedPreferences
        Set<String> legacy = new HashSet<>(Arrays.asList("ALERT"));
        assertEquals("ALERT", KeywordMatcher.findMatch(legacy, "an alert happened"));
    }
    // ---- 二次开发新增：全部命中模式（借鉴短信铃铛的规则设置） ----

    @Test
    public void findMatch_matchAllRequiresEveryKeyword() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "工商银行"));
        // 缺一个 -> 不命中
        assertNull(KeywordMatcher.findMatch(keywords, "您尾号 1234 账户到账 8000 元", true));
        // 全都有 -> 命中
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "工商银行：您尾号 1234 账户到账 8000 元", true));
    }

    @Test
    public void findMatch_matchAnyStillWorksWhenMatchAllDisabled() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "工商银行"));
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "您尾号 1234 账户到账 8000 元", false));
    }

    @Test
    public void findMatch_matchAllIgnoresBlankKeywords() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "  ", ""));
        assertEquals("到账", KeywordMatcher.findMatch(keywords, "到账 8000 元", true));
    }

    @Test
    public void findMatch_matchAllNormalizesFullWidthBody() {
        Set<String> keywords = new LinkedHashSet<>(Arrays.asList("到账", "余额"));
        // 全角数字与全角空格都应被归一化后仍能匹配
        assertEquals("到账", KeywordMatcher.findMatch(keywords,
                "到账８０００元　余额１２３４元", true));
    }

    // ---- modified: v4.2 独立复审新增的回归用例 ----

    @Test
    public void parseKeywords_supportsCjkPunctuation() { // modified
        // 中文用户按习惯输入「报警、求助」，原实现整串会被当成一条永不命中的关键词。
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警、求助")); // modified
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警。求助")); // modified
    }

    @Test
    public void parseKeywords_stripsZeroWidthCharacters() { // modified
        // 从微信 / 网页复制粘贴常带 U+200B 零宽空格与 U+FEFF BOM。
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("报警\u200B求助")); // modified
        assertEquals(Arrays.asList("报警", "求助"), KeywordMatcher.parseKeywords("\uFEFF报警\u200B求助")); // modified
    }

    @Test
    public void parseKeywords_truncatesByCodePoint() { // modified
        // 29 个 'a' + 1 个 emoji(占 2 个 UTF-16 char) = 30 个 code point，
        // 截断边界正好落在 emoji 上，不能留下孤立高代理项。
        StringBuilder word = new StringBuilder(); // modified
        for (int i = 0; i < KeywordMatcher.MAX_KEYWORD_LENGTH - 1; i++) { // modified
            word.append('a'); // modified
        }
        word.append("\uD83D\uDE00").append("tail"); // modified
        String result = KeywordMatcher.parseKeywords(word.toString()).get(0); // modified
        assertEquals(KeywordMatcher.MAX_KEYWORD_LENGTH, // modified
                result.codePointCount(0, result.length())); // modified
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1))); // modified
    }
}
```

### 6.33 `app/src/test/java\com\saiga\find\messagefinder\PhoneNumbersTest.java`（单元测试）

```java
package com.saiga.find.messagefinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link PhoneNumbers} 的单元测试。
 *
 * <p>重点覆盖中国大陆号码场景：短信发送方号码可能带 +86 / 0086 前缀，
 * 也可能带空格分隔（"138 0013 8000"），而用户从通讯录选出的号码格式又各不相同。
 * 原实现一律截取末 10 位，会把 11 位手机号截坏。
 */
public class PhoneNumbersTest {

    @Test
    public void canonical_keepsElevenDigitMobileIntact() {
        assertEquals("13800138000", PhoneNumbers.canonical("13800138000"));
    }

    @Test
    public void canonical_stripsCountryCode86() {
        assertEquals("13800138000", PhoneNumbers.canonical("+8613800138000"));
        assertEquals("13800138000", PhoneNumbers.canonical("008613800138000"));
        assertEquals("13800138000", PhoneNumbers.canonical("8613800138000"));
    }

    @Test
    public void canonical_stripsSeparators() {
        assertEquals("13800138000", PhoneNumbers.canonical("138 0013 8000"));
        assertEquals("13800138000", PhoneNumbers.canonical("138-0013-8000"));
        assertEquals("13800138000", PhoneNumbers.canonical("(138)0013-8000"));
    }

    @Test
    public void canonical_keepsShortServiceNumber() {
        assertEquals("10086", PhoneNumbers.canonical("10086"));
        assertEquals("95588", PhoneNumbers.canonical("95588"));
    }

    @Test
    public void canonical_emptyInput() {
        assertEquals("", PhoneNumbers.canonical(null));
        assertEquals("", PhoneNumbers.canonical(""));
        assertEquals("", PhoneNumbers.canonical("   "));
    }

    @Test
    public void isValidInput_acceptsRealisticNumbers() {
        assertTrue(PhoneNumbers.isValidInput("13800138000"));
        assertTrue(PhoneNumbers.isValidInput("+8613800138000"));
        assertTrue(PhoneNumbers.isValidInput("10086"));
        assertTrue(PhoneNumbers.isValidInput("138 0013 8000"));
        // modified: 通讯录/名片里常见的全角括号与点号分隔，应视为格式字符而非非法字符
        assertTrue(PhoneNumbers.isValidInput("（138）0013-8000"));
        assertTrue(PhoneNumbers.isValidInput("138.0013.8000"));
    }

    @Test
    public void isValidInput_rejectsBadInput() {
        assertFalse(PhoneNumbers.isValidInput(null));
        assertFalse(PhoneNumbers.isValidInput(""));
        assertFalse(PhoneNumbers.isValidInput("123"));           // 太短
        assertFalse(PhoneNumbers.isValidInput("1234567890123456")); // 太长（16 位）
        assertFalse(PhoneNumbers.isValidInput("1380013800a"));
        assertFalse(PhoneNumbers.isValidInput("+"));
        // modified: 以下 4 条对应【独立复审】发现的缺陷——stripSeparators 会静默丢弃
        // 非数字字符，旧实现因此把这些输入判为"合法"并原样存进配置，实际匹配的是别的号码。
        assertFalse(PhoneNumbers.isValidInput("138 0013 8000 转 1234")); // 汉字分机号
        assertFalse(PhoneNumbers.isValidInput("13800138000,1234"));      // 通讯录导出的逗号分机
        assertFalse(PhoneNumbers.isValidInput("1+3800138000"));          // '+' 不在首位
        assertFalse(PhoneNumbers.isValidInput("++8613800138000"));       // 多个 '+'
    }

    @Test
    public void matches_emptyConfiguredMeansAllSenders() {
        assertTrue(PhoneNumbers.matches("", "13800138000"));
        assertTrue(PhoneNumbers.matches(null, "10086"));
    }

    @Test
    public void matches_sameNumberWithDifferentFormatting() {
        assertTrue(PhoneNumbers.matches("13800138000", "+8613800138000"));
        assertTrue(PhoneNumbers.matches("+86 138 0013 8000", "13800138000"));
        assertTrue(PhoneNumbers.matches("10086", "10086"));
    }

    @Test
    public void matches_doesNotMatchDifferentNumber() {
        assertFalse(PhoneNumbers.matches("13800138000", "13900139000"));
    }

    @Test
    public void matches_doesNotMatchOnPartialSubstring() {
        // 原实现用 contains：配置 "1234" 会误命中 "1000012349999"
        assertFalse(PhoneNumbers.matches("1234", "1000012349999"));
    }

    @Test
    public void matches_supportsSuffixWhenOneSideHasExtraPrefix() {
        // 座机带区号与不带区号的场景：010-88886666 vs 88886666
        assertTrue(PhoneNumbers.matches("88886666", "01088886666"));
    }

    @Test
    public void matches_emptySenderNeverMatchesConfiguredNumber() {
        assertFalse(PhoneNumbers.matches("13800138000", ""));
        assertFalse(PhoneNumbers.matches("13800138000", null));
    }

    // ---- modified: v4.2 独立复审新增的回归用例 ----

    @Test
    public void isValidInput_rejectsUssdSupplementChars() { // modified
        // '*' / '#' 会被 stripSeparators 静默丢弃，夹在数字中间会把号码改写成另一个号码。
        // v5 的字符白名单修漏了这两个字符，这里把它们钉死。
        assertFalse(PhoneNumbers.isValidInput("1380013800*99")); // modified
        assertFalse(PhoneNumbers.isValidInput("#10086#")); // modified
        assertFalse(PhoneNumbers.isValidInput("138 0013 8000 #1234")); // modified
    }

    @Test
    public void canonical_keepsLongInternationalNumberIntact() { // modified
        // v5 把输入上限放宽到 E.164 的 15 位后，canonical 仍无条件取末 11 位，
        // 两个不同的 13 位号码会被压成同一个值。
        assertEquals("1234567890123", PhoneNumbers.canonical("1234567890123")); // modified
        assertEquals("9934567890123", PhoneNumbers.canonical("9934567890123")); // modified
    }

    @Test
    public void matches_doesNotCollapseDifferentLongNumbers() { // modified
        assertFalse(PhoneNumbers.matches("1234567890123", "9934567890123")); // modified
    }

    @Test
    public void matches_shortServiceNumberToleratesCountryPrefix() { // modified
        // 10086 / 95588 是最常监听的服务号；旧的 7 位门槛把它们挡在门外，
        // 短信侧返回 +8610086 时规则会静默失效。
        assertTrue(PhoneNumbers.matches("10086", "+8610086")); // modified
        assertTrue(PhoneNumbers.matches("95588", "8695588")); // modified
    }

    @Test
    public void matches_rejectsTooLongPrefixDifference() { // modified
        // 长度差超过 4 位不再做后缀匹配，避免短片段误命中长号码。
        assertFalse(PhoneNumbers.matches("01088886666", "886666")); // modified
    }
}
```

### 6.34 `app/src/test/java\com\saiga\find\messagefinder\QuietHoursTest.java`（单元测试）

```java
package com.saiga.find.messagefinder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link QuietHours} 的单元测试。
 *
 * <p>夜间静音是“跨零点”逻辑，最容易写错的三个点都在这里覆盖：
 * 起止点（含头不含尾）、跨零点、未启用。
 */
public class QuietHoursTest {

    @Test
    public void isActive_normalRangeInsideWindow() {
        assertTrue(QuietHours.isActive(1, 6, 3));
    }

    @Test
    public void isActive_normalRangeBoundariesAreHalfOpen() {
        assertTrue(QuietHours.isActive(1, 6, 1));   // 起始点含
        assertFalse(QuietHours.isActive(1, 6, 6));  // 结束点不含
    }

    @Test
    public void isActive_crossMidnightLateEvening() {
        // 22:00 - 07:00：23 点应静音
        assertTrue(QuietHours.isActive(22, 7, 23));
    }

    @Test
    public void isActive_crossMidnightEarlyMorning() {
        assertTrue(QuietHours.isActive(22, 7, 0));
        assertTrue(QuietHours.isActive(22, 7, 6));
        assertFalse(QuietHours.isActive(22, 7, 7));  // 07:00 起恢复响铃
    }

    @Test
    public void isActive_crossMidnightDaytimeIsNotQuiet() {
        assertFalse(QuietHours.isActive(22, 7, 12));
        assertFalse(QuietHours.isActive(22, 7, 21));
    }

    @Test
    public void isActive_disabledWhenNegative() {
        assertFalse(QuietHours.isActive(QuietHours.DISABLED_START, QuietHours.DISABLED_END, 23));
        assertFalse(QuietHours.isActive(-1, 7, 23));
    }

    @Test
    public void isActive_sameStartEndMeansDisabled() {
        // 起止相同不能理解成“全天静音”，否则用户会以为闹铃坏了
        assertFalse(QuietHours.isActive(22, 22, 22));
        assertFalse(QuietHours.isActive(0, 0, 0));
    }

    @Test
    public void normalizeHour_wrapsOutOfRangeValues() {
        assertEquals(0, QuietHours.normalizeHour(24));
        assertEquals(23, QuietHours.normalizeHour(-1));
        assertEquals(5, QuietHours.normalizeHour(29));
    }

    @Test
    public void describe_formatsTwoDigitRange() {
        assertEquals("22:00–07:00", QuietHours.describe(22, 7));
        assertEquals("01:00–06:00", QuietHours.describe(1, 6));
    }

    @Test
    public void describe_returnsEmptyWhenDisabled() {
        assertTrue(QuietHours.describe(-1, -1).isEmpty());
        assertTrue(QuietHours.describe(22, 22).isEmpty());
    }
}
```

### 6.35 `app/src/test/java\com\saiga\find\messagefinder\RoundPolicyTest.java`（单元测试）

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

---

## 7. 风险提示

### 7.1 本次改动带来的风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| **本环境无 adb / JDK / Android SDK，未做真机验证** | 所有结论来自静态审查 + CI 编译 + JVM 单测；CI 只能证明「能编译、纯 Java 规则正确」，不能证明「红米 K60 Pro 上真的响」 | §8 给出 15 条真机清单；若仍异常，请提供 `adb logcat -b crash -d` 或系统错误报告 |
| `AlertDispatcher` 全部入口加 `synchronized` | 理论上若未来某调用方在持锁期间做耗时 I/O，会串行化提醒链路 | 当前锁内只有 SharedPreferences `commit()`（小文件）与通知构建，无网络/磁盘大 I/O；已核对无回环，不会死锁 |
| 「划掉通知不再停止会话」是**行为变更** | 原实现划掉 = 永久停止；改后划掉只关通知，5 分钟后仍会再响 | 与项目原始需求「未处理就再提醒」一致；想彻底停止请用明确的 STOP 按钮或告警页按钮。**这是本轮唯一需要用户知晓的行为变化** |
| `PhoneNumbers.matches` 后缀规则收紧 | 从「双向后缀 + 两侧 ≥7 位」改为「较长侧 = 较短侧 + ≤4 位前缀」 | 已用 5 条新单测 + 原有 12 条回归覆盖；座机带区号场景（`01088886666` vs `88886666`）仍命中 |
| 新增 `RECEIVE_BOOT_COMPLETED` | 开机时多一个广播接收器；若会话 active 会补发一轮通知 | 只在 `session.isActive()` 时动作；normal 级权限，无隐私/网络影响 |
| 隐私链接改 `https://` | 若原作者的 `unchartedtech.work` 站点不支持 HTTPS，点击会打不开 | 不改则 `usesCleartextTraffic=false` 下同样打不开；如失效请自行更新该字符串或删除该 Preference |
| 音量守护会写系统设置 | `restoreIfStale` 只在检测到残留标记时才写一次 | 标记在正常收尾时立即清除；无论恢复成败都清，不会每次启动都写 |
| `withActions` 参数使状态通知不再带按钮 | 用户不能从「已结束」通知直接稍后提醒 | 这是 F05/F08 要修的 bug；需要稍后提醒请打开告警页或等下一轮 |

### 7.2 兼容性影响

- **签名**：仍使用仓库固定的 `keystore/bellgram.jks`，与 v4.1.0+ 完全一致 → **可直接覆盖安装**，无需卸载。
- **versionCode 23 > 22**，升级路径正常；不会降级。
- **存储**：既有键名、默认 SharedPreferences 文件名一个未改；旧配置、命中日志、提醒策略全部保留。
  新增 3 个内部键：`onboarding_done_v4`、`snoozed_round`、`alarm_volume_guard_v4`。
- **对外接口**：`NotificationHelper.build/post` 的原有重载、`SMSForegroundService` 的三个 action、
  `AlertDispatcher` 全部 public 方法签名（除加 `synchronized` 修饰符）均未变。
- **依赖**：`dependencies` 块一行未动；APK 体积变化只来自新增的两个小类。
- **普通笔记本**：`git clone` → `./gradlew assembleDebug`（JDK 17）即可；`tools/` 下脚本零第三方库，
  Python 3 标准库可跑，CI 已接入 `check_view_types / check_res_refs / check_page_ids` 三项。
- **破坏性修改**：无。约束 1 要求「如需破坏性修改必须重点标注」——本轮唯一的行为变更是 §7.1 的
  「划掉通知不再停止会话」，已重点标注。

### 7.3 已知未修限制（§3.3 的展开）

1. **U01（最需要真机确认）**：亮屏且用户不点通知时，全屏 Intent 只会显示为 HUN，
   系统不会拉起 `AlertActivity`，因此**本轮只有渠道的一声闹铃，没有 MediaPlayer 持续播放 + 循环震动**；
   但 `scheduleFollowUp()` 的闹钟兜底仍在，5 分钟后会再发一轮。彻底解决需要引入新的
   后台 FGS 启动豁免（例如 exact alarm 触发的接收器），属于架构级改动，
   按「稳定性优先 + 约束 3」本轮不动，等真机确认影响后再单独出方案。
2. **U03 / U04**：无音频焦点管理，来电/蓝牙打断后不主动恢复；`ringDurationSec=0`（一直响）
   无硬上限，WakeLock 30 分钟到期后可能静默。两者都建议真机观察后再定策略。
3. **U05**：静音时段在每轮开始读一次快照，跨分钟边界本轮内不切换；「一直响」时永不重判。
4. **U06 / U09 / U10**：渠道被用户调成 `IMPORTANCE_NONE`、厂商省电策略、
   `canUseFullScreenIntent()` 未授予时 HUN 可达性——这三项完全取决于系统/ROM，`PermissionChecker`
   已提供权限体检入口，但需真机逐项确认。
5. **U02 / U07 / U08**：长短信分片广播、跨时区日志时间、通知 ID 123 被普通通知与 FGS 通知共用，
   均为低概率或依赖具体环境，列入 §8 观察项。

### 7.4 需要用户处理的事项

- 之前在对话里明文提供过的 GitHub token 建议到 `https://github.com/settings/tokens` 撤销。
- 装过 **v4.0.1 或更早**版本才需要卸载重装（签名不同）；**v4.1.0 / 4.1.1 / 4.1.2 → 4.2.0 直接覆盖**。

---

## 8. 验证方案

### 8.1 开发期 / CI（已执行）

| # | 步骤 | 期望结果 |
|---|---|---|
| 1 | `python tools/check_view_types.py` | `== findViewById 类型不匹配 == 0`、`OK` |
| 2 | `python tools/check_res_refs.py` | `OK: 资源引用完整` |
| 3 | `python tools/check_page_ids.py` | `OK: 页面 id 全部有归属` |
| 4 | 全部 37 个 XML 用 `ElementTree` 解析 | 0 错误 |
| 5 | `gradle :app:testDebugUnitTest` | 全部单测通过（含本轮新增 8 条） |
| 6 | `gradle :app:assembleDebug` | 编译通过，产出 APK |
| 7 | 解析 APK 的 `AndroidManifest.xml` | `versionCode 23` / `versionName 4.2.0` / `targetSdk 36` |
| 8 | `git clone` 到另一台笔记本后重复 1-6 | 结果一致（可移植性验证） |

> 前 4 项本轮已在本机执行通过；5-7 由 GitHub Actions 执行，结果以 CI 页面为准。

### 8.2 真机验收清单（红米 K60 Pro / HyperOS，按优先级）

**A. 先确认上一轮的闪退没有回归**
1. 覆盖安装 v4.2.0，点图标 → 能进应用（已走过引导页的用户应直接进主界面）。
2. 若是全新安装：引导页第 1 屏正常显示；滑完 3 屏；**三键导航下「完成」按钮完整可点**（F26）；点完成后进主界面。
3. 杀进程后再次冷启动 → **不再回到引导页**（F27）。

**B. 规则与配置（本轮数据层修复）**
4. 关键词输入 `报警、求助`（顿号）→ 保存后进设置页看「当前配置」应是**两条**关键词（F24）。
5. 从微信复制带零宽空格的关键词粘贴保存 → 同样能正确拆成多条（F24）。
6. 号码填 `10086`，用另一台手机以 `+8610086` 形式（或让运营商短信带 `86` 前缀）发含关键词的短信 → 应命中（F23）。
7. 号码填 `1380013800*99` → 「启动」按钮应拒绝并提示格式错误（F21）。
8. 旧配置升级后检查设置页：号码、关键词、命中记录、提醒策略**全部还在**（兼容性）。

**C. 核心提醒链路**
9. 发一条命中关键词的短信，锁屏状态 → 全屏告警页弹出、闹铃持续响 + 循环震动。
10. 响铃期间**反复点通知横幅** → 铃声不从头开始、本轮能按时结束（F11）。
11. 点「停止」→ 铃声立刻停、**通知栏不再残留带按钮的通知**（F14）、后续不再重复。
12. 多轮提醒结束后，在「已结束」通知上（若有）点稍后提醒 → **不应再响**（F05/F08）；本轮该通知已不带按钮。
13. **随手划掉通知横幅** → 5 分钟后应**仍然再响一轮**（F05，本轮行为变更）；想彻底停止请点 STOP。
14. 提醒进行中进设置把「重复间隔」改成 0 → 到点**不应再响一轮**（F09）。
15. 提醒进行中点「稍后提醒」→ 即使重复间隔设为 0，5 分钟后**仍应响这一轮**（F09 回归防护）。
16. 提醒进行中**重启手机** → 开机后应补发一轮通知并继续后续轮次（F16）。
17. 提醒播放中强行杀进程（`adb shell am force-stop`），然后查看系统闹钟音量 → **应恢复为原值**（F13）。
18. 提醒过程中让蓝牙耳机断开/来电打断 → 观察是否静默（U03 取证，暂不修）。
19. 亮屏使用手机时收到命中短信且不点通知 → 记录实际表现（只有一声 HUN 还是持续响），
    并确认 5 分钟后是否再响（U01 取证，决定是否做架构级改造）。

### 8.3 取证方法（若仍异常）

```bash
# 崩溃堆栈
adb logcat -b crash -d > crash.txt
# 本应用相关日志
adb logcat -d -s SMSReceiver AlertDispatcher AlertScheduler SmsService NotificationHelper BootReceiver AlarmVolumeGuard
# 配置实际落盘内容（debug 包可 run-as）
adb shell run-as com.saiga.find.messagefinder cat shared_prefs/com.saiga.find.messagefinder_preferences.xml
```

若无法连电脑，可用「设置 → 开发者选项 → 错误报告」导出，或在手机上安装日志捕获类工具，
把包含 `SMSReceiver` / `AlertDispatcher` / `SmsService` 标签的片段发回。

### 8.4 回归验证顺序建议

先跑 A（3 条）确认能进应用，再跑 B（5 条）确认规则没丢，最后跑 C（11 条）确认提醒链路。
任何一条不通过，请附 §8.3 的日志；**不要先改配置再复测**，以免掩盖初始状态问题。

---

**报告结束。** 本轮共修复 34 项确定性缺陷、新增 2 个类与 8 条单测、零新增依赖、无架构级重构；
10 项需真机取证的限制已如实列出并转入验收清单。
