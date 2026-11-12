@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.dengying.player.player

import platform.ArkGraphics2D.NativeWindow.OHNativeWindow
import com.dengying.player.platform.Log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import platform.ArkGraphics2D.NativeWindow.OH_NativeWindow_CreateNativeWindowFromSurfaceId
import platform.ArkGraphics2D.NativeWindow.OH_NativeWindow_DestroyNativeWindow

/**
 * ArkUI XComponent surfaceId → native `OHNativeWindow` 的全局通道。
 *
 * ## 数据流
 *
 * ```
 * ComposePage.ets  XComponent.onLoad
 *   → getXComponentSurfaceId()            (ArkTS，string)
 *   → dengying.setVideoSurfaceId(id)      (NAPI，napi_init.cpp)
 *   → dlsym "dengy_set_video_surface"     (libdengyui.so 的 @CName)
 *   → OhosVideoSurfaceBridge.updateSurface(surfaceId.toULong())
 *   → OH_NativeWindow_CreateNativeWindowFromSurfaceId (API 12)
 *   → OhosPlayerEngine.onVideoSurfaceChanged(window)
 *   → OH_AVPlayer_SetVideoSurface(player, window)（Prepare 之前）
 * ```
 *
 * ## 时序约束
 *
 * - `OH_AVPlayer_SetVideoSurface` 必须在 `Prepare` 之前设置（avplayer.h 明确）。
 *   引擎在 `setMedia` 里创建 player 后、`SetURLSource`/`Prepare` 前取当前 window。
 * - surface 晚于 player 到达时：这里缓存 window 并通知活动引擎；引擎只在
 *   IDLE / INITIALIZED 态立即补设（仍是 Prepare 之前），其余状态等下一次
 *   `setMedia`（换集/换源都会重新 Prepare）。
 * - surface 销毁：先通知引擎置空，再 DestroyNativeWindow。AVPlayer 的 release
 *   已在后台执行，此处不再等待。
 *
 * ## 为什么不在 UI 层做
 *
 * 这段状态是「进程级唯一显示面」的，和 Compose 组合树无关：页面重建、
 * 引擎单例复用都不应丢 surface。放在 shared 里也让 ArkTS 只需一条 NAPI。
 */
object OhosVideoSurfaceBridge {

    private const val TAG = "DY/OhosVideoSurface"
    private const val GSERROR_OK = 0

    /** 当前 XComponent surfaceId（ArkTS 侧字符串解析成的 ULong）。 */
    @Volatile
    private var surfaceId: ULong? = null

    /** surface 对应的 native window；null = 尚未就绪。 */
    @Volatile
    private var window: CPointer<OHNativeWindow>? = null

    // K/N（非 JVM）没有 kotlin.synchronized。这里的所有调用都来自 UI 线程
    // （ArkTS onLoad/onDestroy 与 Compose 主线程），用不可变快照 + @Volatile
    // 发布即可，避免引入平台锁依赖。
    @Volatile
    private var engines: List<OhosPlayerEngine> = emptyList()

    @Volatile
    private var readyListeners: List<(Boolean) -> Unit> = emptyList()

    fun currentWindow(): CPointer<OHNativeWindow>? = window

    fun isReady(): Boolean = window != null && surfaceId != null

    fun currentSurfaceId(): ULong? = surfaceId

    /** 引擎注册后立即同步一次当前 window（surface 先到、引擎后建的场景）。 */
    fun register(engine: OhosPlayerEngine) {
        if (engines.none { it === engine }) engines = engines + engine
        engine.onVideoSurfaceChanged(window)
    }

    fun unregister(engine: OhosPlayerEngine) {
        engines = engines.filterNot { it === engine }
    }

    fun addReadyListener(listener: (Boolean) -> Unit) {
        if (readyListeners.none { it === listener }) readyListeners = readyListeners + listener
    }

    fun removeReadyListener(listener: (Boolean) -> Unit) {
        readyListeners = readyListeners.filterNot { it === listener }
    }

    /**
     * ArkTS 侧 surface 创建/销毁的唯一入口。
     *
     * @param id null 表示销毁；相同 id 重复上报直接忽略（onLoad 可能重入）。
     */
    fun updateSurface(id: ULong?) {
        val old = surfaceId
        if (id == old) return

        surfaceId = id
        val oldWindow = window
        window = null

        // 先让引擎断掉旧 window（AVPlayer 侧不提供 clear surface，置空引用即可）。
        engines.forEach { it.onVideoSurfaceChanged(null) }
        if (oldWindow != null) {
            OH_NativeWindow_DestroyNativeWindow(oldWindow)
        }

        if (id == null) {
            Log.w(TAG, "surface destroyed")
            notifyReady(false)
            return
        }

        val created = memScoped {
            val out = alloc<CPointerVar<OHNativeWindow>>()
            val ret = OH_NativeWindow_CreateNativeWindowFromSurfaceId(id, out.ptr)
            if (ret != GSERROR_OK) {
                Log.w(TAG, "CreateNativeWindowFromSurfaceId($id) failed: $ret")
                null
            } else {
                out.value
            }
        }
        if (created == null) {
            notifyReady(false)
            return
        }

        window = created
        Log.w(TAG, "surface ready id=$id")
        engines.forEach { it.onVideoSurfaceChanged(created) }
        notifyReady(true)
    }

    /** 只清理与 [id] 相同的 surface，避免旧页面的 onDestroy 拆掉新页面的显示面。 */
    fun clearSurface(id: ULong) {
        if (surfaceId == id) updateSurface(null)
    }

    private fun notifyReady(ready: Boolean) {
        for (l in readyListeners) {
            try {
                l(ready)
            } catch (t: Throwable) {
                Log.w(TAG, "ready listener failed: ${t.message}")
            }
        }
    }
}

// @CName 必须是顶层函数（不能标在 object 成员上，否则会多一个 receiver 参数）。
// ArkTS 侧 surfaceId 是十进制字符串，napi_init.cpp 解析成 int64 后传入。

@CName("dengy_set_video_surface")
fun dengySetVideoSurface(surfaceId: Long) {
    if (surfaceId <= 0L) return
    OhosVideoSurfaceBridge.updateSurface(surfaceId.toULong())
}

@CName("dengy_clear_video_surface")
fun dengyClearVideoSurface(surfaceId: Long) {
    if (surfaceId <= 0L) return
    OhosVideoSurfaceBridge.clearSurface(surfaceId.toULong())
}
