[![Build debug APK](https://github.com/gui8912/bellgram/actions/workflows/build-apk.yml/badge.svg)](https://github.com/gui8912/bellgram/actions/workflows/build-apk.yml)

> **铃信 / Bellgram（本目录为二次开发版本 v4.0.1 · Android 16 合规版 · 已过独立复审 · 已编译出包）**
> - 应用显示名已由「Priority Alerts / 优先提醒」改为 **「铃信」/「Bellgram」**；
>   工程目录、`applicationId`（`com.saiga.find.messagefinder`）与包名**保持不变**，覆盖安装不受影响。
> - 上游项目：saiga006/message-finder（GPL-3.0），原始版本 2.9.8
> - **想直接装到手机上 → 装 `bellgram-v4.0.1-debug.apk`，或从仓库 Actions 页面下载**
>   （见下方「安装包」一节）；想自己编译 → 看 [BUILD.md](BUILD.md)（环境要求、命令行、报错对照、8 步真机自检）
> - **最新复审结论 → [REVIEW_REPORT_V5.md](REVIEW_REPORT_V5.md)**（v4.0.1，独立复审 7 个维度，
>   推翻了上一版 6 条结论，修掉 **13** 处缺陷，其中 4 处会直接破坏核心功能）
> - **完整改造记录 → [AUDIT_REPORT_V4.md](AUDIT_REPORT_V4.md)**
>   （**[AUDIT_REPORT.md](AUDIT_REPORT.md)** 是 v3.0.0 历史存档，其中 4 项结论已被推翻，阅读时以新版为准）
>
> ### 安装包
> - 仓库 **Actions → Build debug APK → 最近一次成功运行 → Artifacts → `bellgram-debug-apk`**
>   （private 仓库需登录；Artifact 保留 30 天）
> - 已导出一份放在工程目录上一级：`bellgram-v4.0.1-debug.apk`
>   9.63 MB / `versionCode 19` / `versionName 4.0.1` / `targetSdk 36`，实测包名、权限、
>   组件导出状态均符合预期（核验过程见复审报告 §12.4）
> - 每次 push 到 `main` 自动跑单测 + 重新出包，见 `.github/workflows/build-apk.yml`
>
> ### v4.0.1 独立复审修掉的关键问题
> - **双重响铃（老用户必现）**：静音渠道沿用了 v3 的 `priority_alert_v2`，而通知渠道的声音
>   **创建后不可修改**，v3 该渠道是带提示音建的 → 升级用户会出现「通知音 + 播放器」双响。
>   已换新 ID 并清理旧渠道。
> - **全屏页误弹**：`build()` 原来无差别给所有通知挂 `setFullScreenIntent`，
>   导致「提醒已结束」「稍后提醒」这类状态通知在锁屏时也会弹全屏页，服务前台通知还可能
>   与告警页互相触发。已改为只有真正要叫人的那一轮才带。
> - **铃声停不下来**：单轮时长选「一直响到手动停止」时，若后台停止服务被系统拒绝，
>   铃声会无限响。已补 `stopService` 兜底。
> - **号码校验静默改写配置**（由 CI 单元测试发现，逐行复审也没看出来）：
>   `isValidInput()` 只看 `stripSeparators()` 之后的长度，而后者会丢弃一切非数字字符。
>   于是 `138 0013 8000 转 1234` 被判为合法，存进配置的却是原始串，`canonical()` 再取
>   末 11 位就成了**另一个号码**——短信永远不响，界面上却完全正常。已加字符白名单。
> - **旋转屏幕铃声重启** / **设置页重复装载 Fragment** / **MIUI 上全屏页被锁屏盖住** /
>   **配置损坏导致提醒静默失效** —— 均已修复，逐条见复审报告 §1 / §12.2。
> - **`gradle-wrapper.jar` 仍是 Gradle 4.6**（上一版只改了 properties 没换 jar），
>   已替换为官方 8.13 引导器，这是「克隆后能直接构建」的前提。
>
> ### 功能总览（v3 起累积，v4 保留）
> - 构建链：AGP **8.11.1** / Gradle **8.13** / JDK **17** / **compileSdk & targetSdk 36**（Android 16）
> - 主链路：`SMS_RECEIVED` → 带闹铃音高优先级通知 → 锁屏全屏告警页（用户可见时才启动 FGS）
>   → 单轮铃声/震动 → `AlarmManager.setAlarmClock()` 精确闹钟排下一轮 → 重复通知
> - 提醒策略：响铃/震动独立开关；单轮时长（直到手动停止 / 30s / 60s / 2min）；
>   每 N 分钟自动再提醒（默认 5 分钟、最多 4 轮）；轮次进度直接写在通知上
> - 规则：任意 / 全部关键词两种匹配模式；夜间静音时段（只震动）；规则演练（粘一段短信立即判断）
> - 交互：锁屏全屏告警、通知上「停止」「稍后提醒」、最近 20 条命中记录、权限体检
>
> ### 构建与真机注意
> - **已通过 GitHub Actions 实际编译**：JDK 17 + Gradle 8.13 + AGP 8.11.1 + platform 36，
>   单测与 `assembleDebug` 均为 success；产出 APK 已核验包名 / 版本 / targetSdk / 组件导出状态。
>   但**未在真机验证**——装到红米 K60 Pro 后请按 `REVIEW_REPORT_V5.md` §11.4 的 4 条逐项确认。
>   本地编译见 `BUILD.md`。
> - 红米 K60 Pro / HyperOS 必读：自启动=开、省电策略=无限制、允许锁屏与悬浮通知、
>   允许通知、允许「全屏通知」（Android 14+ 默认不给）、允许接收短信。
>   缺任一项表现为「短信到了却不响」或「只响一次就没了」。

---

# message-finder
An app to trigger **notifications** when receiving **priority messages**(*determined by user keyword*) 

<img src="https://github.com/saiga006/message-finder/blob/master/priority_notifications.png" >

### How to use:

  1. User inputs contact number(optional) and the keyword(required), for which SOS kind of notification will be raised.
  2. Press trigger button to save the config.
  3. So that the app uses it everytime, when a sms is received and validates it, even after reboot it works.
  4. User needs to accept the READ SMS permission or else the app wouldn't work fine as expected.
  5. To stop the SOS app functionality, press the clear config button.
  6. Users can see their saved config on pressing settings icon inside the app
  
  <img src="https://github.com/saiga006/message-finder/blob/master/2.png" >

### Features:

  - It supports multiple keywords as input to identify the text (either in comma or space seperated format).
  - Triggers notification, when any one of the keywords are matched with received message.
  - On receiving notification, ringtone is played at high volume, even if the phone is in silent mode.
  - OnBoarding Screen for FTI by User

  <img src="https://github.com/saiga006/message-finder/blob/master/5.png" width="400" height="225" >
  
## Download the app at:
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">](https://f-droid.org/en/packages/com.saiga.find.messagefinder/)

  
## Beta Release V2.9.6

#### Beta 2.9.6 Changelog (Play Store Variant):
  - Removed the contacts permission and disabled the experimental auto suggestion feature currently
    (Source Code is available)

#### Beta 2.9.0 Changelog:
  - Added support for auto suggestion experimental feature through app settings, by default it is disabled for user 
  - Added support for default ringtone inside the app resource to be used for priority alerts
  - New icons and modified the app name to "Priority Alerts" as it is synonym with the functionality
  - Refactored permission dialog flow and removed the dependencies for read external storage and large heap feature
  - Bug fix:
      1. Efficient solution for bitmap rendering is proposed to fix the onboarding crash issue appeared in low end
         devices

 
#### Beta 2.5.0 Changelog:
  - Added support for app settings screen, user can now see their saved config after reboots and closing the app too.
  - Displays saved app configurations, app versions and added auto suggestion as an experimental feature
  - More preference is given to contact picker as it is less error prone and doesn't require read contacts permission
    from the user like the auto suggestion feature

#### Beta 2.1.0 Changelog:
  - Added support for contact picker feature, now user can pick their contacts directly from contact app, instead of typing manually
  - Refactored and redefined permission dialog flow for auto suggestion feature
  - Bug Fix:
    1. Temp crash fix for bitmap memory consumption in FTI page for low end devices.
    
#### Beta 2.0.0 Changelog:
  - Support for Android 10 
  - Onboarding Screens for first time users
  - Error Checking func. for contact input field
  - App Size reduction by half 28MB to 13MB
  - Bug Fixes
    1. Permission Dialog dont ask again option fix
    2. Bugs related to edit text fields


#### Beta 1.3.0 Changelog:
  - Support for Android 10 
  - Onboarding Screens for first time users
  - App Size reduction by half 28MB to 13MB
  - Bug Fixes
    1. Permission Dialog dont ask again option fix
    2. Bugs related to edit text fields


#### Beta 1.2.0 Changelog:  
 
  - App permissions can be configured easily with new request permissions design
  - Bug Fixes
    1. IME popup glitches, UI glitches, landscape mode glitches
    2. Fixed runtime Permission request
    3. Fixed the ringtone issue that was occuring in some phones
    4. Snackbar related bug fixes
    

### Compatibility: 

  - Currently expected to work on phones from Android M to Android Q.

### Code Verbosity Level: 

  - Full verbose comments in code is available

### App Screenshots:

  1. <img src="https://github.com/saiga006/message-finder/blob/master/App_Notification.png" width="150" height="300">
     
  > "Notification when priority message is received"
  
  2. <img src="https://github.com/saiga006/message-finder/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="150" height="300">
  
  > "Activity Screen of Message Finder, where user inputs the contact number and keyword, for which SOS has to be raised"
  
  3. <img src="https://github.com/saiga006/message-finder/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="150" height="300">
  
  > "Example user input"
  
  4. <img src="https://github.com/saiga006/message-finder/blob/master/privacy policy.png" width="150" height="300">
  
  > "App Settings Screen"
  
  5. <img src="https://github.com/saiga006/message-finder/blob/master/FTI1.png" width="150" height="300"> <img src="https://github.com/saiga006/message-finder/blob/master/FTI2.png" width="150" height="300"> <img src="https://github.com/saiga006/message-finder/blob/master/FTI3.png" width="150" height="300">
  
  > "FTI Screens"

#### Medium Arcticles ( which describes this app design principles along with UML diagrams and steps to publish such an app in an app store):
  - [App design principles with UML diagrams](https://medium.com/the-uncharted/publishing-an-android-app-d8e3bca598b3)
  - [Debugging and publishing of android app](https://medium.com/the-uncharted/publishing-an-android-app-part-2-7b8787861e62)

#### Resources:
  - [Android Developer Guide - Notifications](https://developer.android.com/guide/topics/ui/notifiers/notifications)
  - [Android Developer Guide - MediaPlayer](https://developer.android.com/guide/topics/media/mediaplayer)
  - [Android Developer Guide - Services](https://developer.android.com/guide/components/services)
  - [Android Developer Guide - Foreground Services](https://developer.android.com/guide/components/services#Foreground)
  - [Android Developer Guide - Toast](https://developer.android.com/guide/topics/ui/notifiers/toasts)
  - [Media Player State Machine](https://developer.android.com/images/mediaplayer_state_diagram.gif)
  - [Styles and Themes Codepath Tutorial](https://guides.codepath.com/android/Styles-and-Themes)
  - For nitty gritty details on Styles and Themes - [Android Developer Guide](https://developer.android.com/guide/topics/ui/look-and-feel/themes)
  - For Receiving SMS in Android - [Tutorial](https://google-developer-training.github.io/android-developer-phone-sms-course/Lesson%202/2_p_2_sending_sms_messages.html)
  - Requesting Runtime Permissions in Android - [Android Developer Guide](https://developer.android.com/training/permissions/requesting#perm-check)
  - Shared preferences in Android - [Android Developer Guide](https://developer.android.com/training/data-storage/shared-preferences) 
  - [Vibration effect android tutorial](https://proandroiddev.com/using-vibrate-in-android-b0e3ef5d5e07)
  - Codepath Tutorial on [Drawable](https://guides.codepath.com/android/drawables)
  - Background Service Limitations [Android Oreo](https://developer.android.com/about/versions/oreo/background#services) and [Android Q](https://developer.android.com/guide/components/activities/background-starts)
  - Good tutorial on [custom notifications](https://medium.com/hootsuite-engineering/custom-notifications-for-android-ac10ca67a08c)
  - For [controlling volume and about fixed volumes devices](https://developer.android.com/guide/topics/media-apps/volume-and-earphones) in android
  - For app [logo design](https://www.freelogodesign.org/) and adding [app icons](https://medium.com/@ujikit/add-icons-to-the-android-application-from-android-studio-ide-cd1af9348749) and [notification icons](https://developer.android.com/studio/write/image-asset-studio?hl=de#create-notification)
  - Adding Splash Screen [Tutorial](https://android.jlelse.eu/launch-screen-in-android-the-right-way-aca7e8c31f52)
  - Integration [Material Design](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md)
    and customizing [Snackbar](https://material.io/develop/android/components/snackbars)
  - App Signing [Tutorial](https://youtu.be/akDXw9n3gFY)
  - Proguard and Apk Size Reduction [Writeup](https://medium.com/androiddevelopers/practical-proguard-rules-examples-5640a3907dc9)
  - Add Onboarding Screen [Tutorial](https://www.youtube.com/watch?v=byLKoPgB7yA)
  - Add Circular ImageView [Library](https://github.com/hdodenhof/CircleImageView)
  - Loading Large Bitmaps Efficiently [Android Guide](https://developer.android.com/topic/performance/graphics/load-bitmap),
    [StackOverflow](https://stackoverflow.com/questions/21392972/how-to-load-large-images-in-android-and-avoiding-the-out-of-memory-error)
  - Contact Picker [Tutorial](https://www.crazygeeksblog.com/2017/01/android-contact-picker-example/)
  - Play default Ringtone [Tutorial](https://abhiandroid.com/androidstudio/add-audio-android-studio.html)
  - App Settings Screen [Blog Post](https://medium.com/google-developer-experts/exploring-android-jetpack-preferences-8bcb0b7bdd14) and App preferences [Blog Post](https://medium.com/@JakobUlbrich/building-a-settings-screen-for-android-part-1-5959aa49337c)
  - Obviously StackOverflow for issues during development and bug fixing :-)
  
