package com.dengying.player.ui.viewmodel

import com.dengying.player.data.net.HlsUtil
import com.dengying.player.data.source.SourceRepository
import com.dengying.player.data.store.Store
import com.dengying.player.playback.PlaybackSession
import com.dengying.player.platform.KeyValueStore
import com.dengying.player.player.PlayerEngine
import com.dengying.player.player.PlayerEngineHost
import com.dengying.player.player.PlayerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `PlayerViewModel` 的引擎生命周期回归（审计 B-2 / B-6 / B-7）。
 *
 * 这里用记录调用的假 [PlayerEngine] 直接驱动 ViewModel：
 *  - B-6：重复 `onPlaying` 只能有一个进度 Job；
 *  - B-7：切换/解绑引擎必须注销监听器，解绑窗口内的回调不得再进入 VM；
 *  - B-2：换档成功后对新地址 `setMedia` 并从保留位置起播；探测失败必须回退
 *    原地址继续播，不能把 `engine` 留在 null（永久黑屏）。
 *
 * ViewModel 的 `init` 会走真实解析链（网络），故测试用 `startOnInit = false`
 * 构造，并注入 `probeReachability` 假结果；`PlaybackSession` 在 `@BeforeTest`
 * 用**独立调度器**提前初始化，避免它的常驻进度循环干扰 `runTest` 的虚拟时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelEngineTest {

    private companion object {
        const val OLD_URL = "http://old.example/master.m3u8"
        const val NEW_URL = "http://old.example/1080p.m3u8"
    }

    /**
     * `PlaybackSession` 是 object，首次访问会 `launch` 一个**永不结束**的进度循环。
     * 若它落在当前测试的调度器上，`runTest` 的虚拟时钟里就永远有挂起任务。
     * 因此先用一个不会被推进的独立调度器把它初始化掉。
     */
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(TestCoroutineScheduler()))
        PlaybackSession.init(object : PlayerEngineHost {
            override fun engine(): PlayerEngine = com.dengying.player.player.NullPlayerEngine
            override fun peek(): PlayerEngine? = null
            override fun surfaceActive() {}
            override fun surfaceInactive() {}
            override fun forceRelease() {}
            override fun surfaceCount(): Int = 0
        })
        // 会话级进度循环是 while(true)+delay，跑在测试调度器上会让虚拟时钟永不空闲。
        PlaybackSession.cancelProgressTickerForTest()
    }

    @AfterTest
    fun tearDown() {
        PlaybackSession.cancelProgressTickerForTest()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ B-6

    @Test
    fun `重复 onPlaying 只保留一个进度循环`() = runTest(timeout = 20.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = RecordingEngine().apply { position = 42_000L }
        val vm = newVm(engine) { okProbe() }
        vm.attachEngine()

        try {
            // ExoPlayer 的 onIsPlayingChanged(true) 会在暂停恢复、seek、缓冲恢复时反复触发
            engine.emitPlaying()
            engine.emitPlaying()
            engine.emitPlaying()

            advanceTimeBy(5_500)
            assertEquals(
                5,
                engine.positionReads,
                "三个 onPlaying 只能有一个每秒推进的循环（5.5s 应读 5 次，而非 15 次）",
            )

            engine.emitPaused()
            val readsAtPause = engine.positionReads
            advanceTimeBy(3_000)
            assertEquals(readsAtPause, engine.positionReads, "暂停后进度循环必须取消")
        } finally {
            // 常驻循环必须在本测试内停掉，否则 runTest 收尾会在虚拟时钟上永不空闲。
            vm.stopProgressLoopForTest()
        }
    }

    // ------------------------------------------------------------------ B-7

    @Test
    fun `切换引擎时注销监听器，解绑窗口内的回调不得进入 VM`() = runTest(timeout = 20.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = RecordingEngine()
        var listenersDuringProbe = -1
        var playingSeenDuringProbe = true
        var vmRef: PlayerViewModel? = null
        val probe: suspend (String) -> HlsUtil.ReachResult = {
            // 此刻 bindEngine(null) 已执行，探测尚未返回——正是"引擎解绑窗口"。
            listenersDuringProbe = engine.listeners.size
            engine.emitPlaying()
            playingSeenDuringProbe = vmRef?.state?.isPlaying ?: false
            failProbe()
        }
        val vm = newVm(engine, probe)
        vmRef = vm
        vm.attachEngine()

        try {
            assertEquals(1, engine.listeners.size, "挂载后 VM 注册了一个监听器")

            vm.applyQuality(NEW_URL)
            runCurrent()

            assertEquals(0, listenersDuringProbe, "换档解绑时必须 removeListener")
            assertFalse(playingSeenDuringProbe, "已注销的监听器不得再收到回调")
            assertEquals(1, engine.listeners.size, "回退起播应重新注册同一个监听器实例")
        } finally {
            vm.stopProgressLoopForTest()
        }
    }

    // ------------------------------------------------------------------ B-2

    @Test
    fun `换档成功后对新地址 setMedia 并从保留位置起播`() = runTest(timeout = 20.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = RecordingEngine().apply { position = 42_000L }
        val vm = newVm(engine) { okProbe() }
        vm.attachEngine()

        try {
            engine.emitPlaying()
            advanceTimeBy(1_100) // 让进度循环把 curTime 推到一个非零值
            assertEquals(42_000L, vm.state.curTime)

            vm.applyQuality(NEW_URL)
            runCurrent()

            val call = engine.setMediaCalls.last()
            assertEquals(NEW_URL, call.first, "换档后必须对新地址 setMedia")
            assertEquals(42_000L, call.third, "换档必须保留原进度")
            assertNotNull(vm.engine, "换档后引擎必须重新挂上，不能停在 null（黑屏）")
            assertEquals(NEW_URL, vm.state.selectedVariant)
        } finally {
            vm.stopProgressLoopForTest()
        }
    }

    @Test
    fun `换档探测失败回退原地址继续播放且不黑屏`() = runTest(timeout = 20.seconds) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val engine = RecordingEngine().apply { position = 25_000L }
        val vm = newVm(engine) { failProbe() }
        vm.attachEngine()

        try {
            engine.emitPlaying()
            advanceTimeBy(1_100)
            assertEquals(25_000L, vm.state.curTime)

            vm.applyQuality(NEW_URL)
            runCurrent()

            val call = engine.setMediaCalls.last()
            assertEquals(OLD_URL, call.first, "探测失败必须回退到原地址")
            assertEquals(25_000L, call.third, "回退后仍从保留位置继续")
            assertNotNull(vm.engine, "回退后引擎必须仍在，不能留下 engine=null 的黑屏")
            assertTrue(vm.state.errMsg.isNotEmpty(), "回退必须给出可见提示")
            assertNotEquals(NEW_URL, vm.state.selectedVariant, "档位应还原为原档")
        } finally {
            vm.stopProgressLoopForTest()
        }
    }

    // ------------------------------------------------------------------ 脚手架

    private fun newVm(
        engine: PlayerEngine,
        probe: suspend (String) -> HlsUtil.ReachResult,
    ): PlayerViewModel = PlayerViewModel(
        params = PlayerViewModel.PlayerParams(
            url = OLD_URL,
            title = "测试影片",
            vodId = 0L,
            source = "",
            vodName = "",
            vodPic = "",
            vodUri = "",
            lineFrom = "",
            episodeIndex = 0,
            episodeName = "",
            altUrls = emptyList(),
            epUrls = emptyList(),
            epNames = emptyList(),
        ),
        repo = SourceRepository(),
        store = Store(FakeKeyValueStore()),
        engineHost = FakeEngineHost(engine),
        isDebug = false,
        probeReachability = probe,
        startOnInit = false,
    )

    private fun okProbe() = HlsUtil.ReachResult(
        m3u8Ok = true,
        m3u8Status = 200,
        segOk = true,
        segStatus = 200,
        segHost = "cdn.example",
    )

    private fun failProbe() = HlsUtil.ReachResult(
        m3u8Ok = false,
        m3u8Status = 0,
        segOk = false,
        segStatus = 0,
        segHost = "",
    )

    /** 记录调用并按接口契约维护监听器列表的假引擎。 */
    private class RecordingEngine : PlayerEngine {

        val listeners = mutableListOf<PlayerListener>()
        val setMediaCalls = mutableListOf<Triple<String, String, Long>>()
        val seekCalls = mutableListOf<Long>()
        var positionReads = 0
        var playCalls = 0
        var position = 0L

        override var currentUrl: String? = null
            private set

        override fun setMedia(url: String, headersJson: String, startPositionMs: Long) {
            setMediaCalls += Triple(url, headersJson, startPositionMs)
            currentUrl = url
            if (startPositionMs > 0L) position = startPositionMs
        }

        override fun play() {
            playCalls++
        }

        override fun pause() {}

        override fun seekTo(positionMs: Long) {
            seekCalls += positionMs
            position = positionMs
        }

        override fun setSpeed(speed: Float) {}

        override fun release() {
            listeners.clear()
            currentUrl = null
        }

        override val currentPosition: Long
            get() {
                positionReads++
                return position
            }

        override val duration: Long get() = 0L

        override val isPlaying: Boolean get() = true

        override fun addListener(listener: PlayerListener) {
            listeners += listener
        }

        override fun removeListener(listener: PlayerListener) {
            listeners -= listener
        }

        override fun viewHandle(): Any? = null

        fun emitPlaying() {
            listeners.toList().forEach { it.onPlaying() }
        }

        fun emitPaused() {
            listeners.toList().forEach { it.onPaused() }
        }
    }

    private class FakeEngineHost(private val engine: PlayerEngine) : PlayerEngineHost {
        override fun engine(): PlayerEngine = engine
        override fun peek(): PlayerEngine = engine
        override fun surfaceActive() {}
        override fun surfaceInactive() {}
        override fun forceRelease() {}
        override fun surfaceCount(): Int = 0
    }

    private class FakeKeyValueStore : KeyValueStore {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, defValue: String): String = map[key] ?: defValue
        override fun allKeys(): Set<String> = map.keys.toSet()
        override fun putString(key: String, value: String, sync: Boolean): Boolean {
            map[key] = value
            return true
        }

        override fun remove(key: String, sync: Boolean): Boolean {
            map.remove(key)
            return true
        }
    }
}
