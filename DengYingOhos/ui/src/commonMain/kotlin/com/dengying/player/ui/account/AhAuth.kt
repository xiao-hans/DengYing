package com.dengying.player.ui.account

import com.dengying.player.data.json.JsonX
import com.dengying.player.data.net.HttpClient
import kotlinx.serialization.json.JsonObject
import com.dengying.player.platform.Clock
import com.dengying.player.platform.KeyValueStore
import kotlin.concurrent.Volatile
import kotlin.random.Random


/**
 * AH 账号体系（`danmu.bmsp9.xyz`）的登录 / 注册入口。
 *
 * 【接口契约】见 `D:\workspace\AHVideo\AH视频_账号体系登录注册接口文档.md` §4：
 * `POST /auth/login` 与 `POST /auth/register` 的 JSON body 必须同时带 `ts`
 * （秒级 Unix 时间戳）与 `app_auth_token`（服务端签名令牌）。二者放 query 或
 * header 会得到 422；ts 偏差超出 ±300 秒会得到 `Expired app_auth_token`；
 * 签名不匹配会得到 `Invalid app_auth_token`。校验顺序在账号字段之前，所以只要
 * 签名没通，账号对不对根本不会被判断。
 *
 * 【为什么这里允许 HTTP 明文】账号服务只提供 `http://` 端点；应用已有的
 * `res/xml/network_security_config.xml` 为采集源保留了全局明文例外且仍校验 TLS
 * 证书链。这里沿用同一条出网通道 [HttpClient]，不新建 client、不引第三方 SDK；
 * 例外只限于这一个已文档化的账号域，不扩展到播放链路。
 *
 * 【签名未取得时的行为】静态分析只确认了算法输入，未提取到
 * `appAuthTokenSecret`；本实现**不猜密钥、不伪造成功**。未配置令牌时请求照发、
 * 服务端如实返回 `403 Invalid app_auth_token`，由 UI 展示为可读提示。真机用
 * frida 抓到一组 `ts`/`app_auth_token` 后，可用 [submitAppAuthToken] 验证签名
 * 假设；如果抓到的是密钥，再按文档补 `signAppAuthToken(ts)`，届时替换取用
 * 逻辑即可，页面与调用方无需改动。
 *
 * 【登录态】成功响应的字段结构尚未实测（文档 §3 标为 C）。参考取自
 * `D:\workspace\AHVideo\tools\ah_auth_client.mjs`：顶层读 `access_token` / `refresh_token` /
 * `user`，同时兼容嵌在 `data` 里的形态。解不出 access_token 就不写登录态，不伪造成功。
 * 这条链路只管账号登录态；「两个BT」4K 线路的解锁仍由 BttwoAuth 独立负责。
 */
object AhAuth {

    private const val HOST = "http://danmu.bmsp9.xyz"
    private const val LOGIN_PATH = "$HOST/auth/login"
    private const val REGISTER_PATH = "$HOST/auth/register"
    private const val KEY_APP_AUTH_TOKEN = "ah_app_auth_token_v1"
    private const val KEY_DEVICE_ID = "ah_device_id_v1"
    private const val KEY_ACCOUNT = "ah_account_v1"
    private const val KEY_SESSION_RAW = "ah_session_raw_v1"

    // 与原客户端 MMKV 键名对齐（auth_access_token_v1 等），方便对照抓包核对。
    private const val KEY_ACCESS_TOKEN = "auth_access_token_v1"
    private const val KEY_REFRESH_TOKEN = "auth_refresh_token_v1"
    private const val KEY_USER_JSON = "auth_user_v1"
    private const val CONNECT_MS = 8_000
    private const val READ_MS = 10_000

    sealed interface Outcome {
        data class Ok(val account: String, val message: String) : Outcome
        data class Error(val message: String) : Outcome
    }

    @Volatile
    private var kv: KeyValueStore? = null

    /** 由平台壳在装配时注入（与 UiDepsHolder.init 同处，setContent 之前）。 */
    fun init(store: KeyValueStore) {
        kv = store
    }

    private fun kv(): KeyValueStore? = kv

    /** 是否已登录：以本地存有 access_token 为准。令牌过期由下次请求的 401 兜底。 */
    fun isLoggedIn(): Boolean = accessToken().isNotEmpty()

    fun accessToken(): String = kv()?.getString(KEY_ACCESS_TOKEN, "") ?: ""

    /** 展示用昵称：优先取用户资料里的 nickname，没有就退回登录时填的账号。 */
    fun nickname(): String {
        val stored = kv()?.getString(KEY_USER_JSON, "") ?: ""
        val name = JsonX.str(JsonX.parseObject(stored), "nickname")
        if (name.isNotEmpty()) return name
        return kv()?.getString(KEY_ACCOUNT, "") ?: ""
    }

    /** 头像地址；没有资料或资料里没有该字段时为空串。 */
    fun avatarUrl(): String {
        val stored = kv()?.getString(KEY_USER_JSON, "") ?: ""
        return JsonX.str(JsonX.parseObject(stored), "avatar_url")
    }

    /** 本地还没有用户资料时返回 true，供「我的」页补拉一次 GET /user。 */
    fun needsProfile(): Boolean {
        if (!isLoggedIn()) return false
        val stored = kv()?.getString(KEY_USER_JSON, "") ?: ""
        return JsonX.str(JsonX.parseObject(stored), "nickname").isEmpty()
    }

    /**
     * 用已存的 access_token 拉一次 GET /user 并落盘。
     * 401 说明令牌失效，清掉登录态；其余失败保持原状，下次再试。
     * @return 是否拿到了资料。
     */
    suspend fun refreshProfile(): Boolean {
        val token = accessToken()
        if (token.isEmpty()) return false
        val result = try {
            HttpClient.get(
                url = "$HOST/user",
                headers = mapOf(
                    "User-Agent" to HttpClient.UA_SYSTEM,
                    "Authorization" to "Bearer $token",
                    "Accept" to "application/json, text/plain, */*",
                ),
                connectMs = CONNECT_MS,
                readMs = READ_MS,
            )
        } catch (_: Exception) {
            return false
        }
        if (result.status == 401) {
            logout()
            return false
        }
        if (result.status !in 200..299) return false
        val json = JsonX.parseObject(result.body) ?: return false
        val data = JsonX.optObject(json, "data")
        val profile = when {
            JsonX.str(data, "nickname").isNotEmpty() -> data
            JsonX.str(json, "nickname").isNotEmpty() -> json
            else -> null
        } ?: return false
        kv()?.putString(KEY_USER_JSON, slimUser(profile), sync = true)
        return true
    }

    /** 退出登录：只清本地登录态，不调用服务端（文档没有登出端点）。 */
    fun logout() {
        val store = kv() ?: return
        store.remove(KEY_ACCESS_TOKEN, sync = true)
        store.remove(KEY_REFRESH_TOKEN, sync = true)
        store.remove(KEY_USER_JSON, sync = true)
        store.remove(KEY_ACCOUNT, sync = true)
        store.remove(KEY_SESSION_RAW, sync = true)
    }

    /**
     * 账号 / 邮箱 + 密码登录。
     *
     * 本地只做「非空 + 密码至少 6 位」的校验（对应文档 UI 文案）；其余交给服务端。
     */
    suspend fun login(account: String, password: String): Outcome {
        val user = account.trim()
        if (user.isEmpty()) return Outcome.Error("请输入账号")
        if (password.isEmpty()) return Outcome.Error("请输入密码")
        if (password.length < 6) return Outcome.Error("密码长度不能少于6位")
        val body = JsonX.buildObject(
            "username" to user,
            "password" to password,
            "ts" to Clock.nowMs() / 1000L,
            "app_auth_token" to appAuthToken(),
        ).toString()
        return request(LOGIN_PATH, body, user, success = "登录成功")
    }

    /** 创建账号：密码 + 确认密码 + 可选邀请码，device_id 由本端稳定生成。 */
    suspend fun register(
        account: String,
        password: String,
        confirmPassword: String,
        inviteCode: String,
    ): Outcome {
        val user = account.trim()
        if (user.isEmpty()) return Outcome.Error("请输入账号")
        if (password.isEmpty()) return Outcome.Error("请输入密码")
        if (password.length < 6) return Outcome.Error("密码长度不能少于6位")
        if (password != confirmPassword) return Outcome.Error("新密码和重复新密码不一致")
        val body = JsonX.buildObject(
            "username" to user,
            "password" to password,
            "invite_code" to inviteCode.trim(),
            "device_id" to deviceId(),
            "ts" to Clock.nowMs() / 1000L,
            "app_auth_token" to appAuthToken(),
        ).toString()
        return request(REGISTER_PATH, body, user, success = "注册成功")
    }

    /**
     * QQ 授权换 JWT 的后端路径尚未实测（文档 §6、§12）。
     *
     * 客户端能静态确认的只有：appid `101983842`、scheme `tencent101983842`、
     * 拉起 `mqq://` / `openmobile.qq.com/oauth2.0/m_authorize`、再取 openid 与
     * 用户资料。最后一步「把授权结果交给账号服务」既没有 URL 也没有字段定义，
     * 不编造端点、不发请求，直接给一条可行动的提示。
     */
    fun qqLoginNotReady(): Outcome = Outcome.Error(
        "QQ 登录的授权换令牌接口尚未实测，暂不可用；请先用账号 / 邮箱登录",
    )

    /**
     * 保存真机 frida 抓到的一组 `app_auth_token`（文档 §8.3）。
     *
     * 注意：令牌与 `ts` 绑定且只有 ±300 秒有效，单独保存旧值不会让后续请求
     * 自动通过；这里只是给逆向复核留一个落盘入口，不改写 [appAuthToken] 的
     * 取用时机。
     */
    fun submitAppAuthToken(token: String) {
        if (token.isBlank()) return
        kv()?.putString(KEY_APP_AUTH_TOKEN, token.trim(), sync = true)
    }

    fun savedAppAuthToken(): String = kv()?.getString(KEY_APP_AUTH_TOKEN, "") ?: ""

    private suspend fun request(
        url: String,
        body: String,
        account: String,
        success: String,
    ): Outcome {
        // 传输层异常（DNS / 超时 / 明文策略）由平台 actual 抛到这里；
        // 不把异常原文塞进 UI，也不让登录按钮卡在 busy。
        val result = try {
            HttpClient.post(
                url = url,
                body = body,
                headers = mapOf(
                    "User-Agent" to HttpClient.UA_SYSTEM,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json, text/plain, */*",
                ),
                connectMs = CONNECT_MS,
                readMs = READ_MS,
            )
        } catch (_: Exception) {
            return Outcome.Error("网络异常，请检查网络后重试")
        }
        if (result.elapsedMs == 0L && result.status == 0) {
            return Outcome.Error("网络异常，请检查网络后重试")
        }
        // 明文 HTTP 在部分平台（鸿蒙传输层）会拒绝；Android 侧由 network_security_config 放行。
        val json = JsonX.parseObject(result.body)
        val message = JsonX.str(json, "message").ifEmpty {
            JsonX.str(json, "msg").ifEmpty { "登录服务返回 HTTP ${result.status}" }
        }
        if (result.status !in 200..299) {
            return Outcome.Error(
                when {
                    message.contains("Invalid app_auth_token", ignoreCase = true) ->
                        "登录服务签名令牌未取得（403 Invalid app_auth_token），暂不能登录或注册"
                    message.contains("Expired app_auth_token", ignoreCase = true) ->
                        "设备时间与服务器相差过大（Expired app_auth_token），请校准时间"
                    message.contains("app_auth_token", ignoreCase = true) ->
                        "登录服务签名令牌校验未通过：$message"
                    else -> if (url == LOGIN_PATH) "登录失败：$message" else "注册失败：$message"
                },
            )
        }
        // 响应带 code 且不是 0/200，按业务失败处理；没有 code 字段则只认 HTTP 2xx。
        val hasCode = json?.containsKey("code") == true
        val code = JsonX.int(json, "code")
        if (hasCode && code != 0 && code != 200) {
            return Outcome.Error(if (url == LOGIN_PATH) "登录失败：$message" else "注册失败：$message")
        }
        // 令牌字段结构未实测：顶层与 data 嵌套两种形态都试，解不出就不写登录态。
        val token = pickToken(json, "access_token")
        if (token.isEmpty()) {
            return Outcome.Error(
                if (url == LOGIN_PATH) "登录失败：服务端未返回登录令牌" else "注册失败：服务端未返回登录令牌",
            )
        }
        val store = kv()
        store?.putString(KEY_ACCESS_TOKEN, token, sync = true)
        val refresh = pickToken(json, "refresh_token")
        if (refresh.isNotEmpty()) store?.putString(KEY_REFRESH_TOKEN, refresh, sync = true)
        store?.putString(KEY_ACCOUNT, account, sync = true)
        store?.putString(KEY_SESSION_RAW, result.body.take(4096), sync = true)
        // 用户资料能从响应里直接拿到就用，拿不到再拉一次 GET /user（文档 §5.6）。
        val inlineUser = pickUser(json)
        val profile = inlineUser ?: fetchProfile(token)
        if (profile != null) store?.putString(KEY_USER_JSON, slimUser(profile), sync = true)
        return Outcome.Ok(account, success)
    }

    /** 顶层没有该字段时退到 `data` 里找；两处都没有返回空串。 */
    private fun pickToken(json: JsonObject?, key: String): String {
        val top = JsonX.str(json, key)
        if (top.isNotEmpty()) return top
        return JsonX.str(JsonX.optObject(json, "data"), key)
    }

    /** 用户资料对象：顶层 `user`，其次 `data.user`，再其次 `data` 本身（带 nickname 时），都没有返回 null。 */
    private fun pickUser(json: JsonObject?): JsonObject? {
        val top = JsonX.optObject(json, "user")
        if (top != null && JsonX.str(top, "nickname").isNotEmpty()) return top
        val data = JsonX.optObject(json, "data") ?: return null
        val nested = JsonX.optObject(data, "user")
        if (nested != null && JsonX.str(nested, "nickname").isNotEmpty()) return nested
        return if (JsonX.str(data, "nickname").isNotEmpty()) data else null
    }

    /** 只保留展示用字段，避免把整个响应原样落盘。 */
    private fun slimUser(user: JsonObject): String = JsonX.buildObject(
        "id" to JsonX.long(user, "id"),
        "user_no" to JsonX.long(user, "user_no"),
        "account" to JsonX.str(user, "account"),
        "nickname" to JsonX.str(user, "nickname"),
        "avatar_url" to JsonX.str(user, "avatar_url"),
        "vip" to JsonX.bool(user, "vip"),
        "vip_expires_at" to JsonX.long(user, "vip_expires_at"),
    ).toString()

    /** GET /user（文档 §5.6）。失败返回 null：登录本身已成功，资料缺失只影响展示，下次进「我的」页再补。 */
    private suspend fun fetchProfile(token: String): JsonObject? {
        if (!refreshProfile()) return null
        val stored = kv()?.getString(KEY_USER_JSON, "") ?: ""
        return JsonX.parseObject(stored)
    }

    /** 16 位 hex，首次生成后落盘，满足注册请求体约定且同一设备保持稳定。 */
    private fun deviceId(): String {
        val saved = kv()?.getString(KEY_DEVICE_ID, "") ?: ""
        if (saved.length == 16 && saved.all { it.isDigit() || it in 'a'..'f' }) return saved
        val hex = "0123456789abcdef"
        val fresh = buildString(16) { repeat(16) { append(hex[Random.nextInt(16)]) } }
        kv()?.putString(KEY_DEVICE_ID, fresh, sync = true)
        return fresh
    }

    /**
     * 签名令牌来源：真机 frida 抓到的一组值由 [submitAppAuthToken] 落盘；
     * 未配置时返回空串。空串不是「假令牌」，它让服务端按既有校验顺序返回
     * 403，UI 如实提示，不制造登录成功的假象。抓到密钥后按文档补
     * `signAppAuthToken(ts)`，只需替换这里的取用逻辑，页面与调用方不变。
     */
    private fun appAuthToken(): String = kv()?.getString(KEY_APP_AUTH_TOKEN, "") ?: ""
}
