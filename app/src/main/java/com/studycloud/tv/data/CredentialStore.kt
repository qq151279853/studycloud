package com.studycloud.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * 「记住登录」的本地存储。
 *
 * 三条红线（见《开发文档》7.4）：
 * 1. 绝不保存密码——电视盒子物理可及性高，密码泄露波及整个账号。
 * 2. 刷新失败清除令牌时保留用户名用于回填，并保留本地题库与规则，
 *    登录态丢失不应导致孩子无法答题或设备被锁死。
 * 3. deviceKey 自己生成并持久化，不用 IMEI / ANDROID_ID（隐私合规风险）。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** 首次启动生成并固定下来，设备解绑重装后才会变。 */
    val deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_KEY, it).apply()
        }

    var lastUsername: String?
        get() = prefs.getString(KEY_USERNAME, null)
        private set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    val refreshToken: String? get() = prefs.getString(KEY_REFRESH_TOKEN, null)

    val refreshExpiresAt: Long get() = prefs.getLong(KEY_REFRESH_EXPIRES_AT, 0)

    val deviceId: String? get() = prefs.getString(KEY_DEVICE_ID, null)

    val hasSession: Boolean get() = !refreshToken.isNullOrEmpty()

    fun saveSession(username: String, deviceId: String, refreshToken: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_REFRESH_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    /** 轮换后覆盖保存新令牌。 */
    fun rotateRefreshToken(refreshToken: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_REFRESH_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    /** 登录态失效：清令牌，**保留用户名与 deviceKey**，本地题库与规则不受影响。 */
    fun clearSessionKeepIdentity() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_REFRESH_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val FILE_NAME = "studycloud_credentials"
        const val KEY_USERNAME = "username"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_KEY = "device_key"
    }
}
