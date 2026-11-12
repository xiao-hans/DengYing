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
   `SetURLSource`/`Prepare`（头文件要求 Prepare 前设置）。
   - Prepare 之后再到达的新 surface（窗口重建、折叠屏切换）：不缓存等待，
     直接保留 url/headers/当前播放位置/起播意图重新 `setMedia`，新 surface 在
     Prepare 前绑好，画面不会黑到下一次换集。
   - 同一 native window 上「旧 player `ReleaseSync` 返回 → 新 player
     `SetVideoSurface`」用挂起式等待串行化（按 window 各一把 Mutex），主线程
     不被阻塞，不同 window 互不影响。
   - `ReleaseSync` 失败时保留回调锚点与 window 引用（重试一次），避免 native
     仍回调已释放的 `StableRef`。

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

#### 工具链调查（2026-11-13，只读，未改依赖）

检查 `ui-ohosArm64Main-1.9.2-1.1.0.klib` 与 `ui-arkui-ohosArm64`（klib 内
metadata 字符串），确认存在 interop 基础设施符号：

- `androidx.compose.ui.viewinterop.InteropViewHolder` / `TypedInteropViewHolder`
- `androidx.compose.ui.viewinterop.TrackInteropPlacementModifierNode`
- `InteropContainer.insertInteropView` / `removeInteropView`（成员名）
- `androidx.compose.ui.interop.arkc.ArkUINativeView` /
  `EmbeddedNativeViewRenderNode` / `C2ACApiDriver` / `ArkUIInteropContext`
- 渲染侧 `EmbeddedInteropComponent`、`nativeHidden`、`wrapContentComposable`

结论：这些是 **CMP 内部**的 Compose→ArkUI 组件（C2AC）与渲染节点，klib 中
没有找到应用代码可直接调用的公开 Composable（例如 `InteropView(...)`）；
缓存里也没有 sources jar 可核对可见性/签名。用反射式/未公开路径插入 interop
视图会依赖内部二进制布局，不具备可维护性。

#### 三条候选路径与结论

1. **Compose 播放区域打洞（per-region 透明）**：需要在 CMP 的整块 EGL 输出面
   上按矩形输出 alpha。`Color.Transparent` 只影响 Compose 内部绘制，不能擦掉
   父 `Box` 的黑色，更不能让整块不透明的 Compose surface 局部透明。klib 中
   未发现公开的 per-region transparency/punch 开关。**本轮不可行。**
2. **公开 interop 视图把 native surface 放进 `VideoSurface` 的坐标/裁剪**：
   正是 CMP 内部 `insertInteropView` + `InteropContainer` 做的事，但 1.9.2-1.1.0
   未暴露公开 API。**卡在 API 缺失，不能在应用层干净实现。**
3. **XComponent 移到 Compose 之上并按矩形裁剪**：ArkUI 层序可做（给
   XComponent 设 position/width/height + clip），但视频矩形会盖住 Compose 在
   该矩形内绘制的播放控件（控制条/手势层），必须把控件改成 ArkUI 覆盖层，
   属于壳层大改；且本机无法用 hvigor 构建/验证 ArkTS 壳。**本轮不能落地。**
   若后续采用，需要：`VideoSurface` 在 Compose 里 `onGloballyPositioned` 上报
   窗口坐标 → 新增 NAPI → ArkTS 调整 XComponent 几何与 clip，并处理
   px/vp 换算与页面切换。

在解决前，播放页与内嵌播放位均视为「只有声音、无画面」，BLOCKER 保留。

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
