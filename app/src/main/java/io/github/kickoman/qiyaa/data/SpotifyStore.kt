package io.github.kickoman.qiyaa.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Base64

class SpotifyStore(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun username(): String = preferences.getString(KEY_USERNAME, "").orEmpty()

    fun type(): Int = preferences.getInt(KEY_TYPE, NO_TYPE)

    fun blob(): ByteArray {
        val text = preferences.getString(KEY_BLOB, "").orEmpty()
        return try {
            Base64.getDecoder().decode(text)
        } catch (ignored: IllegalArgumentException) {
            // A blob that is not base64 is no login: the user logs in again.
            ByteArray(0)
        }
    }

    fun save(username: String, type: Int, blob: ByteArray) {
        preferences.edit {
            putString(KEY_USERNAME, username)
            putInt(KEY_TYPE, type)
            putString(KEY_BLOB, Base64.getEncoder().encodeToString(blob))
        }
    }

    fun clear() {
        preferences.edit {
            remove(KEY_USERNAME)
            remove(KEY_TYPE)
            remove(KEY_BLOB)
        }
    }

    @Synchronized
    fun deviceId(create: () -> String): String {
        preferences.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotEmpty)?.let { return it }
        val made = create()
        preferences.edit { putString(KEY_DEVICE_ID, made) }
        return made
    }

    companion object {
        const val FILE = "spotify"
        const val KEY_USERNAME = "username"
        const val KEY_TYPE = "type"
        const val KEY_BLOB = "blob"
        const val KEY_DEVICE_ID = "deviceId"
        const val NO_TYPE = -1
    }
}
