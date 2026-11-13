package com.dengying.player.playback.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Google Cast 框架的 OptionsProvider —— Manifest 里
 * `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` 指向的真实类。
 *
 * ## 为什么用默认接收端应用 ID
 *
 * `CastMediaOptions.Builder(context)` 不设自定义 receiverApplicationId 时，
 * 框架使用默认媒体接收端（`CC1AD845`，Google 的 Default Media Receiver）。
 * 灯影没有自建云端服务、没有 Cast 开发者账号，也不发布自定义 receiver，
 * 因此这里**只做设备发现 + 媒体下发**，不接任何自建云端。
 *
 * ## 与旧口径的差别
 *
 * 旧文档写「不接入 Google Cast 云端服务」，那是为了解释「清单里那条 meta-data
 * 不需要后台」；但当时类不存在，meta-data 是悬空声明，投屏入口也没有接线。
 * 现在改为：接入 GMS Cast 框架的**本地媒体下发通道**（依赖
 * `play-services-cast-framework`），仍不接入自建 Cast 云端服务、不注册开发者
 * 账号、不上报任何数据（SEC-001）。真机设备发现与下发成功与否尚未验证。
 *
 * ## 为什么 getAdditionalSessionProviders 返回 emptyList()
 *
 * 只用框架自带的 CastSession，不提供自定义 SessionProvider；返回 null 也可以，
 * 但空列表更明确，且不依赖框架对 null 的容错行为。
 */
class DengYingCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        // NotificationOptions 只决定系统媒体通知里出现哪些按钮；不设自定义
        // targetActivity，避免引入额外的 Activity 声明。这里保持框架默认行为，
        // 仅显式构造 CastMediaOptions 让「媒体会话」这条链路是明确接线的。
        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(NotificationOptions.Builder().build())
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_APP_ID)
            .setCastMediaOptions(mediaOptions)
            .setResumeSavedSession(false)
            .setEnableReconnectionService(false)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()

    companion object {
        /** Google Default Media Receiver；无自建 receiver 时使用 */
        const val DEFAULT_MEDIA_RECEIVER_APP_ID: String = "CC1AD845"
    }
}
