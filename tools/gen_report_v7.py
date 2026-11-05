# -*- coding: utf-8 -*-
"""生成 AUDIT_REPORT_V7.md：严格 1-8 节；第 6 节自动拼接整合后的全部源码。"""
import io, os, glob, datetime

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
os.chdir(ROOT)
OUT = "AUDIT_REPORT_V7.md"

HEAD = u"""# 铃达 / Bellgram —— 项目源代码审计、Bug 修复与迭代优化报告（V7）

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
【修改思路】单把 `DEDUP_LOCK` 把整段收成原子操作；指纹改用完整正文 + `\\0` 分隔。

```java
private static final Object DEDUP_LOCK = new Object(); // modified
String fingerprint = (sender == null ? "" : sender) + '\\0' + body + '\\0' + keyword; // modified
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
if (ch == '\\u3001' || ch == '\\u3002' || ch == '\\uFF0E') { // modified
    builder.append(','); // modified: CJK 标点 -> 分隔符
} else if (ch == '\\u200B' || ch == '\\uFEFF' || ch == '\\u2060' // modified
        || ch == '\\u200E' || ch == '\\u200F') { // modified
    builder.append(' '); // modified: 零宽字符 / BOM -> 分隔符
} else if (ch == '\\u3000' || ch == '\\u00A0' // modified
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
    String text = value.replace('\\n', ' ').replace('\\r', ' ').replace('\\t', ' ').trim(); // modified
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
"""

TAIL = u"""---

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
"""

# ---------- 第 6 节：自动拼接整合后的全部源码 ----------
S6_HEAD = u"""## 6. 完整整合后的全部源代码

**范围说明**：以下给出**整合后的全部项目源码**——`app/src/main/java/` 下全部 Java 文件、
`app/src/test/java/` 下全部单测、`AndroidManifest.xml`、`app/build.gradle`，以及本轮改动过的
XML/资源文件。图片、音频、`.webp`、mipmap 位图等二进制资源无法以文本呈现，见仓库对应目录；
Gradle wrapper、`.github/workflows/`、`.gitattributes`、keystore 等未改动文件亦见仓库。

**标注约定**：本次新增/修改的代码行与代码块均以单行注释 `// modified` 标注；
XML 因属性之间不允许插入注释，在合法位置以 `<!-- modified -->` 标注并在 §5 逐条列明；
未改动的历史代码保留其原有的版本标注（如 `// modified: 【独立复审修正】`），便于区分改动轮次。
逐行精确对照可用 `git diff <v4.1.2-commit> HEAD`。

"""

LANG = {
    ".java": "java", ".xml": "xml", ".gradle": "groovy", ".py": "python", ".md": "markdown"
}

sections = []
order = [
    ("app/build.gradle", "构建配置"),
    ("app/src/main/AndroidManifest.xml", "清单"),
]
java_main = sorted(glob.glob("app/src/main/java/**/*.java", recursive=True))
java_test = sorted(glob.glob("app/src/test/java/**/*.java", recursive=True))
for f in java_main:
    order.append((f, "主源码"))
order.append(("app/src/main/res/layout/screen_onboarding.xml", "引导页布局"))
order.append(("app/src/main/res/xml/app_preferences.xml", "设置页偏好"))
order.append(("app/src/main/res/values/strings.xml", "字符串（默认）"))
order.append(("app/src/main/res/values-zh-rCN/strings.xml", "字符串（中文）"))
for f in java_test:
    order.append((f, "单元测试"))

for rel, label in order:
    if not os.path.exists(rel):
        continue
    ext = os.path.splitext(rel)[1]
    lang = LANG.get(ext, "text")
    with io.open(rel, encoding="utf-8") as fh:
        body = fh.read().rstrip()
    sections.append(u"### 6.%d `%s`（%s）\n\n```%s\n%s\n```\n" %
                    (len(sections) + 1, rel, label, lang, body))

with io.open(OUT, "w", encoding="utf-8", newline="\n") as fh:
    full = HEAD + S6_HEAD + u"\n".join(sections) + u"\n" + TAIL
    # 正文里的 '\0' 分隔符在 Python 字符串中会被解释成 NUL 字节，写回成字面量；
    # 代码片段里的 Unicode 转义也还原成 Java 的单反斜杠写法
    fh.write(full.replace(u"\x00", u"\\0").replace(u"\\\\u", u"\\u"))

print("生成 %s" % OUT)
print("源码文件数:", len(sections))
print("总行数:", sum(1 for _ in io.open(OUT, encoding="utf-8")))
