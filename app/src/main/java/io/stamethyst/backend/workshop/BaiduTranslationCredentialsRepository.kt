package io.stamethyst.backend.workshop

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class BaiduTranslationCredentials(
    val appId: String = "",
    val apiKey: String = "",
) {
    fun isConfigured(): Boolean = appId.isNotBlank() && apiKey.isNotBlank()
}

internal class BaiduTranslationCredentialsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        createEncryptedPrefsOrFallback(
            context = appContext,
            encryptedPrefsName = PREFS_NAME,
            fallbackPrefsName = FALLBACK_PREFS_NAME,
        )
    }

    fun getCredentials(): BaiduTranslationCredentials =
        BaiduTranslationCredentials(
            appId = prefs.getString(KEY_APP_ID, null)?.trim().orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty(),
        )

    fun hasConfiguredCredentials(): Boolean = getCredentials().isConfigured()

    fun setCredentials(credentials: BaiduTranslationCredentials) {
        val normalizedAppId = credentials.appId.trim()
        val normalizedApiKey = credentials.apiKey.trim()
        prefs.edit().apply {
            if (normalizedAppId.isEmpty()) {
                remove(KEY_APP_ID)
            } else {
                putString(KEY_APP_ID, normalizedAppId)
            }

            if (normalizedApiKey.isEmpty()) {
                remove(KEY_API_KEY)
            } else {
                putString(KEY_API_KEY, normalizedApiKey)
            }
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "baidu_translation_credentials"
        private const val FALLBACK_PREFS_NAME = "baidu_translation_credentials_fallback"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_API_KEY = "api_key"
    }
}

private fun createEncryptedPrefsOrFallback(
    context: Context,
    encryptedPrefsName: String,
    fallbackPrefsName: String,
): SharedPreferences =
    runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            encryptedPrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        Log.w(TAG, "Encrypted SharedPreferences unavailable for Baidu translation credentials; using fallback storage.", error)
        context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
    }

private const val TAG = "BaiduTranslation"
