package com.studycloud.tv.data

import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ApiException(val status: Int, val code: String, override val message: String) : IOException(message)

/**
 * 服务端 API 客户端。
 *
 * 访问令牌只放内存，刷新令牌由 [CredentialStore] 加密落盘；
 * 401 时自动用刷新令牌换一次，仍失败才判定登录态失效。
 */
class ApiClient(
    private val baseUrl: String,
    private val credentials: CredentialStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val onSessionLost: () -> Unit = {},
) {
    @Volatile
    private var accessToken: String? = null

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshLock = Any()

    fun login(username: String, password: String, deviceName: String, appVersion: String): JSONObject {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("rememberMe", true)
            .put(
                "client",
                JSONObject()
                    .put("type", "device")
                    .put("deviceKey", credentials.deviceKey)
                    .put("name", deviceName)
                    .put("platform", "tv")
                    .put("model", android.os.Build.MODEL)
                    .put("osVersion", android.os.Build.VERSION.RELEASE)
                    .put("appVersion", appVersion),
            )

        val response = execute(buildRequest("POST", "/auth/login", body, withAuth = false))

        accessToken = response.getString("accessToken")
        credentials.saveSession(
            username = username,
            deviceId = response.getJSONObject("device").getString("id"),
            refreshToken = response.getString("refreshToken"),
            expiresAtMillis = expiryFrom(response),
        )

        return response
    }

    /** 冷启动时静默恢复会话；失败会清除令牌但保留用户名与本地数据。 */
    fun restoreSession(): Boolean = try {
        refreshAccessToken()
        true
    } catch (_: ApiException) {
        credentials.clearSessionKeepIdentity()
        onSessionLost()
        false
    }

    fun getManifest(): JSONObject = execute(buildRequest("GET", "/sync/manifest"))

    fun getRules(): JSONObject = execute(buildRequest("GET", "/sync/rules"))

    fun getQuestions(bankId: String, sinceVersion: Int, offset: Int, limit: Int): JSONObject =
        execute(
            buildRequest(
                "GET",
                "/sync/banks/$bankId/questions?sinceVersion=$sinceVersion&offset=$offset&limit=$limit",
            ),
        )

    fun heartbeat(payload: JSONObject): JSONObject =
        execute(buildRequest("POST", "/sync/heartbeat", payload))

    fun uploadEvents(payload: JSONObject): JSONObject =
        execute(buildRequest("POST", "/events/batch", payload))

    private fun refreshAccessToken(): String = synchronized(refreshLock) {
        val token = credentials.refreshToken ?: throw ApiException(401, "TOKEN_REVOKED", "本地没有登录态")

        val body = JSONObject()
            .put("refreshToken", token)
            .put("deviceId", credentials.deviceId)

        val response = execute(buildRequest("POST", "/auth/refresh", body, withAuth = false))

        credentials.rotateRefreshToken(response.getString("refreshToken"), expiryFrom(response))

        response.getString("accessToken").also { accessToken = it }
    }

    private fun buildRequest(
        method: String,
        path: String,
        body: JSONObject? = null,
        withAuth: Boolean = true,
    ): Request.Builder {
        val builder = Request.Builder().url("$baseUrl/api/v1$path")

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            else -> throw IllegalArgumentException("unsupported method $method")
        }

        if (withAuth) {
            builder.tag(AuthRequired::class.java, AuthRequired)
        }

        return builder
    }

    private fun execute(builder: Request.Builder): JSONObject {
        val needsAuth = builder.build().tag(AuthRequired::class.java) != null

        var response = send(builder, if (needsAuth) accessToken else null)

        if (response.code == 401 && needsAuth) {
            response.close()
            response = send(builder, refreshAccessToken())
        }

        response.use {
            val text = it.body?.string().orEmpty()

            if (!it.isSuccessful) {
                val problem = runCatching { JSONObject(text) }.getOrNull()

                throw ApiException(
                    it.code,
                    problem?.optString("code").orEmpty().ifEmpty { "UNKNOWN" },
                    problem?.optString("title").orEmpty().ifEmpty { "请求失败（${it.code}）" },
                )
            }

            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    private fun send(builder: Request.Builder, token: String?): Response {
        val request = builder.build().newBuilder().apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()

        return http.newCall(request).execute()
    }

    /**
     * 服务端直接给秒数。刻意不解析 `refreshExpiresAt` 的 ISO-8601 字符串：
     * java.time 要 API 26，而我们的 minSdk 是 23，老电视上会直接崩。
     */
    private fun expiryFrom(response: JSONObject): Long {
        val seconds = response.optLong("refreshExpiresIn", 0L)
        return if (seconds > 0) System.currentTimeMillis() + seconds * 1000 else 0L
    }

    private object AuthRequired
}
