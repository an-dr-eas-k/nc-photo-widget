package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var username: String
        get() = prefs.getString("user", "") ?: ""
        set(v) = prefs.edit().putString("user", v).apply()

    var appPassword: String
        get() = prefs.getString("password", "") ?: ""
        set(v) = prefs.edit().putString("password", v).apply()

    var folders: List<String>
        get() = (prefs.getString("folders", null) ?: prefs.getString("folder", null))
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = prefs.edit().putString("folders", v.joinToString("\n")).remove("folder").apply()

    var listRefreshHours: Int
        get() = prefs.getInt("list_refresh_hours", 24)
        set(v) = prefs.edit().putInt("list_refresh_hours", v).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 60)
        set(v) = prefs.edit().putInt("interval", v).apply()

    var themeMode: Int
        get() = prefs.getInt("theme", THEME_SYSTEM)
        set(v) = prefs.edit().putInt("theme", v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty() && username.isNotEmpty() && appPassword.isNotEmpty()

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        val INTERVALS_MINUTES = listOf(2, 5, 10, 15, 30, 60, 180, 360, 720, 1440)

        /** 0 means the photo list is only refreshed manually. */
        val LIST_REFRESH_HOURS = listOf(1, 6, 12, 24, 168, 0)

        fun normalizeServerUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            return if (t.contains("://")) t else "https://$t"
        }
    }
}
