package io.stamethyst.ui.main

import android.content.Context
import androidx.core.content.edit
import java.util.LinkedHashMap
import org.json.JSONObject

internal object ModAliasStore {
    fun loadAliases(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_ALIASES, "{}") ?: "{}"
        return try {
            val root = JSONObject(raw)
            val aliases = LinkedHashMap<String, String>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val storagePath = keys.next().trim()
                val alias = root.optString(storagePath).trim()
                if (storagePath.isNotEmpty() && alias.isNotEmpty()) {
                    aliases[storagePath] = alias
                }
            }
            aliases
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun resolveAlias(context: Context, storagePath: String): String {
        return resolveAlias(storagePath, loadAliases(context))
    }

    fun resolveAlias(storagePath: String, aliases: Map<String, String>): String {
        if (aliases.isEmpty()) {
            return ""
        }
        resolveModStoragePathCandidates(storagePath).forEach { candidate ->
            val alias = aliases[candidate]?.trim()
            if (!alias.isNullOrEmpty()) {
                return alias
            }
        }
        return ""
    }

    fun setAlias(context: Context, storagePath: String, alias: String) {
        val normalizedPath = storagePath.trim()
        if (normalizedPath.isEmpty()) {
            return
        }
        val aliases = LinkedHashMap(loadAliases(context))
        removeAliasCandidates(aliases, normalizedPath)
        val normalizedAlias = alias.trim()
        if (normalizedAlias.isNotEmpty()) {
            aliases[normalizedPath] = normalizedAlias
        }
        saveAliases(context, aliases)
    }

    fun setAliases(context: Context, aliasesByStoragePath: Map<String, String>) {
        if (aliasesByStoragePath.isEmpty()) {
            return
        }
        val aliases = LinkedHashMap(loadAliases(context))
        aliasesByStoragePath.keys.forEach { storagePath ->
            removeAliasCandidates(aliases, storagePath.trim())
        }
        aliasesByStoragePath.forEach { (storagePath, alias) ->
            val normalizedPath = storagePath.trim()
            val normalizedAlias = alias.trim()
            if (normalizedPath.isNotEmpty() && normalizedAlias.isNotEmpty()) {
                aliases[normalizedPath] = normalizedAlias
            }
        }
        saveAliases(context, aliases)
    }

    fun isShowFileNameRemovalNoticeHandled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_FILE_NAME_REMOVAL_NOTICE_HANDLED, false)
    }

    fun markShowFileNameRemovalNoticeHandled(context: Context) {
        prefs(context).edit {
            putBoolean(KEY_SHOW_FILE_NAME_REMOVAL_NOTICE_HANDLED, true)
        }
    }

    private fun removeAliasCandidates(aliases: MutableMap<String, String>, storagePath: String) {
        if (storagePath.isEmpty()) {
            return
        }
        resolveModStoragePathCandidates(storagePath).forEach { candidate ->
            aliases.remove(candidate)
        }
    }

    private fun saveAliases(context: Context, aliases: Map<String, String>) {
        val root = JSONObject()
        aliases.forEach { (storagePath, alias) ->
            val normalizedPath = storagePath.trim()
            val normalizedAlias = alias.trim()
            if (normalizedPath.isNotEmpty() && normalizedAlias.isNotEmpty()) {
                root.put(normalizedPath, normalizedAlias)
            }
        }
        prefs(context).edit {
            if (root.length() == 0) {
                remove(KEY_ALIASES)
            } else {
                putString(KEY_ALIASES, root.toString())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
    )

    private const val PREFS_NAME = "ModAliases"
    private const val KEY_ALIASES = "aliases"
    private const val KEY_SHOW_FILE_NAME_REMOVAL_NOTICE_HANDLED = "show_file_name_removal_notice_handled"
}
