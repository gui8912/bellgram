# 铃达 · Bellgram

<img src="docs/app-icon.png" width="88" align="right" alt="铃达图标">

**只让该响的短信响，而且响到你处理为止。**

把手机里含指定关键词的短信，变成"闹钟级"提醒：锁屏整页告警 + 响铃震动 + 没处理就每 5 分钟再来一次。

[![Build debug APK](https://github.com/gui8912/bellgram/actions/workflows/build-apk.yml/badge.svg)](https://github.com/gui8912/bellgram/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/gui8912/bellgram?include_prereleases&sort=semver&label=release)](https://github.com/gui8912/bellgram/releases/latest)
![Platform](https://img.shields.io/badge/Android-6.0%2B%20(API%2023%2B)-3DDC84)
![Target](https://img.shields.io/badge/targetSdk-36%20(Android%2016)-3DDC84)

---

## 为什么需要它

普通短信提示音响一下就过去了。手机在口袋里、开了静音、戴着耳机、正在开会——都可能错过。而错过的那条，恰好是银行到账、验证码、客户确认、家人急事。

系统闹钟很能叫醒人，但它只认时间，不认内容。

铃达把这两件事接起来：**按短信内容触发，用闹铃的强度提醒，不处理就一直提醒。**

适用场景：只关心特定几类信息（"到账""验证码""报警""急"），其余短信不想被打扰。

## 功能

| 能力 | 说明 |
|---|---|
| **关键词规则** | 两种匹配模式：命中任意一个 / 必须全部命中。全角半角、大小写自动归一，不用纠结输入法 |
| **号码限定**（可选） | 兼容 `+86` / `0086` / 带空格横线括号的写法，也支持 10086 这类短号 |
| **响铃 + 震动** | 可独立开关，只震动不响铃也支持 |
| **单轮时长** | 直到手动停止 / 30 秒 / 60 秒 / 2 分钟 |
| **未处理自动再提醒** | 默认每 5 分钟再来一次，最多 4 轮；可关掉，也可设为不限次数。轮次进度直接写在通知上 |
| **锁屏全屏告警** | 息屏或锁屏时整页弹出，必须点「停止」或「稍后提醒」才能继续用手机 |
| **夜间静音时段** | 设定时段内只震动不出声，支持跨零点（如 23 点到 7 点） |
| **规则演练** | 粘一段示例短信，边输边告诉你"会不会响、命中了哪个词"，不用真等一条短信来试 |
| **命中记录** | 最近 20 条，解决"到底响没响过"的疑问 |
| **权限体检** | 逐项检查短信权限、通知权限、通知总开关、全屏 Intent、电池优化白名单，每项可一键跳到对应系统设置 |

## 安装

从 [**Releases**](https://github.com/gui8912/bellgram/releases/latest) 下载 `bellgram-*.apk`，传到手机点击安装。

- 使用固定自用签名，后续版本可直接覆盖升级，号码、关键词、提醒策略、命中记录全部保留。
- 是 debug 构建，仅供个人自用，不上架任何应用商店，也不适合二次分发。
- 也可以从 [Actions](https://github.com/gui8912/bellgram/actions/workflows/build-apk.yml) 取最新构建产物（需解压 zip）。

> **装过 v4.0.x 的请先卸载旧版。** 那一版用的是 CI 临时生成的签名，私钥随构建机器一起销毁，
> 无法延续到新版。这是最后一次需要卸载 —— 从 v4.1.0 起签名固定，以后都能直接覆盖升级。

## 装完必做：五项系统设置

国产 ROM 会主动限制后台应用。**缺任何一项，都会表现为「短信到了却不响」或「只响一次就没了」。**

以红米 K60 Pro / HyperOS 为例：

| # | 设置项 | 路径 |
|---|---|---|
| 1 | 自启动 = 开 | 设置 → 应用设置 → 应用管理 → 铃达 → 自启动 |
| 2 | 省电策略 = 无限制 | 同上 → 省电策略 |
| 3 | 允许锁屏通知 + 悬浮通知 | 同上 → 通知管理 |
| 4 | 允许「全屏通知」 | 同上 → 其他权限 → 显示在其他应用上层 / 全屏通知（Android 14+ 默认不授予） |
| 5 | 允许接收短信 | 同上 → 权限 → 短信 |

装好后打开 App，首页状态条会显示还差几项；点「权限体检」可逐项处理并直达系统设置。

## 工作原理

`targetSdk 36`（Android 16）之后有两条硬限制，直接决定了架构：

1. **不允许从 `SMS_RECEIVED` 广播启动前台服务** —— 官方豁免清单里没有短信广播。老写法"收到短信就在广播里 `startForegroundService()`"在 Android 12+ 会抛 `ForegroundServiceStartNotAllowedException`，且是在后台静默失败。
2. **`shortService` 类型的前台服务约 3 分钟超时** —— 撑不住"每 5 分钟提醒一次"。

所以主链路改成**通知驱动**，让前台服务只在"用户可见"时启动：

```
短信广播 SMS_RECEIVED
   │  号码过滤 → 长短信多包拼接 → 关键词匹配 → 去重 → 命中日志
   ▼
带闹铃音的高优先级通知（含全屏 Intent）        ← 这是唯一可靠的唤醒入口
   │
   ├─ 息屏/锁屏 → 全屏告警页 AlertActivity（用户可见，此时启动 FGS 合法）
   │                  │
   │                  ▼
   └─ 亮屏       →  单轮播放服务 SMSForegroundService（mediaPlayback）
                      │  播放 30s / 60s / 直到手动停止
                      ▼
                  AlarmManager.setAlarmClock() 排下一轮
                      │  用闹钟语义，不需要 SCHEDULE_EXACT_ALARM 权限，Doze 下也能唤醒
                      ▼
                  到点 → RepeatAlarmReceiver → 重新发通知（回到第二步）
```

几个关键设计取舍：

- **为什么用 `setAlarmClock()` 而不是 `setExactAndAllowWhileIdle()`**：前者是"用户可见的闹钟"，系统在 Doze 下优先保证唤醒，且不需要申请精确闹钟权限；代价是状态栏会出现闹钟图标。
- **为什么服务只播一轮就退出**：常驻服务会被系统回收、会撞 3 分钟超时、还会触发 `onTimeout` ANR。改成"播放器 + 精确闹钟"后，每轮之间不占任何常驻资源。
- **为什么通知渠道分两个**：`priority_alert_audible_v4`（带闹铃音，广播阶段先出声）和 `priority_alert_v2`（静音，服务接手后只更新文案）。这样既保证第一声一定响，又避免"通知音 + 播放器"双重响铃。注意 Android 的通知渠道声音**创建后不可修改**，所以升级改造时只能换新渠道 ID。
- **为什么状态通知不带全屏 Intent**：只有真正要叫人的那一轮才带。否则用户点过「停止」之后，锁屏会再弹出一个写着"提醒已结束"的全屏页。

## 项目结构

```
app/src/main/java/com/saiga/find/messagefinder/
├── 入口与调度
│   ├── SMSReceiver.java            短信广播：过滤、拼接、匹配、去重、记日志
│   ├── AlertDispatcher.java        统一调度入口：新短信 / 重复闹钟 / 停止 / 稍后 / 结束
│   ├── AlertScheduler.java         精确闹钟（setAlarmClock）
│   ├── RepeatAlarmReceiver.java    闹钟到点入口，只重发通知，绝不启动服务
│   └── AlertActionReceiver.java    通知按钮动作（停止 / 稍后提醒），走广播而非 getService
├── 提醒播放
│   ├── SMSForegroundService.java   单轮播放器：响铃 + 震动，播完即退
│   ├── NotificationHelper.java     通知、渠道、PendingIntent、全屏 Intent 唯一构建入口
│   └── AlertActivity.java          锁屏全屏告警页
├── 状态与规则（纯 Java，可单测）
│   ├── AlertSession.java           跨组件会话状态持久化
│   ├── RoundPolicy.java            轮次规则：总轮数 / 是否还有下一轮 / 稍后提醒间隔
│   ├── KeywordMatcher.java         关键词归一化与匹配
│   ├── PhoneNumbers.java           号码规范化与比对
│   └── QuietHours.java             夜间静音时段判定
├── 配置与界面
│   ├── AppConfig.java              全应用配置统一门面
│   ├── MainActivity.java           主界面
│   ├── SettingsActivity.java       设置页（AndroidX Preference）
│   ├── PermissionChecker.java      权限体检
│   ├── EdgeToEdgeHelper.java       边到边 insets 适配
│   └── HitLog.java                 命中记录
└── 引导
    ├── Onboarding.java / SliderAdapter.java / MyDialogFragment.java
```

## 构建

```bash
# 需要 JDK 17、Android SDK Platform 36
./gradlew :app:assembleDebug              # Linux / macOS
gradlew.bat :app:assembleDebug            # Windows
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

环境要求与常见报错处理见 [BUILD.md](BUILD.md)。

| 组件 | 版本 |
|---|---|
| AGP | 8.11.1 |
| Gradle | 8.13 |
| JDK | 17 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 23（Android 6.0） |

## 已知限制

这些是设计边界，不是待修 bug：

- **通知权限被拒 = 完全静默**。`targetSdk 31+` 下不能从后台起服务，通知是唯一唤醒入口。用户拒绝通知权限或关闭通知总开关后，应用没有任何办法发出声音。已用状态条红字 + 权限体检 + 日志提示，但只能靠用户自己配置。
- **首次安装后需要手动放行 5 项系统设置**（见上文），国产 ROM 无法通过代码绕过。
- **`setAlarmClock` 会显示系统闹钟图标**，这是使用该 API 的固有代价。
- 只在红米 K60 Pro / HyperOS 上做过目标验证，其他 ROM 未逐一测试。
- **亮屏且未点通知时，本轮只有渠道的一声闹铃**（Android 对全屏 Intent 的既定策略：亮屏只显示悬浮通知，
  不会拉起全屏页，因此没有 MediaPlayer 持续播放）。但 5 分钟后的重复轮次由系统闹钟兜底，不会丢。
- **随手划掉通知不再等于「停止」**：划掉只关通知，会话继续，5 分钟后仍会再响一轮；
  想彻底停止请点通知上的「停止」或告警页里的按钮。

## 版本

| 版本 | 内容 |
|---|---|
| **v4.2.0** | **全量独立复审**：修复 34 项确定性缺陷（通知渠道创建失败静默丢通知、设备重启后提醒永久失效、进程被杀后系统闹钟音量被永久改大、会话并发错轮、降级通知自触发循环、去重哈希碰撞、关键词顿号/零宽字符、旧配置静默丢失、引导页按钮被导航栏遮挡等）；新增开机恢复、音量守护与 8 条边界单测。详见 [`AUDIT_REPORT_V7.md`](AUDIT_REPORT_V7.md) |
| **v4.1.2** | **修复首次安装启动即闪退**：`SliderAdapter` 仍把页面视图强转为 `RelativeLayout`，而界面重做后 `page_layout.xml` 的根节点已改为 `LinearLayout`，导致首次启动的引导页在 ViewPager 首次布局时抛 `ClassCastException` |
| v4.1.1 | 更换应用图标：铃铛 + 声波，自适应图标（渐变背景 + 前景图层），同步替换界面品牌图 |
| v4.1.0 | 更名为「铃达」；固定自用签名，解决 CI 每次构建签名不同导致无法覆盖安装的问题；修正号码校验会静默改写用户输入的问题 |
| v4.0.1 | 独立复审后的 13 项缺陷修复：老用户升级双重响铃、状态通知误弹全屏页、一直响模式无法停止、旋转屏幕铃声重启、MIUI 全屏页被锁屏盖住、`gradle-wrapper.jar` 版本错配等 |
| v4.0.0 | Android 16 合规改造：`targetSdk 29 → 36`、主链路重构为通知驱动 + 精确闹钟、AndroidX Preference 迁移、边到边与预测式返回适配、权限体检 |
| v3.0.0 | 19 项 P1 缺陷修复、界面重做、响铃/震动/重复提醒策略、夜间静音、规则演练、稍后提醒、锁屏全屏告警 |

详细记录：
- [REVIEW_REPORT_V5.md](REVIEW_REPORT_V5.md) —— 独立复审（7 个核查维度，含 CI 实证与 APK 核验）
- [AUDIT_REPORT_V4.md](AUDIT_REPORT_V4.md) —— Android 16 合规改造全过程
- [AUDIT_REPORT.md](AUDIT_REPORT.md) —— v3.0.0 历史存档，其中 4 项结论已被后续版本推翻

## 致谢与许可

本项目是 [saiga006/message-finder](https://github.com/saiga006/message-finder)（v2.9.8，GPL-3.0）的个人二次开发版本。原始工程由 saiga006 开发，遵循 GPL-3.0 许可，本衍生版本同样遵循 GPL-3.0。

原工程的定位是"用关键词匹配短信并提醒"，本版本围绕**提醒可靠性**做了架构级重写（见上文"工作原理"），并针对中国大陆号码格式、国产 ROM 后台限制、Android 12–16 的权限与前台服务变更做了适配。

复用第三方代码的出处均在对应文件注释中标明来源与用途（如位图降采样实现出自 Android 官方文档示例）。

> 这是个人自用项目，不提供商用支持。使用它导致的漏接消息、误响、耗电等问题请自行评估。
