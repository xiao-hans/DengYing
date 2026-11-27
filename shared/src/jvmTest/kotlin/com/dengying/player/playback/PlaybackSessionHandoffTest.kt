package com.dengying.player.playback

import com.dengying.player.data.source.SourceRepository
import com.dengying.player.player.PlaybackErrorKind
import com.dengying.player.player.PlayerEngine
import com.dengying.player.player.PlayerEngineHost
import com.dengying.player.player.PlayerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 发现页 → 详情 → 返回 → 再进 的会话交接回归。
 *
 * 覆盖的真机 bug：第二次进入详情时 `previewHolding` 因 `engine != null` 失效、
 * `engineHolding` 因 `stop()` 清了 `lastSetUrl` 失效，于是同步写一帧
 * Resolving + totalTime=0，随后又对同一条流 `setMedia` —— 用户看到"已滑进详情
 * 又加载一次"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionHandoffTest {

    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @AfterTest
    fun tearDown() {
        PlaybackSession.cancelProgressTickerForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun stopThenReenterKeepsPlayingWithoutReload() = runTest(main) {
        val engine = FakeEngine()
        PlaybackSession.init(FakeHost(engine))
        // 进度循环是 while(true) + delay(1s)，跑在虚拟时钟上会让 advanceUntilIdle 不返回。
        PlaybackSession.cancelProgressTickerForTest()

        val url = "https://cdn.example.com/ep1.m3u8"
        engine.url = url
        engine.position = 12_000L
        engine.total = 600_000L
        engine.playing = true

        // 发现页点进详情（预览引擎已在播这一条）
        playLocal(url, startAtMs = 12_000L)

        assertEquals(
            PlaybackSession.Status.Playing,
            PlaybackSession.state.value.status,
            "交接命中时不得先广播 Resolving",
        )
        assertEquals(0, engine.setMediaCount, "预览引擎正在播，不得 setMedia")
        assertEquals(600_000L, PlaybackSession.state.value.totalTime, "交接必须沿用引擎时长")

        // 让 play() 里的接管协程真正跑完：此后会话持有该引擎，才是"再进详情"的现场。
        // 不推进的话 engine 仍为 null，第二次 play 走的是"首次进入"分支，测不到 bug。
        advanceUntilIdle()
        assertEquals(0, engine.setMediaCount, "接管不得重建媒体")

        // 返回发现页：表面归零 → releaseInline → stop()
        PlaybackSession.stop()
        assertEquals(PlaybackSession.Status.Idle, PlaybackSession.state.value.status)

        // 再进详情：lastSetUrl 已被清空，但引擎里还是同一条流
        playLocal(url, startAtMs = 12_000L)

        assertEquals(
            PlaybackSession.Status.Playing,
            PlaybackSession.state.value.status,
            "再进详情不得回到 Resolving",
        )
        assertEquals(0, engine.setMediaCount, "同一条流不得重新 setMedia")
        assertEquals(600_000L, PlaybackSession.state.value.totalTime, "totalTime 不得被清 0")

        advanceUntilIdle()
        assertEquals(0, engine.setMediaCount, "接管协程跑完后也不得 setMedia")

        // 播到结尾后再点同一集：语义是重播，必须重建媒体，否则停在片尾
        engine.position = engine.total
        engine.playing = false
        playLocal(url, startAtMs = 0L)
        assertEquals(
            PlaybackSession.Status.Resolving,
            PlaybackSession.state.value.status,
            "播完再点同一集必须重新解析/装载",
        )
        advanceUntilIdle()
        assertEquals(1, engine.setMediaCount, "播完重播必须 setMedia 一次")

        // 播放期报错后再退出重进：不能把"错误地址"当成画面连续，否则黑屏且无法恢复
        engine.fail()
        assertEquals(PlaybackSession.Status.Error, PlaybackSession.state.value.status)
        PlaybackSession.stop()
        playLocal(url, startAtMs = 0L)
        assertEquals(
            PlaybackSession.Status.Resolving,
            PlaybackSession.state.value.status,
            "错误地址不得命中交接，必须重新解析/装载",
        )
        advanceUntilIdle()
        assertEquals(2, engine.setMediaCount, "错误后重进必须重试 setMedia")

        // 还没 READY 就退出（duration 未知、也不在播）：同样不算"握着"这条流
        engine.total = 0L
        engine.position = 0L
        engine.playing = false
        PlaybackSession.stop()
        playLocal(url, startAtMs = 0L)
        assertEquals(
            PlaybackSession.Status.Resolving,
            PlaybackSession.state.value.status,
            "未就绪的引擎不得命中交接",
        )
        advanceUntilIdle()
        assertEquals(3, engine.setMediaCount, "未就绪后重进必须 setMedia")
    }

    private fun playLocal(url: String, startAtMs: Long) {
        PlaybackSession.play(
            repo = repo,
            rawUrl = url,
            title = "测试",
            vodName = "测试",
            vodId = 1L,
            source = "test",
            vodUri = "test://1",
            episodeIndex = 0,
            episodeName = "第 1 集",
            startAtMs = startAtMs,
            localFile = true,
        )
    }

    private val repo by lazy { SourceRepository() }
}

private class FakeHost(private val only: FakeEngine) : PlayerEngineHost {
    override fun engine(): PlayerEngine = only
    override fun previewEngineIfAny(): PlayerEngine = only
    override fun peek(): PlayerEngine = only
    override fun surfaceActive() = Unit
    override fun surfaceInactive() = Unit
    override fun forceRelease() = Unit
    override fun surfaceCount(): Int = 1
}

private class FakeEngine : PlayerEngine {
    var url: String? = null
    var position: Long = 0L
    var total: Long = 0L
    var playing: Boolean = false
    var setMediaCount: Int = 0

    private val listeners = mutableListOf<PlayerListener>()

    override fun setMedia(url: String, headersJson: String, startPositionMs: Long) {
        setMediaCount++
        this.url = url
        position = startPositionMs
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        position = positionMs
    }

    override fun setSpeed(speed: Float) = Unit

    override fun release() {
        url = null
    }

    override val currentPosition: Long get() = position
    override val duration: Long get() = total
    override val isPlaying: Boolean get() = playing
    override val currentUrl: String? get() = url

    override fun addListener(listener: PlayerListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlayerListener) {
        listeners -= listener
    }

    /** 模拟 Media3 的播放失败：currentUrl 保留、状态报错。 */
    fun fail() {
        playing = false
        listeners.toList().forEach { it.onError(PlaybackErrorKind.Unknown, -1, "boom") }
    }

    override fun viewHandle(): Any? = null
}

