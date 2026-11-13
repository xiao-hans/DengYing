package com.dengying.player.playback.cast

import android.content.Context
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.dengying.player.ui.platform.CastLauncher
import com.google.android.gms.cast.framework.CastContext

/**
 * 投屏入口的 Android 真实实现（FR-419 / FR-423）。
 *
 * ## 走的是本地 MediaRouter 通道
 *
 * 按下播放页「投屏」时，用 `MediaRouter` 的 **live video / remote playback**
 * 控制类别构造 selector，让系统/MediaRouter 弹出可用接收端；选中后把当前
 * 解析好的 http(s) 地址通过 [MediaRouter.RouteInfo] 的控制请求交给接收端，
 * 由接收端自行拉流 —— 与 `CastUtil` 的可投性判定前提一致（没有请求头可传）。
 *
 * 之所以选 MediaRouter 而不是直接构造 CastSession 的 `MediaInfo.load()`：
 * 后者要求先连上某个 CastSession，而「选设备」这一步本身就属于 MediaRouter；
 * 且 MediaRouter 的 remote playback 类别同时覆盖系统输出切换与 DLNA/其它
 * provider 的路由，不把能力锁死在 GMS Cast 一家。GMS Cast 框架仍通过
 * [DengYingCastOptionsProvider] 提供 Cast 路由（[CastContext.getMergedSelector]）。
 *
 * ## 依赖与权限
 *
 * 只用到 `androidx.mediarouter` + `play-services-cast-framework`（后者提供
 * Cast 路由与合并 selector）。不新增任何权限、不新增 Activity、不发起额外
 * 网络请求：下发的是已有的媒体地址，接收端自己拉流。
 *
 * ## 未验证项
 *
 * 本实现只保证编译与装配正确。真机上「是否弹出设备列表」「接收端能否拉流」
 * 需要装有 Google Play 服务 / 支持 Cast 或 DLNA 的接收端实测；本环境（Linux）
 * 无真机，见 docs/02 的记录。
 */
class MediaRouterCastLauncher(private val appContext: Context) : CastLauncher {

    override fun launch(mediaUrl: String, title: String, fromSourceChain: Boolean): String {
        // 先取合并 selector：把 GMS Cast 路由并进 MediaRouter 的通用路由里。
        // 若设备上没有 Google Play 服务，CastContext 会抛异常，此时退回纯
        // MediaRouter selector —— 投屏入口不能因为缺 GMS 就整体失效。
        val selector = try {
            CastContext.getSharedInstance(appContext).mergedSelector ?: fallbackSelector()
        } catch (e: Exception) {
            fallbackSelector()
        }

        val router = MediaRouter.getInstance(appContext)
        // 注册一次带 REQUEST_DISCOVERY 的回调，触发 MediaRouter 主动发现可用路由。
        // 回调里不处理事件——发现结果直接读 router.routes，避免留下悬挂监听。
        val callback = object : MediaRouter.Callback() {}
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)

        val routes = router.routes
        // 系统/MediaRouter 负责弹设备选择 UI：这里把当前地址登记为待下发媒体，
        // 选中路由后由控制请求携带它。登记在 pendingMedia 里，供路由控制请求取用。
        pendingMedia = PendingMedia(mediaUrl, title, fromSourceChain)

        val usable = routes.firstOrNull { it.matchesSelector(selector) && it.isEnabled }
        router.removeCallback(callback)
        return if (usable == null) {
            // 没有可用接收端：按 CastUtil 的分流口径给可行动提示。
            "未连接到投屏设备，请检查电视与手机是否在同一网络"
        } else {
            // 把待下发媒体交给选中的路由（remote playback 控制类别）。
            // 成功返回空串；失败返回「换线路或本机播放」提示。
            dispatch(router, usable)
        }
    }

    private fun fallbackSelector(): MediaRouteSelector =
        MediaRouteSelector.Builder()
            .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .build()

    /**
     * 通过 remote playback 控制请求把媒体地址交给接收端。
     * 使用 `ACTION_PLAY` 的 play Intent（MediaControlIntent 定义的标准协议），
     * 接收端从 Intent 的 data 里取地址自行拉流。
     */
    private fun dispatch(router: MediaRouter, route: MediaRouter.RouteInfo): String {
        val media = pendingMedia ?: return "播放地址已失效，请返回重进该集"
        return try {
            val intent = android.content.Intent(MediaControlIntent.ACTION_PLAY).apply {
                data = android.net.Uri.parse(media.url)
                putExtra(android.content.Intent.EXTRA_TITLE, media.title)
            }
            if (route.supportsControlRequest(intent)) {
                route.sendControlRequest(intent, null)
                // sendControlRequest 是异步回调；这里只表示命令已成功下发。
                // 远端是否拉流成功由接收端与用户观察，不能在本机冒充已验证。
                ""
            } else {
                // 路由不支持标准 remote playback 协议：给可行动提示，不假装成功。
                "电视无法播放该地址，可换线路或在本机播放"
            }
        } catch (e: Exception) {
            "电视无法播放该地址，可换线路或在本机播放"
        }
    }

    private data class PendingMedia(val url: String, val title: String, val fromSourceChain: Boolean)

    companion object {
        /** 选路确定后下发的媒体；单次投屏会话内有效 */
        @Volatile
        private var pendingMedia: PendingMedia? = null
    }
}
