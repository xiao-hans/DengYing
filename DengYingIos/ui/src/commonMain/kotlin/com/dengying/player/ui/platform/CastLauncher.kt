package com.dengying.player.ui.platform

/**
 * 投屏入口的平台契约（FR-419 / FR-423）—— 由 `:app` 组合根注入，见 [com.dengying.player.ui.UiDeps]。
 *
 * ## 为什么是接口而不是 expect/actual
 *
 * 投屏需要 Activity 上下文才能弹设备选择框，而 `:ui/commonMain` 不能引用
 * `android.app.Activity`；但这属于「谁来装配」而不是「同一件事在各平台做法不同」，
 * 与 [com.dengying.player.ui.UiDeps] 同一理由：平台壳持有上下文，页面只声明需求。
 *
 * ## 契约
 *
 *  1. [launch] 只负责「打开接收端选择 / 把当前媒体地址交给接收端」，不负责判定。
 *     可投性判定由 `shared` 的 `CastUtil` 完成（纯函数，可单测），页面在
 *     `castEnabled` 为 false 时根本不调用本接口。
 *  2. 返回用户可读的失败提示；成功返回空串。页面把它写进 `castMsg` 浮层，
 *     不在这里直接弹 Toast —— 保持 UI 层对呈现方式的唯一所有权。
 *  3. 调用方传入的 [mediaUrl] 是解析后的最终地址；接收端自行拉流，
 *     故需要自定义请求头的源已在 `CastUtil` 里判为不可投，不会走到这里。
 */
interface CastLauncher {
    /**
     * @param mediaUrl 解析后的 http(s) 媒体地址
     * @param title 展示在接收端 / 系统媒体控件上的标题
     * @param fromSourceChain 地址是否来自源适配器换链（可试形态，失败提示需带「换线路」建议）
     */
    fun launch(mediaUrl: String, title: String, fromSourceChain: Boolean): String
}

/**
 * 无投屏能力时的缺省实现：不假装成功，明确告知用户下一步。
 *
 * 平台壳未注入 [CastLauncher] 时用这个兜底（例如 JVM 单测、桌面预览），
 * 避免「点了没反应」这种最差的失败形态。
 */
object UnsupportedCastLauncher : CastLauncher {
    override fun launch(mediaUrl: String, title: String, fromSourceChain: Boolean): String =
        "当前平台尚未接入投屏通道，请在本机播放"
}
