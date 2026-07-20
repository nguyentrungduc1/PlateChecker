package com.leeduc.platechecker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Lưu tài khoản/mật khẩu trên chính thiết bị, được mã hoá bằng khoá nằm trong
 * Android Keystore (phần cứng bảo mật của máy) - giống cơ chế Chrome dùng để
 * lưu mật khẩu đã nhớ, không lưu dạng chữ thường (plain text), không đồng bộ
 * lên đâu cả, chỉ nằm trên máy này.
 */
object CredentialStore {

    private const val PREFS_NAME = "vos_credentials_encrypted"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getUsername(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)

    fun getPassword(context: Context): String? = prefs(context).getString(KEY_PASSWORD, null)

    fun hasSavedCredentials(context: Context): Boolean =
        !getUsername(context).isNullOrBlank() && !getPassword(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
