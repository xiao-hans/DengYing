# iosApp —— CMP iOS 壳工程（P5-6 收敛版）

**整工程仍未在 macOS 上构建过**：本目录全部文件在 Windows 上落盘。
P5-6 把能静态做到的全做了——入口符号名、工程层级、framework 搜索路径与链接方式、
ATS 例外、版本号、脚本相位顺序，全部按 KGP 源码实证改到一致，并逐项校验通过。
**剩下的只有「Xcode 能不能打开、能不能链接、能不能跑」**，见文末。

## 文件清单

| 文件 | 作用 |
|---|---|
| `DengYing.xcodeproj/project.pbxproj` | 单 target `DengYing`。构建相位顺序 `Compile Kotlin Framework → Sources → Frameworks → Resources`；`FRAMEWORK_SEARCH_PATHS = "$(SRCROOT)/../ui/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"`；`OTHER_LDFLAGS = ("$(inherited)", "-framework", DengYing)`；`ENABLE_USER_SCRIPT_SANDBOXING = NO` |
| `DengYing.xcodeproj/xcshareddata/xcschemes/DengYing.xcscheme` | 共享 scheme（`BlueprintIdentifier = 1A000501`），`xcodebuild -scheme DengYing` 直接可用，不依赖 Xcode 自动生成 |
| `DengYingApp.swift` | SwiftUI 入口。`UIViewControllerRepresentable` 包 `MainViewControllerKt.MainViewController()`，外层 `OrientationHostingController` 回答 `supportedInterfaceOrientations` / `prefersStatusBarHidden` |
| `Info.plist` | bundle 标识、版本（与 Android 同步：`1.1.0` / `1000100`）、方向、启动屏、`NSAppTransportSecurity` 例外（见下） |
| `build-phase.sh` | 构建相位脚本，末行 `exec ./gradlew :ui:embedAndSignAppleFrameworkForXcode` |

## 三条接线为什么是这样（读 KGP 源码得出，不是猜的）

依据 `kotlin-gradle-plugin-2.4.21-sources.jar` 里的 `XcodeEnvironment.kt` 与
`AppleXcodeTasks.kt`：

1. `embedAndSignAppleFrameworkForXcode` 把 framework 写到
   `<project>/build/xcode-frameworks/<CONFIGURATION>/<SDK_NAME>/`，
   **然后自己**拷进 `TARGET_BUILD_DIR/FRAMEWORKS_FOLDER_PATH` 并重签。
   ⇒ 所以搜索路径必须是 `xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`；
   ⇒ 所以**不能**再手写 Embed Frameworks 相位 + `CodeSignOnCopy`（那是重复签名）。
2. 没有 FileReference 之后，链接靠 `OTHER_LDFLAGS` 的 `-framework DengYing`。
3. KGP 的 `CheckSandboxAndWriteProtectionTask` 会因脚本沙箱拒绝写入而失败
   ⇒ `ENABLE_USER_SCRIPT_SANDBOXING = NO` 是硬要求。
4. 脚本相位必须排在 Sources **之前**，且带 `alwaysOutOfDate = 1`（否则 Xcode
   认为无输入无输出，直接跳过）。

## `Info.plist` 的 ATS 例外

`NSAllowsArbitraryLoads = false`，只精确放开与 `core/Sources.kt` 里明文端点
一一对应的三项：`43.248.128.122`、`103.36.167.27`、`qkys.qukanwh.com`，
均 `NSExceptionAllowsInsecureHTTPLoads = true` 并钉 `TLSv1.2`。

**已知遗留**：`shared/src/commonMain/kotlin/com/dengying/player/data/source/adapters/JuzhiRoot.kt:32`
的 `ROSS_URL` 会运行期发现明文根域，无法静态白名单。当前取舍是不放开全局
`NSAllowsArbitraryLoads`；该源预计报 `NSURLErrorDomain -1022`（`AvPlayerEngine.classify()`
已单列并归入 `Network`）。真机实测后再定「标 `playGap`」还是「加域通配例外」。

## 在 macOS 上要做的三件事

```sh
plutil -lint iosApp/Info.plist
plutil -lint iosApp/DengYing.xcodeproj/project.pbxproj
xcodebuild -list -project iosApp/DengYing.xcodeproj
```

然后把 `DEVELOPMENT_TEAM` 填成真实 Team ID（现在是空串，写假值只会让
`xcodebuild` 报更难的错），再 `xcodebuild -scheme DengYing -sdk iphonesimulator`.

**不需要 xcodegen 重建**：手写工程已按上节四条实证修到正确形态。

## 真机分发前提（与 docs/09 §9.5 一致）

免费 Apple ID 签名 7 天过期且不可分发他人；给朋友长期安装需付费开发者账号
（TestFlight 或 Ad Hoc）。这属打包环节，不影响本工程骨架的编译验证。

## 投屏 TODO（未实现，不要写成「已支持」）

Android 侧已接 MediaRouter + GMS Cast 本地下发通道（见各工程 `docs/02` §0.1），
**iOS 原生侧在本分支只写方案，不落实现代码**。原因：本环境是 Linux，
没有 macOS / Xcode，不能编译 Swift、不能链接 `AVKit` / `AVFoundation`、
不能做真机 AirPlay 验证；贸然写 Swift/ArkTS 会造成「看起来已支持」的假象。

方案（在 macOS + Xcode 上落地时执行）：

| 项 | 内容 |
|---|---|
| 目标文件 | `iosApp/DengYingApp.swift`（新建 `AirPlayPicker.swift` 亦可）、`ui/src/iosMain/.../platform/CastLauncher.ios.kt` |
| API | `AVRoutePickerView`（`AVKit`，iOS 11+）经 `UIViewRepresentable` 包进 SwiftUI；或 `MPVolumeView`（`MediaPlayer`）的 AirPlay 按钮。`AVPlayer.allowsExternalPlayback = true`、`usesExternalPlaybackWhileExternalScreenIsActive` 按会话需要设置 |
| 共享层接线 | `UiDeps.castLauncher` 目前缺省 `UnsupportedCastLauncher`（点击会明确提示「当前平台尚未接入投屏通道」）。需在 `ui/src/iosMain` 提供 `CastLauncher` 的 `actual` 或等价平台注入，把 `launch()` 映射到唤起 AirPlay 路由选择器 |
| 系统能力 | AirPlay 由系统弹路由选择器，应用不自建云端服务、不新增 entitlement；`Info.plist` 无需因 AirPlay 加键 |
| 缺的环境 | macOS + Xcode + iOS 真机/模拟器 + AirPlay 接收端（Apple TV / 支持 AirPlay 的电视） |
| 待验证项 | 路由选择器能弹出、选中接收端后 `AVPlayer` 切到外部播放、后台播放保持、断开回退本机、`CastUtil` 失败分流文案在 iOS 侧呈现 |

`ui/src/iosMain` 侧目前**不改**：`applyDefaultHierarchyTemplate=false` 且 iosMain 手工接线，
新增 expect/actual 需同步检查 `iosArm64Main` / `iosSimulatorArm64Main`，没有 Xcode
就无法确认编译，故留作明确 TODO。
