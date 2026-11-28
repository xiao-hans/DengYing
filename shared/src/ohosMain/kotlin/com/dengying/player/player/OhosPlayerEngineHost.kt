package com.dengying.player.player

import com.dengying.player.platform.Log
import kotlin.concurrent.Volatile

/**
 * 鸿蒙侧全局播放引擎宿主（对齐 Android `PlaybackEngineHolder`）。
 *
 * ## 语义（逐条沿用 PlayerEngineHost 契约）
 *
 * - **单例**：全应用只有一个 [OhosPlayerEngine]；inline / 全屏共用，避免解码器抢占。
 * - `surfaceCount` 只表示「几个表面在显示」，与谁持有无关；归零时**只暂停不释放**。
 * - [peek] 不创建实例：详情页内嵌位据此决定挂载还是空态。
 * - [forceRelease] 用于进程级清理/排障。
 *
 * ## 预览引擎
 *
 * 鸿蒙只有一个 XComponent，且同一 window 不能同时有两个 AVPlayer consumer
 * （见 [OhosPlayerEngine.scheduleBind]）。因此这里不建第二台播放器：发现页
 * 预览就是这台主引擎，`previewEngineIfAny()` 在已装媒体时把它交回会话，
 * 交接判据落在 `PlaybackSession` 的 `currentUrl` 比较上。
 *
 * 线程：K/N 新内存模型下用 `@Volatile` 引用 + 简单计数。所有方法按契约由主线程调用。
 */
object OhosPlayerEngineHost : PlayerEngineHost {

    private const val TAG = "DY/OhosEngineHost"

    @Volatile
    private var instance: OhosPlayerEngine? = null

    private var surfaceRefs: Int = 0

    /**
     * 发现页 → 详情交接窗口，见 [PlayerEngineHost.isHandoffInProgress]。
     *
     * 交接时发现页表面先归零、详情页表面后挂上，这几帧不能暂停引擎，
     * 否则详情页接管到的是一条停住的流。
     */
    @Volatile
    private var handoffInProgress = false

    override fun engine(): PlayerEngine {
        instance?.let { return it }
        val created = OhosPlayerEngine()
        instance = created
        return created
    }

    override fun peek(): PlayerEngine? = instance

    /** 鸿蒙只有一台播放器，预览与正式播放共用。 */
    override fun previewEngine(): PlayerEngine = engine()

    /** 已经装过媒体的主引擎可以当作预览引擎交回会话；没装过就返回 null。 */
    override fun previewEngineIfAny(): PlayerEngine? =
        instance?.takeIf { it.currentUrl != null }

    override fun isHandoffInProgress(): Boolean = handoffInProgress

    override fun setHandoffInProgress(inProgress: Boolean) {
        // 详情页每次状态刷新都会调一次（每秒），只在真正变化时记日志
        if (handoffInProgress == inProgress) return
        handoffInProgress = inProgress
        Log.w(TAG, "handoff in progress=$inProgress")
    }

    override fun surfaceActive() {
        surfaceRefs += 1
    }

    override fun surfaceInactive() {
        if (surfaceRefs > 0) surfaceRefs -= 1
        if (surfaceRefs == 0) {
            if (handoffInProgress) {
                Log.w(TAG, "no surface left, handoff keeps engine alive")
            } else {
                instance?.pause()
            }
        }
    }

    override fun forceRelease() {
        surfaceRefs = 0
        instance?.release()
        instance = null
    }

    override fun surfaceCount(): Int = surfaceRefs
}
