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
    private val encryptedPrefs by lazy { createEncryptedPrefsOrNull(appContext) }
    private val fallbackPrefs by lazy {
        appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCredentials(): BaiduTranslationCredentials {
        val encryptedCredentials = encryptedPrefs?.let { prefs ->
            readCredentials(prefs, storageName = "encrypted")
        }
        return encryptedCredentials
            ?: readCredentials(fallbackPrefs, storageName = "fallback")
            ?: BaiduTranslationCredentials()
    }

    fun hasConfiguredCredentials(): Boolean = getCredentials().isConfigured()

    fun setCredentials(credentials: BaiduTranslationCredentials) {
        val normalizedAppId = credentials.appId.trim()
        val normalizedApiKey = credentials.apiKey.trim()
        val savedEncrypted = encryptedPrefs?.let { prefs ->
            writeCredentials(
                prefs = prefs,
                appId = normalizedAppId,
                apiKey = normalizedApiKey,
                storageName = "encrypted",
            )
        } == true

        if (savedEncrypted) {
            clearFallbackCredentials()
            return
        }

        writeCredentials(
            prefs = fallbackPrefs,
            appId = normalizedAppId,
            apiKey = normalizedApiKey,
            storageName = "fallback",
        )
    }

    private fun readCredentials(
        prefs: SharedPreferences,
        storageName: String,
    ): BaiduTranslationCredentials? =
        runCatching {
            BaiduTranslationCredentials(
                appId = prefs.getString(KEY_APP_ID, null)?.trim().orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty(),
            )
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read Baidu translation credentials from $storageName storage.", error)
            null
        }

    private fun writeCredentials(
        prefs: SharedPreferences,
        appId: String,
        apiKey: String,
        storageName: String,
    ): Boolean =
        runCatching {
            val saved = prefs.edit().apply {
                putOrRemove(KEY_APP_ID, appId)
                putOrRemove(KEY_API_KEY, apiKey)
            }.commit()
            if (!saved) {
                Log.w(TAG, "Unable to save Baidu translation credentials to $storageName storage.")
            }
            saved
        }.getOrElse { error ->
            Log.w(TAG, "Unable to save Baidu translation credentials to $storageName storage.", error)
            false
        }

    private fun clearFallbackCredentials() {
        runCatching {
            fallbackPrefs.edit().clear().commit()
        }.onFailure { error ->
            Log.w(TAG, "Unable to clear fallback Baidu translation credentials.", error)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
        } else {
            putString(key, value)
        }
    }

    companion object {
        private const val FALLBACK_PREFS_NAME = BAIDU_TRANSLATION_CREDENTIALS_FALLBACK_PREFS_NAME
        private const val KEY_APP_ID = BAIDU_TRANSLATION_CREDENTIALS_KEY_APP_ID
        private const val KEY_API_KEY = BAIDU_TRANSLATION_CREDENTIALS_KEY_API_KEY
    }
}

private fun createEncryptedPrefsOrNull(context: Context): SharedPreferences? =
    runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            BAIDU_TRANSLATION_CREDENTIALS_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        Log.w(TAG, "Encrypted SharedPreferences unavailable for Baidu translation credentials; using fallback storage.", error)
        null
    }

private const val BAIDU_TRANSLATION_CREDENTIALS_PREFS_NAME = "baidu_translation_credentials"
private const val BAIDU_TRANSLATION_CREDENTIALS_FALLBACK_PREFS_NAME = "baidu_translation_credentials_fallback"
private const val BAIDU_TRANSLATION_CREDENTIALS_KEY_APP_ID = "app_id"
private const val BAIDU_TRANSLATION_CREDENTIALS_KEY_API_KEY = "api_key"
private const val TAG = "BaiduTranslation"
