package com.dengying.player.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.dengying.player.player.PlaybackProbe
import com.dengying.player.player.PlayerEngine
import com.dengying.player.ui.player.IosPlayerEngineHost
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerLayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

/**
 * iOS 播放画面承载（KMP P5-6 落真身）。
 *
 * ## 做法
 *
 * 用 CMP 的 `UIKitView` 把一个 [UIView] 塞进 Compose 布局，再把
 * [AVPlayerLayer] 挂到这个 UIView 的 `layer` 上。画面尺寸跟随 UIView 的 bounds，
 * 由 `AVPlayerLayer.videoGravity = resizeAspect`（引擎侧已设）保证等比缩放不裁切。
 *
 * 为什么不用 `ComposeUIViewController` 里直接加图层：`:ui` 拿不到宿主
 * `UIViewController`，`UIKitView` 是 CMP 提供的、唯一稳定的「Compose 里插原生视图」入口。
 *
 * ## 契约照守（commonMain Kdoc 五条）
 *
 * 1. **引擎变化必须整体重建视图** —— 用 `key(engine)` 包住整个 [UIKitView]。
 *    Android 侧的黑屏根因就是「工厂先于引擎」导致旧视图留着旧 Surface；这里
 *    重建而不是改绑，同一理由同一处理。
 * 2. 不接管触摸：`UIKitView` 沿用默认（不吞事件）。
 * 3. 不画缓冲指示：缓冲态由调用方浮层表达，本文件不碰。
 * 4. FIT 缩放：`videoGravity = resizeAspect`。
 * 5. `viewHandle()` 转型失败按 `null` 处理，**永不抛异常**。
 *
 * ## 生命周期
 *
 * `DisposableEffect` 在离开组合时通知 host `surfaceInactive()`；host 在计数归零时
 * 只暂停、不释放（与 Android `PlaybackEngineHolder` 同语义）。摘层前先确认这一层
 * 仍挂在**本视图**上：单例引擎的 [AVPlayerLayer] 会被新 surface 取走，旧 surface
 * 的 `onDispose` 若无条件 `removeFromSuperlayer()`，会把已经挂到新视图上的层摘掉，
 * 表现为「有声音、画面全黑」。
 *
 * 尺寸不能只在 `factory` 里设一次：`UIKitView` 的 view bounds 可能到布局后才非零。
 * 这里用 `onSizeChanged` 触发一次重组，并在 `update` 里重新同步 `layer.frame`；
 * 重组时 `view.bounds` 已是最终值。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoSurface(
    engine: PlayerEngine?,
    modifier: Modifier,
    zoomMode: VideoZoomMode,
    onBind: (bound: Boolean) -> Unit,
) {
    // 引擎变化 ⇒ 整块重建（契约第 1 条）
    key(engine) {
        val layer = remember(engine) { engine?.viewHandle() as? AVPlayerLayer }
        // UIKitView 的 factory/update 拿到的 view bounds 可能还是 0×0；布局完成后
        // onSizeChanged 写这个 state，触发重组，update 再把 layer.frame 同步到最终 bounds。
        val surfaceSize = remember(engine) { mutableStateOf(IntSize.Zero) }
        val sizeTick = surfaceSize.value
        // 只记引用，不做 state：factory 里写 state 会踩「组合期写入」的坑。
        val hostView = remember(engine) { arrayOfNulls<UIView>(1) }

        if (layer == null) {
            // 没有可挂的层：保持黑底，且**不虚报** onBind(true)，
            // 否则排障时「绑上了但没有画面」与「压根没绑」无法区分。
            BlackCanvas(modifier)
            return@key
        }

        UIKitView(
            modifier = modifier.onSizeChanged { surfaceSize.value = it },
            factory = {
                val view =
                    UIView().apply {
                        backgroundColor = UIColor.blackColor
                        autoresizingMask =
                            UIViewAutoresizingFlexibleWidth or
                            UIViewAutoresizingFlexibleHeight
                    }
                hostView[0] = view
                // 隐式动画必须在改动之前关；原实现 begin/commit 放在赋值之后，等于没关。
                CATransaction.begin()
                CATransaction.setValue(true, kCATransactionDisableActions)
                view.layer.addSublayer(layer)
                layer.videoGravity =
                    when (zoomMode) {
                        VideoZoomMode.FIT -> AVLayerVideoGravityResizeAspect
                        VideoZoomMode.COVER -> AVLayerVideoGravityResizeAspectFill
                        VideoZoomMode.FILL -> AVLayerVideoGravityResize
                    }
                layer.frame = view.bounds
                CATransaction.commit()
                PlaybackProbe.note(
                    "ios-surface-factory",
                    "view=${view.bounds.dims()} tick=${sizeTick.width}x${sizeTick.height}",
                )
                view
            },
            update = { view ->
                hostView[0] = view
                CATransaction.begin()
                CATransaction.setValue(true, kCATransactionDisableActions)
                layer.frame = view.bounds
                CATransaction.commit()
                PlaybackProbe.note(
                    "ios-surface-update",
                    "view=${view.bounds.dims()} tick=${sizeTick.width}x${sizeTick.height}",
                )
            },
        )

        DisposableEffect(layer) {
            IosPlayerEngineHost.surfaceActive()
            onBind(true)
            onDispose {
                // 不在这里 release 引擎：host 计数归零时只暂停（契约）
                IosPlayerEngineHost.surfaceInactive()
                onBind(false)
                // 单例 layer 可能已被新 surface 取走；只在它还挂在本视图上时才摘。
                // 无条件 removeFromSuperlayer() 会把新视图上的 layer 一起摘掉。
                val stillHere = layer.superlayer === hostView[0]?.layer
                PlaybackProbe.note("ios-surface-detach", "stillHere=$stillHere")
                if (stillHere) layer.removeFromSuperlayer()
            }
        }
    }
}

/** 只写尺寸，不写完整地址；用于黑屏排障。 */
@OptIn(ExperimentalForeignApi::class)
private fun CValue<platform.CoreGraphics.CGRect>.dims(): String = useContents { "${size.width.toInt()}x${size.height.toInt()}" }

/** 无引擎时的黑底占满，与桌面 actual 同形 */
@Composable
private fun BlackCanvas(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black))
}
