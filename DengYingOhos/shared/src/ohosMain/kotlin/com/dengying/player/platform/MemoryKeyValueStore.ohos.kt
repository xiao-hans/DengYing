package com.dengying.player.platform

/**
 * [KeyValueStore] 的内存实现 —— 文件 kv 构造失败时的兜底。
 *
 * ## 为什么需要它
 *
 * `OhosApp.init` 里 `FileKeyValueStore(dataDir)` 一旦抛错（目录不可用、锁分配失败等），
 * 原实现会把 `lateinit var kv` 留在未初始化状态；随后 `installOhosUiDeps()` 里的
 * `AhAuth.init(OhosApp.kv)` / `ThemePrefs.init(OhosApp.kv)` 直接
 * `UninitializedPropertyAccessException`，启动期崩溃。
 *
 * 退化为内存实现后：账号签名令牌、主题偏好、收藏等写入**本次进程内仍可读写**，
 * 只是重启即丢。这比「整个 UI 起不来」好，而且日志会明确标出降级。
 *
 * ## 语义
 *
 * 与 [FileKeyValueStore] 的契约一致：
 * - `sync = true`：数据已写入内存，返回 `true`。
 * - `sync = false`：内存写是即时的，等价于「已提交」，返回 `true`。
 *
 * 注意 `true` 在这里只表示「内存写入成功」，**不代表已落盘**——降级本身已经
 * 在 `OhosApp.init` 记录日志，调用方无需区分。
 */
class MemoryKeyValueStore : KeyValueStore {

    private val lock = PlatformLock()
    private val props = HashMap<String, String>()

    override fun getString(key: String, defValue: String): String =
        lock.withLock { props[key] ?: defValue }

    override fun allKeys(): Set<String> = lock.withLock { props.keys.toSet() }

    override fun putString(key: String, value: String, sync: Boolean): Boolean =
        lock.withLock {
            props[key] = value
            true
        }

    override fun remove(key: String, sync: Boolean): Boolean =
        lock.withLock {
            props.remove(key)
            true
        }
}
