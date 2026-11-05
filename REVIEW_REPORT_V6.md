# 独立复审报告 v6 —— 首次启动闪退的根因定位与全量一致性核查

> 复审对象：`bellgram` 仓库 `main` 分支（v4.1.1 之后的工作区）
> 复审时间：2026-11-06
> 复审前提：**不采信前几轮复审（AUDIT_REPORT_V4 / REVIEW_REPORT_V5）的结论**，全部重新核查。
> 触发原因：真机安装 v4.1.0 后**打开即闪退**。

---

## 0. 结论摘要

| 级别 | 数量 | 内容 | 状态 |
|---|---|---|---|
| **P0 崩溃** | 1 | 引导页 `SliderAdapter.isViewFromObject()` 强转 `RelativeLayout`，实际根布局是 `LinearLayout` → `ClassCastException`，首次安装启动必崩 | **已修复** |
| P2 UI | 1 | 通讯录联想下拉项高度算出来是 0，点开是空白 | **已修复** |
| P3 规范 | 2 | 残留英文调试注释、未使用的 import | 已清理 / 部分保留 |

**关键结论：闪退是 v3「界面美化」留下的隐性缺陷，不是 v4 架构重构引入的。**
它在 v4.1.0 之前的版本里一直存在，只是因为**从未在真机上走完"首次启动"这条路**，一直没有暴露。

**前两轮复审为什么没发现**：v5 复审的 7 个维度全部围绕 Java 源码内部逻辑展开（空指针、并发、资源泄漏、权限链路），
**没有做"布局 XML ↔ Java 类型"的跨文件一致性校验**。而这次的崩溃点恰好落在两者交界处——
XML 里改了控件类型，Java 里还在用旧类型强转。编译期完全不报错，只有真机运行才会炸。
本次复审为此补了三个静态检查脚本（见 §7），把这类问题变成可自动发现的。

---

## 1. 逻辑正确性

### 1.1 P0 崩溃的完整因果链

```
用户点图标
  └─ MainActivity.onCreate()
       └─ isOnboardingEnabled() == true（首次安装，组件处于 DEFAULT）
            └─ startActivity(Onboarding.class)
                 └─ Onboarding.onCreate()
                      └─ slideView.setAdapter(pageAdapter)
                      └─ slideView.addOnPageChangeListener(...)  → 触发 ViewPager 首次布局
                           └─ PagerAdapter.isViewFromObject(view, object)
                                └─ return view == (RelativeLayout) object;   ← ★ 崩溃点
                                     object 实际是 LinearLayout 的实例
                                     → java.lang.ClassCastException
```

**为什么是"必崩"而不是"偶发"**：`isViewFromObject` 是 ViewPager 在**布局阶段**判定
"这个 child 属于哪个 item"的必经调用，只要 ViewPager 里有 item 就一定会被调用。
引导页是本应用的第一个界面，所以现象就是**装完点图标 → 白屏一瞬 → 闪退回桌面**。

**为什么会漏这么久**：`page_layout.xml` 的根节点在 v3 界面重做时由 `RelativeLayout` 改为
`LinearLayout`（为了适配小屏与横屏，见该文件头部注释），但 `SliderAdapter` 里两处强转没有同步。
`javac` 不会检查 XML 里的控件类型，`aapt2` 也不会检查 Java 里的类型转换——
这种"跨语言契约"只有运行期或专门的静态检查才能发现。

### 1.2 修复

```java
// 修复前
public boolean isViewFromObject(View view, Object object) {
    return view == (RelativeLayout) object;
}
public void destroyItem(ViewGroup container, int position, Object object) {
    container.removeView((RelativeLayout) object);
}

// 修复后
public boolean isViewFromObject(View view, Object object) {
    return view == object;                    // PagerAdapter 只需要引用相等
}
public void destroyItem(ViewGroup container, int position, Object object) {
    container.removeView((View) object);
}
```

**为什么这样改是对的**：`PagerAdapter` 的契约只要求"判断这个 View 是不是这个 Object 对应的页面"，
标准实现就是引用相等；官方 `PagerAdapter` 文档示例同样是 `return view == object`。
去掉强转后，页面根布局将来再换成任何容器都不会重蹈覆辙——这是从"修一个值"变成"消掉整类风险"。

### 1.3 顺带复核的其他崩溃面（均未发现新问题）

| 检查项 | 方法 | 结果 |
|---|---|---|
| `findViewById` 类型与 XML 控件类型是否兼容 | 脚本全量比对 22 个 Java 文件 / 8 个布局 | 仅 1 处（已修），其余兼容 |
| Java 引用的 `R.id/R.drawable/R.string/...` 是否存在 | 脚本全量比对资源定义表 | 无缺失（2 处为 `android.R.string` 误报） |
| 页面用到的 id 是否在**该页面自己的布局**里 | 脚本按 `setContentView` 追溯（含 `<include>` 递归） | 无缺失（`contact_title` 来自列表项布局，属误报） |
| 自定义 View 标签是否有对应依赖 | 扫描所有 `<com./de./org.` 标签 | `circleimageview` / `material` 均已在 `dependencies` 声明 |
| `getString(id, args...)` 占位符数量是否匹配 | 脚本比对 `%n$x` 与实参个数 | 无异常（2 处为跨行调用误报，已人工确认） |

---

## 2. 改动合理性

**图标更换（v4.1.1）**：纯资源替换，不动任何逻辑。
- 新增 `app_logo_background.png`（渐变背景层）与改写的 `app_logo_foreground.png`（前景层），沿用工程既有的 `app_logo_*` 命名，没有引入新的资源体系。
- `mipmap-anydpi-v26/app_logo.xml` 的 `<background>` 由 `@color/...` 改为 `@mipmap/...`，这是自适应图标的标准写法。
- **未改** `color/app_logo_background`（`#FFFFFF`）：它同时被 `windowBackground` 与 `primaryTextColor` 引用，属于页面底色，与图标背景不是一回事。改名会连带改动主题，超出本次范围。
- `drawable/app_logo.webp` 由旧商标图（6 KB）换为新图标（29 KB），在浅色卡片上的显示效果一致（圆角方块）。

**新增 `tools/` 三个脚本**：不参与 Gradle 构建，不打进 APK，只在开发期做一致性校验。
判断标准是"它解决的是反复出现的同一类问题"——本次崩溃正是这类问题，且人工审查看不出来，故保留。

**未做的改动**：没有新增任何依赖，没有改包名、签名、minSdk/targetSdk，没有动提醒链路。

---

## 3. 代码规范

- 修复处的注释统一用 `// modified: 【崩溃修复 v4.1.2】` 前缀，写明**原实现是什么、为什么错、为什么这样改**，与工程既有约定一致。
- 删除了 `SliderAdapter` 中已无用的 `import android.widget.RelativeLayout;`。
- `contacts_view.xml` 的 `tools:text` 由 `@tools:sample/date/mmddyy`（模板占位）改为真实示例（`张三` / `13800138000`），预览更直观。
- **保留未改**：`SliderAdapter` 里上游项目留下的大段英文注释（含 `Bitmap.Config.HARDWARE` 的历史说明）。这些注释有溯源价值，删掉反而丢失"为什么用 HARDWARE 配置"的依据。

---

## 4. 兼容性与可移植性

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8.11.1 的硬性要求 |
| Gradle | 8.13 | `gradle-wrapper.jar` 已替换为官方 8.13，`gradlew` 可执行位已入库 |
| Android SDK | Platform 36 + Build-Tools | `compileSdk 36` |
| 依赖 | 无新增 | 全部沿用既有版本，不存在版本冲突面扩大 |
| `tools/` 脚本 | Python 3.8+ | **零第三方库**（只用 `re` / `glob` / `os` / `collections`） |

**拉取即可运行**：`git clone` 后用 Android Studio 打开，或 `./gradlew assembleDebug`。
若本机没有 SDK，直接下载 Releases 里的 APK——CI 用固定 keystore 签名（`keystore/bellgram.jks`），
同一 `versionCode` 的产物字节一致，可覆盖安装。

**minSdk 23 的兼容性复核**：本次改动只涉及布局属性（`paddingStart` 是 API 17+、`layout_weight` 无版本限制）
与 ViewPager 回调，均不影响 6.0 设备。

---

## 5. 变更风险

| 风险 | 等级 | 说明与缓解 |
|---|---|---|
| **修复未在真机验证** | 中 | 本环境无 adb / Android SDK，无法本地跑真机。已通过三项静态检查 + CI 编译 + 单测间接验证；**必须由真机确认"首次启动能走完 3 屏引导"** |
| 装过 v4.1.0 的用户升级 | 低 | `versionCode 22 > 20`，同签名可覆盖；**若曾装过 v4.0.1，需先卸载一次**（v4.1.0 起换了固定签名） |
| 图标缓存不刷新 | 低 | 部分 ROM 换图标后桌面仍显示旧图，重启启动器或重启手机即可 |
| 旧图标残留引用 | 极低 | 已确认 `@drawable/app_logo` 的 3 处引用（splash / 主界面 / 引导页）都指向新图 |
| `ic_launcher_foreground.xml` 未使用 | 极低 | v4 重构后遗留的孤立资源，不进 APK 逻辑，保留不影响运行（可后续清理） |

**前轮风险点的复核结论**：
- 「未编译（最高风险）」——v5 §11.1 提出的这条已被 CI 实证推翻，APK 已成功产出并核验。
- 「装过 v4.0.1 需卸载」——仍成立，且无法通过代码规避（签名不同）。
- 「通知权限被拒 = 完全静默」——仍成立，属 Android 平台限制，已有状态条警示。

---

## 6. 代码溯源

本次全部改动**均为自研代码/资源**，未摘抄任何第三方实现：

| 改动 | 来源 |
|---|---|
| `SliderAdapter` 两处修复 | 依据 AndroidX `PagerAdapter` 接口契约自行编写 |
| 图标设计与生成脚本 | 原创（Pillow 绘制 + 向心 Catmull-Rom 轮廓） |
| `tools/` 三个检查脚本 | 原创 |
| `contacts_view.xml` 调整 | 修改本项目自有布局 |

**复核既有出处标注**（未改动，仅核对是否完整）：
- `SliderAdapter.decodeSampledBitmapFromResource` / `calculateInSampleSize`：已标注来自 Android 官方培训文档《Loading Large Bitmaps Efficiently》，并说明上游 GPL-3.0 项目已引入 ✓
- `EdgeToEdgeHelper`：已标注参考官方《Edge-to-edge / Handle insets》，未复制第三方代码 ✓
- `SettingsActivity`：已标注参考官方《Migrate to AndroidX Preference》 ✓
- `SliderAdapter` 的 `Bitmap.Config.HARDWARE` 优化：上游注释保留了原始出处说明 ✓

---

## 7. 测试方案

### 7.1 自动化（已可复现）

```bash
# 1) 单元测试（纯 JVM，覆盖轮次策略 / 号码规范化 / 关键词匹配）
./gradlew testDebugUnitTest

# 2) 跨文件一致性检查（本次新增，正是能提前发现本 P0 的手段）
python tools/check_view_types.py     # findViewById 类型 vs XML 控件类型
python tools/check_res_refs.py       # R.xxx 引用是否存在
python tools/check_page_ids.py       # 页面 id 是否属于该页布局
```
三个脚本的期望输出：`不匹配 0 处`。CI 目前只跑单测，**建议后续把这三个脚本加进 workflow**（另开任务，不在本次范围）。

### 7.2 真机验收清单（必须逐条走）

- [ ] **全新安装**（先卸载旧版）→ 点图标 → 应进入引导页第 1 屏，**不闪退**
- [ ] 引导页左右滑动 3 屏，指示点颜色依次变化，「上一步」在第 1 屏隐藏
- [ ] 点「完成」→ 进入主界面，**不闪退**
- [ ] 主界面输入关键词 → 点「启用提醒」→ 顶部状态条变为「监听中」
- [ ] **发一条含关键词的短信** → 应弹出全屏告警页 + 响铃 + 震动
- [ ] 告警页点「停止」→ 铃声立即停，页面关闭
- [ ] 告警页点「稍后提醒」→ 通知栏出现「X 分钟后再次响铃」
- [ ] 等 5 分钟 → 自动再次响铃（验证 `setAlarmClock` 链路）
- [ ] 设置页逐个开关切换，退出重进，值应保持
- [ ] 主界面底部「权限清单」可点，能弹出体检对话框
- [ ] 主界面「规则演练」：输入一段含关键词的文字，应提示命中

### 7.3 若仍闪退的取证方法

本环境无 adb，无法远程取日志。若 7.2 第 1 条仍失败，请任选其一提供堆栈：
1. 手机 `设置 → 开发者选项 → 错误报告 → 生成完整报告`，把 `bugreport-*.zip` 发我；
2. 或连电脑执行 `adb logcat -b crash -d > crash.txt`；
3. 或截图闪退提示（部分 ROM 会显示"应用已停止运行"的详细按钮）。

---

## 8. 本次改动的文件清单

| 文件 | 改动 |
|---|---|
| `SliderAdapter.java` | 修复 CCE（2 处）；删除无用 import |
| `contacts_view.xml` | 修复下拉项 0 高度；示例文本改真实值 |
| `app/build.gradle` | `versionCode 22` / `versionName 4.1.2` |
| `strings.xml`（values / values-zh-rCN） | `current_app_version` → 4.1.2 |
| `README.md` | 版本表补 v4.1.1 / v4.1.2 |
| `tools/check_view_types.py` | 新增 |
| `tools/check_res_refs.py` | 新增 |
| `tools/check_page_ids.py` | 新增 |
| 图标资源（5 档密度 × 4 类 + webp） | v4.1.1 已提交，本次未再改动 |
