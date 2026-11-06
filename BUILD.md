# 构建与运行说明（铃达 / Bellgram v4.3.0）

> 目标：在一台**没有配置过本项目**的普通笔记本上，拉取代码后能直接编译出可安装的 APK。
> 本文档只写「怎么做」，架构与逐项改动见 [`AUDIT_REPORT_V4.md`](AUDIT_REPORT_V4.md)
> 与 [`REVIEW_REPORT_V5.md`](REVIEW_REPORT_V5.md)；v4.2.0 全量复审见 [`AUDIT_REPORT_V7.md`](AUDIT_REPORT_V7.md)，
> v4.3.0 双主题 / 音量档位 / 过滤链的独立复审见 [`REVIEW_REPORT_V8.md`](REVIEW_REPORT_V8.md)。

---

## 1. 环境要求

| 组件 | 要求 | 说明 |
|---|---|---|
| JDK | **17**（必须） | AGP 8.11 不支持 JDK 8/11 作为 Gradle JDK；用 Android Studio 自带的 JBR 17 最省事 |
| Android Studio | 2024.2 (Ladybug) 或更新 | 太旧的 AS 内置 AGP 无法打开 AGP 8.11 工程 |
| Gradle | **8.13** | 已由 `gradle/wrapper` 固定，**不需要本机安装 Gradle** |
| Android SDK Platform | **36**（Android 16） | `compileSdk 36` 必需，缺了会在 sync 阶段报 `Failed to find target with hash string 'android-36'` |
| Build Tools | 35.0.0 或 36.0.0 | AGP 会自动选用与 compileSdk 匹配的版本 |
| 网络 | 需要 | 首次构建要下载 Gradle 发行包与 AndroidX 依赖 |

> `minSdk 23`：可运行在 Android 6.0 及以上；`targetSdk 36`：符合当前最新（Android 16）规范。

---

## 2. 方式一：Android Studio（推荐）

1. **File → Open**，选中本目录（含 `settings.gradle` 的那一层），不要选 `app/`。
2. 等待 Gradle Sync。首次会下载 Gradle 8.13（约 130 MB）与依赖。
3. 若提示缺少 SDK Platform 36：
   **Tools → SDK Manager → SDK Platforms → 勾选「Android 16 (API 36)」→ Apply**。
4. 确认 Gradle JDK：
   **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** 选 **17**。
5. **Run ▶** 安装到手机，或 **Build → Build Bundle(s)/APK(s) → Build APK(s)**。
   产物路径：`app/build/outputs/apk/debug/app-debug.apk`。

---

## 3. 方式二：命令行

Windows（PowerShell / cmd）：

```bat
gradlew.bat :app:assembleDebug --stacktrace
```

macOS / Linux：

```bash
chmod +x gradlew
./gradlew :app:assembleDebug --stacktrace
```

只跑纯 Java 单元测试（不需要连手机，也不需要完整构建）：

```bat
gradlew.bat :app:testDebugUnitTest
```

macOS / Linux：

```bash
./gradlew :app:testDebugUnitTest
```

安装到已连接的手机：

```bat
gradlew.bat :app:installDebug
```

### 关于 `local.properties`

本仓库**不含** `local.properties`（它记录的是每台机器自己的 SDK 路径，写死会把别人的构建搞坏）。
以下两种方式任选其一，构建时会自动生成或由 IDE 生成：

- 用 Android Studio 打开一次，它会自动写入；
- 或手动在本目录新建：

```properties
# Windows 示例（路径按自己的 SDK 位置改）
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

```properties
# macOS 示例
sdk.dir=/Users/你的用户名/Library/Android/sdk
```

也可以不写文件，改用环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。

---

## 4. 国内网络较慢时

`gradle/wrapper/gradle-wrapper.properties` 目前指向腾讯云镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip
```

若该镜像不可用，改成官方地址即可（文件里已有注释示例）：

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

依赖下载慢，可在 `settings.gradle` 里加镜像仓库（**注意顺序：镜像要放在 `google()` 之前**）：

```groovy
pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        google()
        mavenCentral()
    }
}
```

> 不加镜像也能构建，只是慢。当前 `settings.gradle` 未写死镜像，避免给其他人带来额外依赖。

---

## 5. 常见报错对照

| 报错 | 原因 | 处理 |
|---|---|---|
| `Failed to find target with hash string 'android-36'` | 没装 SDK Platform 36 | SDK Manager 勾选 Android 16 (API 36) |
| `Android Gradle plugin requires Java 17 to run. You are currently using Java 11` | Gradle JDK 不是 17 | 改 Gradle JDK 为 17 |
| `Could not find com.android.tools.build:gradle:8.11.1` | 缺 `google()` 仓库或网络问题 | 检查 `build.gradle` 的 repositories，配镜像 |
| `Unable to download gradle-8.13-bin.zip` | 镜像不可用 | 换成 `services.gradle.org` 官方地址 |
| `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain` | `gradle-wrapper.jar` 缺失（被 .gitignore 误排除） | 确认 `gradle/wrapper/gradle-wrapper.jar` 在库中（本仓库已提交） |
| `SDK location not found` | 无 `local.properties` 也无 `ANDROID_HOME` | 见上文第 3 节 |
| Release 构建报 `Missing class` / 混淆后崩溃 | R8 激进裁剪 | 本工程 `lint.abortOnError=false`，release 默认未开启额外 keep；日常自用请用 debug 包 |

---

## 6. 装到手机后的必要设置（否则「短信到了却不响」）

这是本项目最容易踩的坑，与代码无关，全是系统开关。安装后请依次确认：

1. **允许接收短信**（首次保存规则时会请求）。
2. **允许通知**（Android 13+ 会弹窗；拒绝后 `notify()` 静默失败，等于完全不提醒）。
3. **允许「全屏通知 / 全屏 Intent」**：Android 14+ 默认不给非通话/闹钟类应用。
   应用内 **设置 → 权限体检** 可一键跳转。
4. **关闭电池优化 / 设为「无限制」**：红米 K60 Pro（HyperOS）路径
   **设置 → 应用设置 → 应用管理 → 铃达 → 省电策略 → 无限制**。
5. **允许自启动**：HyperOS **手机管家 → 应用管理 → 权限 → 自启动** 打开本应用。
6. **允许锁屏通知与悬浮通知**：应用通知设置里打开「锁屏通知」。
7. 若手机装了第三方省电/清理类工具，把本应用加入白名单。

> 应用内主界面顶部状态条会显示「N 项权限未就绪」，点底部清单可逐项跳转处理。
> 设置页的 **权限体检** 也会实时显示未就绪项数量。

---

## 7. 快速自检（不依赖真机短信）

装好后按这个顺序验证核心链路，任何一步失败都能定位到具体环节：

1. **规则演练**：主界面「规则演练」→ 粘一段含关键词的示例短信 → 应显示「命中 · 关键词『xxx』」。
   这一步只验证匹配逻辑，不涉及通知与铃声。
2. **保存规则**：填关键词后点「启动」，状态条应变成「监听中 · 关键词：…」。
3. **真机短信**：用另一台手机给测试机发一条含关键词的短信（**号码留空**表示不限发送方）。
   预期：通知出现 + 闹铃响 + 震动（若开启）。
4. **重复提醒**：什么都不做，等待设定的间隔（默认 5 分钟），应再次响铃，通知文案变成
   「已提醒 N 次 · 5 分钟后再次提醒」。
5. **停止**：点通知上的「停止」或全屏页的「停止」→ 铃声立刻停、通知消失、闹钟取消
   （下拉状态栏不应再看到闹钟图标）。
6. **稍后提醒**：点「稍后提醒」→ 铃声停，间隔后再次响。
7. **锁屏全屏**：锁屏状态下发短信，应弹出全屏告警页（而不是只有横幅）。
   若只看到横幅，说明「全屏 Intent」权限没给。
8. **命中共记录**：设置页「最近命中记录」应出现刚才那条短信；
   若为空，说明规则没命中（回到第 1 步用演练排查）。

### v4.3.0 新设置补充自检

9. **双主题**：设置页切换「明亮 / 暗色」应立即换色；杀掉 App 冷启动后主题仍保持，
   且图标、系统栏图标与全屏告警页同步换色。
10. **音量档位**：依次设为「跟随系统 / 40% / 80%」并各触发一次提醒；
    停止后到系统「声音 → 闹钟音量」核对，**不应被永久改大**。选「跟随系统」时
    系统音量条应完全没有变化。
11. **自定义数值**：三个数值项选「自定义…」，分别输入合法值与越界值；
    越界应被拒绝，合法值的摘要应显示「自定义：N 单位」，退出设置页再进入仍保留。
12. **稍后提醒时长**：设为 1 分钟后点「稍后提醒」，应约 1 分钟后重响（而非固定 5 分钟）。
13. **音量键行为**：设为「停止」，在锁屏全屏页按音量键应立刻停止；
    设为「不拦截」应只调系统音量、不结束提醒。
14. **总开关 / 排除词**：正在响铃或已挂好下一轮闹钟时关掉总开关，应**立刻停铃、撤通知、
    下拉状态栏不再有闹钟图标**；之后发含关键词短信也完全不提醒。配置排除词「退订」后，
    同时含「到账」与「退订」的短信应被忽略，只含「到账」的仍提醒。

---

## 8. 本仓库的验证状态（如实说明）

- **已在 CI 完成**：每次 push 由 GitHub Actions 执行 `:app:testDebugUnitTest`、
  三项一致性静态检查（`tools/check_view_types.py`、`check_res_refs.py`、`check_page_ids.py`）
  与 `:app:assembleDebug`，并上传带固定签名的 APK 产物。构建记录见 Actions 页。
- **已完成（本地静态）**：Java 括号 / 字符串状态机配平（33 个文件）、资源引用全量解析、
  布局 id 与 `findViewById` 交叉比对、中英文案键名与格式化占位符数量对称、XML 良构性、
  全仓源码 NUL 字节扫描。
- **未完成**：**真机链路**。生成此仓库的环境没有 adb / JDK / Android SDK，也没有第二张 SIM 卡，
  因此「锁屏全屏弹出」「定时重复」「重启后恢复」「强杀后音量还原」等只能在手机上人工验收，
  清单见第 7 节。若首次 `assembleDebug` 出现依赖解析差异，按第 5 节对照表处理。
