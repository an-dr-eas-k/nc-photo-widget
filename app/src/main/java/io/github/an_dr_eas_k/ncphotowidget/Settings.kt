package io.github.an_dr_eas_k.ncphotowidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import java.io.File

/**
 * A Nextcloud system tag in a filter. A [required] tag must be on every photo; the other chosen
 * tags are alternatives, of which a photo needs at least one.
 */
data class Tag(val id: Long, val name: String, val required: Boolean = false) {
    fun encode() = "$id\t$name\t${if (required) 1 else 0}"

    companion object {
        /** A line without the last field comes from before tags could be alternatives: all were required. */
        fun decode(line: String): Tag? {
            val parts = line.split('\t', limit = 3)
            val id = parts[0].toLongOrNull() ?: return null
            return Tag(id, parts.getOrElse(1) { "" }, parts.getOrNull(2) != "0")
        }
    }
}

/** App-wide settings: the one Nextcloud account and the theme. */
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

    /** A login the user started in the browser and that is not yet finished; kept so it survives the screen. */
    class PendingLogin(val pollUrl: String, val token: String, val server: String, val deadline: Long)

    var pendingLogin: PendingLogin?
        get() {
            val token = prefs.getString("login_token", null) ?: return null
            return PendingLogin(
                prefs.getString("login_poll_url", "") ?: "", token,
                prefs.getString("login_server", "") ?: "", prefs.getLong("login_deadline", 0),
            )
        }
        set(v) {
            val editor = prefs.edit()
            if (v == null) {
                editor.remove("login_token").remove("login_poll_url").remove("login_server").remove("login_deadline")
            } else {
                editor.putString("login_token", v.token).putString("login_poll_url", v.pollUrl)
                    .putString("login_server", v.server).putLong("login_deadline", v.deadline)
            }
            editor.apply()
        }

    /** Stores the account of a finished login. The address is the one the user typed: the server's may be internal. */
    fun completeLogin(pending: PendingLogin, login: LoginFlow.Result) {
        prefs.edit()
            .putString("server", pending.server).putString("user", login.username).putString("password", login.appPassword)
            .remove("login_token").remove("login_poll_url").remove("login_server").remove("login_deadline")
            .apply()
    }

    var themeMode: Int
        get() = prefs.getInt("theme", THEME_SYSTEM)
        set(v) = prefs.edit().putInt("theme", v).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotEmpty() && username.isNotEmpty() && appPassword.isNotEmpty()

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        /** 0 means the picture only changes on tap. */
        const val INTERVAL_MANUAL = 0
        val INTERVALS_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440, INTERVAL_MANUAL)

        /** 0 means the photo list is only refreshed manually. */

        fun normalizeServerUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            return if (t.contains("://")) t else "https://$t"
        }
    }
}

/** The settings of one widget: what it shows, how often it changes, and how it looks. */
class WidgetConfig(context: Context, val widgetId: Int) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)

    var folders: List<String>
        get() = prefs.getString("folders", "")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = prefs.edit().putString("folders", v.joinToString("\n")).apply()

    var tags: List<Tag>
        get() = prefs.getString("tags", "")?.split('\n').orEmpty().mapNotNull { Tag.decode(it) }
        set(v) = prefs.edit().putString("tags", v.joinToString("\n") { it.encode() }).apply()

    var timeFilter: TimeFilter
        get() = TimeFilter(prefs.getInt("time_days", 0), prefs.getBoolean("time_repeat", false))
        set(v) = prefs.edit().putInt("time_days", v.days).putBoolean("time_repeat", v.repeatYears).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 60)
        set(v) = prefs.edit().putInt("interval", v).apply()

    var filter: PhotoFilter
        get() = PhotoFilter.entries.firstOrNull { it.name == prefs.getString("filter", null) } ?: PhotoFilter.NONE
        set(v) = prefs.edit().putString("filter", v.name).apply()

    var shape: WidgetShape
        get() = WidgetShape.entries.firstOrNull { it.name == prefs.getString("shape", null) } ?: WidgetShape.ROUNDED
        set(v) = prefs.edit().putString("shape", v.name).apply()

    companion object {
        private fun prefsName(id: Int) = "widget_$id"

        fun widgetIds(c: Context): IntArray =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, PhotoWidgetProvider::class.java))

        /** Removes everything stored for a widget that was deleted. */
        fun delete(c: Context, id: Int) {
            c.applicationContext.deleteSharedPreferences(prefsName(id))
            PhotoState.delete(c, id)
        }

        /**
         * Versions before 2.0 had one set of settings and one photo state for all widgets. They are
         * handed to the widgets that exist: the settings to each, the shown photos to the first.
         */
        fun migrateLegacy(c: Context) {
            val legacy = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val keys = listOf("folders", "folder", "tags", "interval", "list_refresh_hours", "shape")
            if (keys.none { legacy.contains(it) }) return
            val ids = widgetIds(c)
            if (ids.isEmpty()) return

            val folders = (legacy.getString("folders", null) ?: legacy.getString("folder", null))
                ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            for (id in ids) {
                c.applicationContext.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE).edit()
                    .putString("folders", folders.joinToString("\n"))
                    .putString("tags", legacy.getString("tags", ""))
                    .putString("shape", legacy.getString("shape", null))
                    .putInt("interval", legacy.getInt("interval", 60))
                    .apply()
            }
            PhotoState.migrateLegacy(c, ids.first())
            // Settings and the account stay; only the moved keys go.
            legacy.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }
}
