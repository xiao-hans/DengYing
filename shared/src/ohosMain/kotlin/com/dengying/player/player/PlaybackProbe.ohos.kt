package com.dengying.player.player

import com.dengying.player.platform.Log

/**
 * [PlaybackProbe] 的 OHOS 实现：直接写 HiLog。
 *
 * 【为什么不落文件】OHOS 应用沙箱内写文件需要额外的 `@ohos.file.fs` 权限与目录
 * 解析；HiLog 用 `hdc hilog` 就能抓，模拟器与真机同一条路径。Android 落文件
 * 是因为部分 ROM 会过滤本应用日志，这个前提在 OHOS 不成立。
 *
 * 【约束】所有 `note` 只带长度/状态，不带完整播放地址与请求头；`dumpSharePage`
 * 保持空实现，避免把分享页地址写进系统日志。
 */
actual object PlaybackProbe {

    private const val TAG = "DY/Probe"

    actual fun init(dir: String) {
        // HiLog 不需要初始化目录
    }

    actual fun note(tag: String, detail: String) {
        Log.w(TAG, "[$tag] $detail")
    }

    actual fun reset() {
        // 日志流没有可清空的缓冲
    }

    actual fun dumpSharePage(url: String) {
        // 不输出分享页地址
    }
}
