# 鸿蒙播放引擎接线说明（native OH_AVPlayer）

状态：2026-11-13，分支 `feat/ohos-avplayer-engine`。未真机验证。

## 画面（XComponent surfaceId）

1. `harmony/entry/src/main/ets/pages/ComposePage.ets` 在 Compose 下方铺一个全屏
   `XComponent(type: SURFACE)`，`onLoad` 里取 `getXComponentSurfaceId()`。
2. `setVideoSurfaceId(id)` → `napi_init.cpp` → `dlsym("dengy_set_video_surface")`
   → `OhosVideoSurfaceBridge.updateSurface()`（shared/ohosMain）。
3. bridge 调 `OH_NativeWindow_CreateNativeWindowFromSurfaceId`（API 12）生成
   `OHNativeWindow`，通知活动引擎。
4. `OhosPlayerEngine.setMedia` 在 `OH_AVPlayer_SetVideoSurface` 之后才
   `SetURLSource`/`Prepare`（头文件要求 Prepare 前设置）。surface 晚于 Prepare
   到达时只缓存，下一次 `setMedia` 生效。

限制：XComponent 是全屏的，视频铺满窗口；内嵌小窗的位置对齐需要
Compose ↔ ArkUI interop（CMP `insertInteropView`），尚未实现。
`VideoSurface` actual 在 surface 就绪时透明、未就绪时黑底。

### BLOCKER：视频层被 Compose 画布遮挡（2026-11-13，未修）

现状：`ComposePage.ets` 的 Stack 顺序是「XComponent 在下、Compose 在上」，
音频正常但**画面始终不可见**（全黑）。

原因：

- CMP 的 `Compose` 组件自身用 XComponent/EGL 出图，整块 Compose 内容都画在
  视频 surface 之上；ArkUI 的层序决定了后绘制的 Compose 覆盖先绘制的视频层。
- 播放页根 `Box` 是 `fillMaxSize().background(Color.Black)`
  （`PlayerScreen.kt`），`InlinePlayer` 的 16:9 区域同样铺黑底。
- `VideoSurface` 里 `Color.Transparent` 只是不画自己的 Box，擦不掉父 Box 已画
  的黑色，也无法穿透 Compose 自己的渲染面。

为什么不能简单对调层序：若把 SURFACE 盖到 ArkUI/Compose 之上，全屏视频会遮住
所有 UI，两种顺序都不成立。

需要的方案（择一，尚未实现）：

1. **Compose ↔ ArkUI interop**：把 native surface 的载体视图插入到 Compose
   布局内，按 `VideoSurface` 的坐标/裁剪摆放（CMP ui-ohos 的 interop 视图路径）。
2. **播放区域打洞**：Compose 侧对播放区域输出透明/挖孔，并调整 ArkUI 与
   Compose 的合成层序，使视频只在该矩形内透出。

在解决前，播放页与内嵌播放位均视为「只有声音、无画面」。

## 请求头（未支持）

结论：**当前 SDK 的 native OH_AVPlayer 没有请求头入口。**

- 系统头文件 `multimedia/player_framework/avplayer.h` 只有
  `OH_AVPlayer_SetURLSource` / `SetFDSource`，没有 header 参数。
- platform klib `org.jetbrains.kotlin.native.platform.AVPlayer` 里也没有
  `OH_AVMediaSource_*`（`strings *.knm` 实测无 `MediaSource`/`Header` 符号）。
- 因此 `setMedia(url, headersJson, ...)` 收到非空 headers 时只记 warning，
  **不生效**，代码里有 `TODO(请求头)` 标记。

可选方案（按代价从低到高）：

1. **运行时按设备 API 降级**：只有 API 20+ 才有 media source header API 时，
   在 17/18 设备上仍然不可用，不能作为通用解。
2. **ArkTS `media.createAVPlayer`**：其 `AVPlayer` 支持 `url` + `header`
   （`AVFileDescriptor`/`AVDataSrcDescriptor` 另说）。代价是播放器回到 ArkTS，
   需要重建一条 Kotlin ↔ ArkTS 的命令/回调通道。
3. **本地 HTTP 代理**：在 App 内起 loopback HTTP 服务，Kotlin 侧请求转发时
   注入 header，native player 只播 `http://127.0.0.1:<port>/...`。保持 native
   播放器不变，代价是多一个本地服务与端口生命周期管理。

在选定方案前，需要鉴权头的源请视为「暂不支持」。
