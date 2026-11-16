package com.cruiselink.app.data

import android.content.Context
import android.os.Build
import java.util.UUID

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cruiselink_prefs", Context.MODE_PRIVATE)

    val userId: String
        get() {
            var id = prefs.getString("user_id", null)
            if (id.isNullOrBlank()) {
                id = "u-${UUID.randomUUID().toString().take(8)}"
                prefs.edit().putString("user_id", id).apply()
            }
            return id
        }

    var displayName: String
        get() = prefs.getString("display_name", Build.MODEL ?: "旅伴") ?: "旅伴"
        set(value) {
            prefs.edit().putString("display_name", value.trim().ifBlank { "旅伴" }).apply()
        }

    var avatarBase64: String
        get() = prefs.getString("avatar_base64", "") ?: ""
        set(value) {
            prefs.edit().putString("avatar_base64", value).apply()
        }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) {
            prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()
        }

    var themeMode: String
        get() = prefs.getString("theme_mode", "dark") ?: "dark"
        set(value) {
            prefs.edit().putString("theme_mode", value).apply()
        }
}
