package io.stamethyst.backend.workshop

import android.content.Context

internal class WorkshopSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoImportEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_IMPORT_ENABLED, DEFAULT_AUTO_IMPORT_ENABLED)

    fun setAutoImportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_IMPORT_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "workshop_settings"
        private const val KEY_AUTO_IMPORT_ENABLED = "auto_import_enabled"
        const val DEFAULT_AUTO_IMPORT_ENABLED = true

        fun defaultAutoImportEnabled(): Boolean = DEFAULT_AUTO_IMPORT_ENABLED
    }
}
