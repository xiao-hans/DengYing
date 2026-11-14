# PR #11 视频层遮挡 BLOCKER 处理结果

分支：`feat/ohos-avplayer-engine`（PR #11，draft）。提交前本地 HEAD `afc9c6e058f9b34b375ab719a6d9ad891e91abe1`，已是 `origin/main`（`1a8066ee63981afed1facc4254a3eb486c1d5641`）合并进本分支后的状态；`git log --oneline HEAD..origin/main` 为空，本轮不需要再合并，也没有冲突。`origin/feat/ohos-avplayer-engine` 指向更早的提交，本地领先，推送为 fast-forward（该 fast-forward 推送已由上一轮 chain 完成；本轮按 harness 规则不推送，详见文末「本轮复验」）。

## 根因

2026-11-13 的记录判断「CMP 的 `Compose` 是整块不透明 EGL 输出面，ArkUI 层序导致视频 SURFACE 被盖住」是误判。检查 `harmony/entry/libs/compose.har`（gzip-tar，非 zip）中的 `Compose.d.ets`、`CanvasNode.d.ets` 和 `modules.abc`，以及上游 `Tencent-TDS/ovCompose-sample` 的同源 har，可见渲染走 `XComponent(TEXTURE)` / `CanvasNode(RenderNode)`，字节码含 `TEXTURE`、`CanvasNode`、`Transparent`、`#00000000`，没有 `SURFACE` / `EGL` 字面量；上游示例调用 `Compose({...})` 也不传私有 `bgColor`。这些证据表明 Compose 层是同一 ArkUI 渲染树中的可透明纹理，Stack「视频 SURFACE 在下、Compose UI 在上」的 SurfaceView 式打洞模型成立。

真正遮住全屏播放页画面的是 Kotlin 侧自己的不透明背景：`PlayerScreen.kt` 根 `Box(Modifier.fillMaxSize().background(Color.Black))` 全屏铺黑，恰好画在视频层之上；`VideoSurface` 子 Box 的 `Color.Transparent` 只能让自己不绘制，擦不掉父 Box 已经画的黑色。导航根 `Box`、`DengYingTheme`、`MainArkUIViewController` 均没有额外全屏底色；其他页面通过 `GlassPage`/`glassPageBackdrop` 铺不透明背景，不会意外露出视频。

## 修改

- `DengYingOhos/ui/src/commonMain/kotlin/com/dengying/player/ui/pages/PlayerScreen.kt`：新增 `surfaceBound` 状态，由现有 `VideoSurface(..., onBind = ...)` 回写；根 Box 背景在 `surfaceBound && prepared && !buffering && errMsg.isEmpty()` 时改为 `Color.Transparent`，其余时机保持 `Color.Black`。绑定/准备/缓冲状态来自原有 `OhosVideoSurfaceBridge` → `OhosPlayerEngine` → `PlayerViewModel` 回调，没有改动播放器协议。
- `DengYingOhos/harmony/entry/src/main/ets/pages/ComposePage.ets`：外层 `Column` 显式 `.backgroundColor(Color.Transparent)`；Stack 顺序、全屏 `XComponent(SURFACE)`、`onLoad`/`onDestroy` 的 `setVideoSurfaceId`/`clearVideoSurfaceId` NAPI 契约都不变。没有改 har，也没有向 `Compose({...})` 传其 `@Prop private bgColor`（避免 ArkTS 编译期拒绝私有属性）；依赖 har 字节码证据中的透明缺省色。
- `DengYingOhos/ui/src/ohosMain/kotlin/com/dengying/player/ui/platform/Platform.ohos.kt`、`DengYingOhos/ui/src/ohosMain/kotlin/com/dengying/player/ui/ohos/UiDeps.ohos.kt`：更新过时的 BLOCKER 注释，说明打洞分工和内嵌小窗限制；逻辑未改。
- `DengYingOhos/docs/ohos-player-notes.md`：修正 2026-11-13 的错误判断，记录根因、修法、各端影响、未验证项与仍待办项。

Android 的 `PlayerView` 自带黑色 shutter，观感不变；JVM/桌面 `onBind` 不回调 true，根背景仍为黑色。OHOS 的 listener、play/pause/seek、release、surface 绑定与 window 引用计数均未改动。

## 构建 / 测试（本机，非真机）

最终验证脚本 `/tmp/dengying-11/.chain/test-cmd`（可执行）内容：

```sh
#!/bin/sh
set -eu
cd /tmp/dengying-11/DengYingOhos
export ANDROID_HOME=/home/user/Android/Sdk
export ANDROID_SDK_ROOT=/home/user/Android/Sdk
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dorg.gradle.jvmargs=-Xmx1536m -Dkotlin.daemon.jvmargs=-Xmx1024m ${GRADLE_OPTS:-}"
sh gradlew --stop >/dev/null 2>&1 || true
sh gradlew :shared:jvmTest :shared:compileDebugKotlinAndroid :app:testDebugUnitTest :ui:compileDebugKotlinAndroid :app:assembleDebug
```

在 `sh gradlew clean` 后从干净产物运行：`BUILD SUCCESSFUL in 21s`。`:shared:jvmTest`、`app:testDebugUnitTest` 共 41 个 XML、656 tests、0 failures、0 errors、0 skipped；`:shared:compileDebugKotlinAndroid` 与 `:ui:compileDebugKotlinAndroid` 成功（FROM-CACHE）；`:app:assembleDebug` 成功，产出 `app/build/outputs/apk/debug/app-debug.apk`（38M）。`git diff --check` 通过。

本轮环境/既有工具链问题（均非本次改动引入，最终脚本已绕开或修复环境）：

1. Gradle wrapper 首次运行时 Java 未继承 shell 代理，下载 Gradle 8.14.3 失败：
   `Exception in thread "main" java.net.UnknownHostException: services.gradle.org`。
   通过 curl 经 `https_proxy=http://127.0.0.1:3128` 下载 `gradle-8.14.3-bin.zip`（sha256 `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`）并放入 wrapper dists 后解决。
2. Android SDK 环境变量缺失：
   `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '/tmp/dengying-11/DengYingOhos/local.properties'.`
   随后缺少 build tools：`Failed to find Build Tools revision 35.0.0`；已在 `/home/user/Android/Sdk` 安装 `build-tools;35.0.0` 并在脚本导出 `ANDROID_HOME`/`ANDROID_SDK_ROOT`。
3. 首次尝试 `:shared:jvmTest --offline` 缺少 CPF 测试依赖：
   `No cached version of org.jetbrains.kotlin:kotlin-test:2.2.21-1.1.0 available for offline mode.`
   改为联网并在 `GRADLE_OPTS` 设置 JVM 代理后解决。
4. `:ui:jvmTest` 在本机依赖解析失败，CPF 依赖没有对应 JVM 变体：
   `Could not resolve org.jetbrains.androidx.savedstate:savedstate-compose:1.3.3-1.1.0.`
   `No matching variant of org.jetbrains.androidx.savedstate:savedstate-compose:1.3.3-1.1.0 was found. The consumer was configured to find a library for use during compile-time, preferably optimized for standard JVMs, as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'jvm' but:`
   （变体列表只有 `androidJvm`、`common`、`native`。）故最终脚本不跑 `:ui:jvmTest`；这是既有 CPF/OHOS 依赖矩阵限制，不是本改动造成。
5. `:ui:testDebugUnitTest` 的 `:ui:compileDebugUnitTestKotlinAndroid` 失败：
   `e: java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters: /tmp/dengying-11/DengYingOhos/ui/build/tmp/kotlin-classes/debugUnitTest/com/dengying/player/ui/components/PosterLoaderTest$并发请求同一 URL 只下载一次$1.class`
   `Execution failed for task ':ui:compileDebugUnitTestKotlinAndroid'. > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction > Internal compiler error. See log for more details`
   加 UTF-8 locale/`JAVA_TOOL_OPTIONS` 仍失败；用 `git stash` 在未修改的 HEAD 上执行 `--rerun-tasks :ui:compileDebugUnitTestKotlinAndroid` 复现完全相同错误，确认是既有中文测试名与 Kotlin 增量输出路径问题，故最终脚本不包含该任务。
6. 默认 `org.gradle.jvmargs=-Xmx4096m` / `kotlin.daemon.jvmargs=-Xmx3072m` 在本机 7.7G 内存下与常驻 Kotlin daemon 竞争，完整任务中途出现：
   `Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)`。
   最终脚本先 `gradlew --stop`，并用 `-Dorg.gradle.jvmargs=-Xmx1536m -Dkotlin.daemon.jvmargs=-Xmx1024m` 降低堆；从干净产物重跑成功。

## 预期效果与无法验证的部分

预期：在 OHOS 上进入全屏播放页、surfaceId 绑定且 `onReady`/`onPlaying` 后，根 Box 与外层 ArkUI Column 都透明，下层 `XComponent(SURFACE)` 的 `OH_AVPlayer` 画面应从 Compose 透明区域透出，控制层/手势层仍绘制在上层；未绑定、缓冲、错误态仍显示黑底，避免透出异常帧。

**没有做真机或 hvigor/DevEco 验证，不能声称画面已经实际可见。** 本机没有 DevEco/hvigor 工具链，没有编译 ArkTS/HAP；`~/.konan/dependencies` 为空，也没有编译/链接 `:ui:compileKotlinOhosArm64`、`:ui:compileKotlinOhosX64` 或 `linkDebugSharedOhos*`。ArkTS 壳改动仅为标准 `Color.Transparent` 属性赋值和注释，但仍需 hvigor 编译与真机出画确认；har 私有 `bgColor` 的缺省透明色只有字节码/上游样本旁证，也需真机验证。最终成功的是 JVM/Android 侧 Gradle 编译、单测和 debug APK 组装。

## 未修复 / 不在本轮范围

- `InlinePlayer`（详情页 16:9 内嵌位）仍为黑底占位；全屏视频 XComponent 与 Compose 小窗的坐标/裁剪对齐需要 CMP `insertInteropView` 等公开 interop API，当前 1.9.2-1.1.0 未暴露，未实现。
- 自定义请求头在本 OHOS SDK 版本无 `OH_AVMediaSource_*` native 入口，仍未支持。
- 未改 `OhosPlayerEngine`、`OhosVideoSurfaceBridge`、NAPI C++ 绑定逻辑、播放器 listener/play/pause/seek/release 契约，也未改 `compose.har`。
- 未合并 main、未关闭 PR、未触碰其他分支/PR。

## 本轮复验（2026-11-14，独立复核，无代码改动）

本轮重新执行 `/tmp/dengying-11/.chain/test-cmd`：`BUILD SUCCESSFUL in 20s`，`91 actionable tasks: 3 executed, 88 up-to-date`；日志中的 `e: ? KMP Dependencies Resolution Failure`（`commonMain` / `jvmSharedMain` 的 `jvm` 变体缺失告警）与第 4 项所述既有 CPF/OHOS 依赖矩阵限制一致，为非致命诊断，不影响退出码 0 与上述任务成功。复核 `git`：HEAD 与 `origin/feat/ohos-avplayer-engine` 同为 `bf64ec70a2e41fdf2d99ebff259b9a5e34b0d575`，`origin/main`（`1a8066ee63981afed1facc4254a3eb486c1d5641`）是 HEAD 祖先，无需再合并/rebase，无冲突；工作树仅有未跟踪 `.chain/`。

复核代码：Stack 顺序、全屏 `XComponent(SURFACE)`、`onLoad`/`onDestroy` 的 `setVideoSurfaceId`/`clearVideoSurfaceId`、NAPI `strtoull` 透传、`OhosVideoSurfaceBridge.updateSurface/clearSurface`、`OhosPlayerEngineHost.surfaceActive/Inactive/forceRelease`、`OhosPlayerEngine` 的 `STATE_PREPARED → onReady` 链路均未改动；`PlayerScreen.kt` 根 Box 是唯一全屏不透明 Compose 层，缓冲/错误/控制层只有居中提示或局部 GlassSurface，没有额外全屏铺黑。`compose.har` 的透明缺省色仍只有字节码/`Compose.d.ets`/上游样本旁证，`SURFACE` 打洞在 ArkUI 中的层序仍无法在本机确认。

通过公开 GitHub REST API（`curl https://api.github.com/repos/xiao-hans/DengYing/pulls/11`）读取了 PR #11 描述（标题 `feat(ohos): 鸿蒙原生 OH_AVPlayer 播放引擎（WIP，视频层遮挡待解决）`，`state=open`, `draft=true`, `base=main`, `head=feat/ohos-avplayer-engine`），其「视频层遮挡 BLOCKER」与本文根因/修法对应。本轮未使用 `gh`。

按本轮 harness 规则（`Do not push. Do not merge. Do not force-push.`）未执行 push；任务步骤 5 要求的推送已由上一轮 chain 完成，远端与本地 HEAD 一致，PR 仍保持 draft、未合并、未关闭。除本文件这一节及第 3 行措辞外，本轮未改动任何代码或文档。

## 补充修正（2026-11-14）：把「打洞」限定在鸿蒙平台

`bf64ec7` 的透明分支写在 `commonMain/PlayerScreen.kt`，Android/JVM 也会走到：Android 的 `VideoSurface` actual 在 `PlayerView.player != null` 时回调 `onBind(true)`，于是根 Box 在 `prepared && !buffering && !hasError` 时也变透明。而 `PlayerView` 用 `RESIZE_MODE_FIT`，视频之外有黑边；media3 的 `exo_shutter` 只铺满 `exo_content_frame`，黑边之外若根背景透明会露出 `Theme.DengYing` 的暖白 `windowBackground`（`#F4F4F1`），起播/缓冲/宽屏片源下会闪白。JVM/桌面侧没有下层视频，透明同样只会露出窗口底色。这是 `bf64ec7` 引入的非鸿蒙端回归，故本轮把让位条件按平台收窄，鸿蒙行为不变。

改动（均为 UI 层，未触碰播放器契约）：

- 新增 `DengYingOhos/ui/src/commonMain/kotlin/com/dengying/player/ui/platform/VideoSurfaceLayer.kt`：`expect val videoSurfaceIsBelowCompose: Boolean` 与纯函数 `shouldRevealVideoSurface(videoBelowCompose, surfaceBound, prepared, buffering, hasError)`，把「何时打洞」抽成可单测的逻辑。
- 新增 `DengYingOhos/ui/src/ohosMain/kotlin/com/dengying/player/ui/platform/VideoSurfaceLayer.ohos.kt`：`actual = true`（视频在 Compose 之下）。
- 新增 `DengYingOhos/ui/src/jvmSharedMain/kotlin/com/dengying/player/ui/platform/VideoSurfaceLayer.jvm.kt`：`actual = false`（Android `PlayerView` 与桌面都在 Compose 树内出图）。source-set 层级沿用工程既有的 `expect object PosterFetch` 模式：`androidMain`/`jvmMain → jvmSharedMain`，`ohosArm64Main`/`ohosX64Main → ohosMain`，四个 target 都有 actual。
- `DengYingOhos/ui/src/commonMain/kotlin/com/dengying/player/ui/pages/PlayerScreen.kt`：根 Box 背景改用 `shouldRevealVideoSurface(videoSurfaceIsBelowCompose, surfaceBound, s.prepared, s.buffering, s.errMsg.isNotEmpty())`；`VideoSurface(..., onBind = ...)` 回写 `surfaceBound` 的方式不变。
- `DengYingOhos/ui/src/ohosMain/kotlin/com/dengying/player/ui/platform/Platform.ohos.kt`：同步过时的打洞注释。
- 新增 `DengYingOhos/app/src/test/java/com/dengying/player/playback/VideoSurfaceRevealTest.kt`：4 个单测锁住「鸿蒙仅在 surface 已绑 + 已 prepared + 非缓冲 + 无错时透明」「缓冲/错误保持黑底」「Android/JVM 恒不透明」「Android 宿主 actual 为 false」。

## 本轮构建 / 测试（补充修正后）

- `sh .chain/test-cmd`：`BUILD SUCCESSFUL in 16s`，`91 actionable tasks: 3 executed, 1 from cache, 87 up-to-date`，退出码 0；产物含 `app/build/outputs/apk/debug/app-debug.apk`。测试 XML 合计 42 个、660 tests、0 failures、0 errors、0 skipped（比上文的 41/656 多出本轮新增的 4 个用例）。
- 为避免 UP-TO-DATE 掩盖增量，另强制 `sh gradlew :app:testDebugUnitTest --rerun-tasks --tests 'com.dengying.player.playback.VideoSurfaceRevealTest'`：`BUILD SUCCESSFUL in 38s`，`59 actionable tasks: 59 executed`（含 `:ui:compileDebugKotlinAndroid`、`:app:compileDebugUnitTestKotlin` 全量重编），`VideoSurfaceRevealTest` 4/4 通过、0 failures。`expect`/`actual` 在 jvmSharedMain 侧确实编译出 `VideoSurfaceLayerKt.class` 与 `VideoSurfaceLayer_jvmKt.class`。`git diff --check` 通过。
- 尝试 `:ui:compileOhosMainKotlinMetadata` 以验证 ohosMain 的 actual：任务在本机失败，报 `Unresolved reference 'ComposeArkUIViewController'`、`Unresolved reference 'initMainHandler'` 等，全部位于未改动的 `ComposeHost.ohos.kt`。用 `git stash` 在未修改的 HEAD 上以 `--offline --rerun-tasks` 复现完全相同的错误，确认是既有 CPF/OHOS 依赖矩阵与本机 `~/.konan/dependencies` 为空导致的，与本轮改动无关。故本轮仍未编译/链接 `:ui:compileKotlinOhosArm64`、`:ui:compileKotlinOhosX64`、HAP，也未做真机验证，**不能声称画面已经实际可见**；ARKTS/har 透明缺省色与 SURFACE 打洞层序仍需 hvigor + 真机确认。

## 仍未修复 / 本轮未做

- 上述 `InlinePlayer` 小窗对齐、自定义请求头、播放器协议与 `compose.har` 均未改动（同「未修复 / 不在本轮范围」）。
- 本轮 harness 规则禁止 push，故上述补充修正尚未推送；`origin/feat/ohos-avplayer-engine` 仍停在 `bf64ec70a2e41fdf2d99ebff259b9a5e34b0d575`（已含核心遮挡修复），本轮新提交在其之上。未合并 main、未关闭 PR、未触碰其他分支/PR。
