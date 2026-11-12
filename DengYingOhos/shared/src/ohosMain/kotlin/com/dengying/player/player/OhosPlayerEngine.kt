@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.dengying.player.player

import com.dengying.player.platform.Log
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.toKString
import cnames.structs.OH_AVFormat
import cnames.structs.OH_AVPlayer
import platform.AVCodecKit.Core.OH_AVFormat_GetIntValue
import platform.AVCodecKit.Core.OH_AVFormat_GetLongValue
import platform.MediaKit.AVPlayer.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 鸿蒙播放引擎 —— 直接用 MediaKit 的 native AVPlayer（libavplayer.so）。
 *
 * ## 为什么直接用 native AVPlayer，而不是 ArkTS 的 @ohos.multimedia.media
 *
 * CPF 发行版已随 ohos target 提供 platform klib
 * `org.jetbrains.kotlin.native.platform.AVPlayer`（package
 * `platform.MediaKit.AVPlayer`，manifest 自带 linkerOpts=-lavplayer），
 * 底层就是系统 `libavplayer.so`。命令与回调因此都留在 Kotlin 进程内：
 * 不需要在 ArkTS 再写一份播放器、不需要轮询命令槽、也不会多一条
 * Kotlin→C++→NAPI→ArkTS 的异步转发链。
 *
 * 注意：**不要自建 cinterop**。CPF 的 klib 布局会让 cinterop 任务报
 * "Could not find org.jetbrains.kotlin.native.platform.ohos"（实测），
 * 直接用上面的 platform klib 即可。
 *
 * ## 已实现 / 未实现（第一版，WIP）
 *
 * - 已实现：setMedia（URL）/prepare/play/pause/seekTo/setSpeed/release，
 *   状态→onReady/onPlaying/onPaused/onCompleted/onBuffering/onError，
 *   时长与视频尺寸。
 * - **未实现：自定义请求头**。本 SDK 版本的 avplayer.h 没有
 *   SetMediaSourceHeader 之类的入口（只有 SetURLSource/SetFDSource），
 *   headersJson 目前仅记录，不生效；需要请求头的源会失败。
 * - **未实现：画面绑定**。需要 ArkUI XComponent 的 surfaceId +
 *   OH_NativeWindow_CreateNativeWindowFromSurfaceId；`VideoSurface.ohos.kt`
 *   仍是黑底 Box。当前只能出声不出画。
 * - 回调在 AVPlayer 的工作线程触发（不是主线程）。PlayerEngine 契约要求
 *   主线程回调，接线 Compose 状态前需要切主线程；WIP 阶段先同步透传。
 */
class OhosPlayerEngine : PlayerEngine {

    private companion object {
        const val TAG = "DY/OhosPlayer"

        // AVPlayerState（avplayer_base.h）。CPF 的 platform klib 把 C enum 暴露为
        // 无 entries 的整型（AVPlayerState.entries 编不过），故按 C 枚举值使用。
        const val STATE_IDLE = 0
        const val STATE_INITIALIZED = 1
        const val STATE_PREPARED = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_STOPPED = 5
        const val STATE_COMPLETED = 6
        const val STATE_RELEASED = 7
        const val STATE_ERROR = 8

        // AVPlayerOnInfoType
        const val INFO_EOS = 3
        const val INFO_STATE_CHANGE = 4
        const val INFO_POSITION_UPDATE = 5
        const val INFO_RESOLUTION_CHANGE = 8
        const val INFO_BUFFERING_UPDATE = 9
        const val INFO_DURATION_UPDATE = 12

        // AVPlayerSeekMode.AV_SEEK_CLOSEST（since 12）
        val SEEK_CLOSEST: AVPlayerSeekMode = 2u
    }

    /** 监听器快照，回调线程与主线程并发读写；K/N 新内存模型下 @Volatile 引用即可。 */
    @Volatile
    private var listeners: List<PlayerListener> = emptyList()

    private var player: CPointer<OH_AVPlayer>? = null
    private var callbacksAnchor: StableRef<OhosPlayerEngine>? = null

    private var state: Int = STATE_IDLE
    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var playing = false
    private var completedNotified = false

    private var loadedUrl: String? = null
    private var headersJson: String = ""
    private var pendingSeekMs: Long = 0L
    private var wantPlay = false
    private var speed: Float = 1f

    override fun setMedia(url: String, headersJson: String, startPositionMs: Long) {
        releasePlayer()
        loadedUrl = url
        this.headersJson = headersJson
        pendingSeekMs = startPositionMs
        wantPlay = false
        completedNotified = false
        durationMs = 0L
        positionMs = startPositionMs
        playing = false
        state = STATE_IDLE

        if (headersJson.isNotEmpty()) {
            // 本版本 native API 无请求头入口，明确留痕，避免误判成「源坏了」。
            Log.w(TAG, "headers ignored: native OH_AVPlayer has no header API")
        }

        val p = OH_AVPlayer_Create()
        if (p == null) {
            notifyError(PlaybackErrorKind.Unknown, -1, "OH_AVPlayer_Create returned null")
            return
        }
        player = p

        // userData 里放引擎引用；StableRef 必须保活到 Release 之后。
        val anchor = StableRef.create(this)
        callbacksAnchor = anchor
        OH_AVPlayer_SetOnInfoCallback(
            p,
            staticCFunction { _: CPointer<OH_AVPlayer>?, type: AVPlayerOnInfoType,
                body: CPointer<OH_AVFormat>?, userData: COpaquePointer? ->
                try {
                    val engine = userData?.asStableRef<OhosPlayerEngine>()?.get() ?: return@staticCFunction
                    engine.handleInfo(type, body)
                } catch (t: Throwable) {
                    Log.w(TAG, "onInfo failed: ${t.message}")
                }
            },
            anchor.asCPointer(),
        )
        OH_AVPlayer_SetOnErrorCallback(
            p,
            staticCFunction { _: CPointer<OH_AVPlayer>?, code: Int, msg: CPointer<ByteVar>?,
                userData: COpaquePointer? ->
                try {
                    val engine = userData?.asStableRef<OhosPlayerEngine>()?.get() ?: return@staticCFunction
                    engine.onNativeError(code, msg?.toKString() ?: "")
                } catch (t: Throwable) {
                    Log.w(TAG, "onError failed: ${t.message}")
                }
            },
            anchor.asCPointer(),
        )

        val ret = OH_AVPlayer_SetURLSource(p, url)
        if (ret != 0u) {
            notifyError(PlaybackErrorKind.Unknown, ret.toInt(), "SetURLSource failed: $ret")
            return
        }
        OH_AVPlayer_Prepare(p)
    }

    override fun play() {
        wantPlay = true
        val p = player ?: return
        if (state == STATE_PREPARED ||
            state == STATE_PAUSED ||
            state == STATE_COMPLETED ||
            state == STATE_STOPPED
        ) {
            applyPendingSeek(p)
            OH_AVPlayer_Play(p)
        }
        // INITIALIZED 状态等 PREPARED 回调再起播（见 handleInfo）。
    }

    override fun pause() {
        wantPlay = false
        val p = player ?: return
        OH_AVPlayer_Pause(p)
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        val p = player ?: return
        if (state == STATE_PREPARED || state == STATE_PLAYING ||
            state == STATE_PAUSED || state == STATE_COMPLETED
        ) {
            val seconds = (positionMs / 1000L).toInt()
            OH_AVPlayer_Seek(p, seconds, SEEK_CLOSEST)
        } else {
            pendingSeekMs = positionMs
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        val p = player ?: return
        if (state == STATE_PREPARED || state == STATE_PLAYING ||
            state == STATE_PAUSED
        ) {
            OH_AVPlayer_SetPlaybackRate(p, speed)
        }
    }

    override fun release() {
        wantPlay = false
        loadedUrl = null
        releasePlayer()
        listeners = emptyList()
    }

    override val currentPosition: Long
        get() {
            val p = player ?: return positionMs
            return memScoped {
                val t = alloc<IntVar>()
                if (OH_AVPlayer_GetCurrentTime(p, t.ptr) == 0u) t.value.toLong() else positionMs
            }
        }

    override val duration: Long
        get() {
            val p = player ?: return durationMs
            return memScoped {
                val d = alloc<IntVar>()
                if (OH_AVPlayer_GetDuration(p, d.ptr) == 0u) d.value.toLong() else durationMs
            }
        }

    override val isPlaying: Boolean get() = playing

    override val currentUrl: String? get() = loadedUrl

    override fun addListener(listener: PlayerListener) {
        val current = listeners
        if (current.any { it === listener }) return
        listeners = current + listener
    }

    override fun removeListener(listener: PlayerListener) {
        listeners = listeners.filterNot { it === listener }
    }

    /**
     * 视图绑定口子。native player 本身不能直接给 Compose 用；画面要等
     * surfaceId 通道接上后返回一个包装对象。当前恒 null（黑底占位，合法契约）。
     */
    override fun viewHandle(): Any? = null

    // ---------------- 内部 ----------------

    private fun releasePlayer() {
        val p = player
        player = null
        playing = false
        state = STATE_RELEASED
        if (p != null) {
            OH_AVPlayer_Release(p)
        }
        callbacksAnchor?.dispose()
        callbacksAnchor = null
    }

    private fun applyPendingSeek(p: CPointer<OH_AVPlayer>) {
        if (pendingSeekMs > 0L) {
            OH_AVPlayer_Seek(p, (pendingSeekMs / 1000L).toInt(),
                SEEK_CLOSEST)
            pendingSeekMs = 0L
        }
        if (speed != 1f) {
            OH_AVPlayer_SetPlaybackRate(p, speed)
        }
    }

    private fun handleInfo(type: AVPlayerOnInfoType, body: CPointer<OH_AVFormat>?) {
        when (type.toInt()) {
            INFO_STATE_CHANGE -> onStateChanged(intOf(body, OH_PLAYER_STATE))
            INFO_DURATION_UPDATE -> {
                val d = longOf(body, OH_PLAYER_DURATION)
                if (d > 0L) durationMs = d
            }
            INFO_POSITION_UPDATE -> {
                val pos = longOf(body, OH_PLAYER_CURRENT_POSITION)
                if (pos >= 0L) positionMs = pos
            }
            INFO_RESOLUTION_CHANGE -> {
                notify {
                    it.onVideoSize(
                        intOf(body, OH_PLAYER_VIDEO_WIDTH),
                        intOf(body, OH_PLAYER_VIDEO_HEIGHT),
                    )
                }
            }
            INFO_BUFFERING_UPDATE -> {
                // AVPLAYER_BUFFERING_START = 1 / AVPLAYER_BUFFERING_END = 2。
                val bufferingType = intOf(body, OH_PLAYER_BUFFERING_TYPE)
                if (bufferingType == 1 || bufferingType == 2) {
                    notify { it.onBuffering(bufferingType == 1) }
                } else {
                    // PERCENT / CACHED_DURATION：按百分比兜底（1..99 = 缓冲中）。
                    val percent = intOf(body, OH_PLAYER_BUFFERING_VALUE)
                    if (percent in 1..99) notify { it.onBuffering(true) }
                }
            }
            // EOS 与 STATE_CHANGE(COMPLETED) 都可能到，交给 completedNotified 去重。
            INFO_EOS -> notifyCompleted()
            else -> Unit
        }
    }

    private fun intOf(body: CPointer<OH_AVFormat>?, key: CPointer<ByteVar>?): Int {
        if (body == null || key == null) return 0
        val name = key.toKString()
        return memScoped {
            val v = alloc<IntVar>()
            if (OH_AVFormat_GetIntValue(body, name, v.ptr)) v.value else 0
        }
    }

    private fun longOf(body: CPointer<OH_AVFormat>?, key: CPointer<ByteVar>?): Long {
        if (body == null || key == null) return 0L
        val name = key.toKString()
        return memScoped {
            val v = alloc<LongVar>()
            if (OH_AVFormat_GetLongValue(body, name, v.ptr)) v.value else 0L
        }
    }

    private fun onStateChanged(rawState: Int) {
        state = rawState
        when (rawState) {
            STATE_PREPARED -> {
                durationMs = duration
                notify { it.onReady(durationMs) }
                player?.let { applyPendingSeek(it) }
                if (wantPlay) player?.let { OH_AVPlayer_Play(it) }
            }
            STATE_PLAYING -> {
                playing = true
                notify { it.onPlaying() }
            }
            STATE_PAUSED, STATE_STOPPED -> {
                playing = false
                notify { it.onPaused() }
            }
            STATE_COMPLETED -> {
                playing = false
                notifyCompleted()
            }
            STATE_ERROR -> notifyError(PlaybackErrorKind.Unknown, -1, "player entered AV_ERROR")
            else -> Unit
        }
    }

    private fun onNativeError(code: Int, message: String) {
        playing = false
        notifyError(classify(code, message), code, message)
    }

    private fun classify(code: Int, message: String): PlaybackErrorKind {
        val text = message.lowercase()
        return when {
            "timeout" in text || "network" in text || "connect" in text -> PlaybackErrorKind.Network
            "404" in text || "403" in text || "not found" in text -> PlaybackErrorKind.SourceGone
            "codec" in text || "decode" in text || "demux" in text -> PlaybackErrorKind.Decode
            else -> PlaybackErrorKind.Unknown
        }
    }

    private fun notifyCompleted() {
        if (completedNotified) return
        completedNotified = true
        playing = false
        notify { it.onCompleted() }
    }

    private fun notifyError(kind: PlaybackErrorKind, code: Int, message: String) {
        Log.w(TAG, "error kind=$kind code=$code msg=$message")
        notify { it.onError(kind, code, message) }
    }

    private inline fun notify(block: (PlayerListener) -> Unit) {
        for (l in listeners) {
            try {
                block(l)
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed: ${t.message}")
            }
        }
    }
}
