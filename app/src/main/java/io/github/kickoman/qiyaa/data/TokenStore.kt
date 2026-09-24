package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The OAuth token, app-private (MODE_PRIVATE, backups disabled) — the same guarantee the
 * desktop app gives with its owner-only file. Empty = logged out.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY, "").orEmpty()

    fun save(token: String) {
        prefs.edit().putString(KEY, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "token"
    }
}
