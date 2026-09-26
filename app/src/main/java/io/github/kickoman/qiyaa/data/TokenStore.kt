package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences

class TokenStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): String = preferences.getString(KEY_TOKEN, "").orEmpty()

    fun save(token: String) {
        preferences.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        const val FILE = "auth"
        const val KEY_TOKEN = "token"
    }
}
