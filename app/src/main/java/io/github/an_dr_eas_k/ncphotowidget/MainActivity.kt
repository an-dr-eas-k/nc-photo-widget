package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var serverField: EditText
    private lateinit var foldersContainer: LinearLayout
    private val folders = mutableListOf<String>()
    private lateinit var intervalSpinner: Spinner
    private lateinit var listRefreshSpinner: Spinner
    private lateinit var listStats: TextView
    private lateinit var refreshNow: Button
    private lateinit var status: TextView

    @Volatile
    private var polling = false

    private var configureWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        // Launched from the widget's setup/reconfigure button; must answer with the widget id.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            configureWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId))
        }
        // The manifest theme already follows the system, so only explicit choices need overriding.
        when (settings.themeMode) {
            Settings.THEME_LIGHT -> setTheme(R.style.Theme_NC_Light)
            Settings.THEME_DARK -> setTheme(R.style.Theme_NC_Dark)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setUpThemeSwitch()

        serverField = findViewById(R.id.server)
        foldersContainer = findViewById(R.id.folders)
        intervalSpinner = findViewById(R.id.interval)
        listRefreshSpinner = findViewById(R.id.list_refresh)
        listStats = findViewById(R.id.list_stats)
        refreshNow = findViewById(R.id.refresh_now)
        status = findViewById(R.id.status)

        intervalSpinner.adapter = spinnerAdapter(R.array.interval_labels)
        listRefreshSpinner.adapter = spinnerAdapter(R.array.list_refresh_labels)

        serverField.setText(settings.serverUrl)
        folders.addAll(settings.folders)
        renderFolders()
        intervalSpinner.setSelection(Settings.INTERVALS_MINUTES.indexOf(settings.intervalMinutes).coerceAtLeast(0))
        listRefreshSpinner.setSelection(Settings.LIST_REFRESH_HOURS.indexOf(settings.listRefreshHours).coerceAtLeast(0))
        showLoginStatus()

        findViewById<Button>(R.id.login).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.add_folder).setOnClickListener { addFolder() }
        refreshNow.setOnClickListener { refreshPhotoList() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        // The widget may have scanned in the background since this screen was last shown.
        showListStats()
    }

    private fun spinnerAdapter(labels: Int) =
        ArrayAdapter.createFromResource(this, labels, android.R.layout.simple_spinner_item)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }

    private fun setUpThemeSwitch() {
        val group = findViewById<RadioGroup>(R.id.theme_group)
        val modes = mapOf(
            R.id.theme_system to Settings.THEME_SYSTEM,
            R.id.theme_light to Settings.THEME_LIGHT,
            R.id.theme_dark to Settings.THEME_DARK,
        )
        group.check(modes.entries.first { it.value == settings.themeMode }.key)
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = modes.getValue(checkedId)
            if (mode != settings.themeMode) {
                settings.themeMode = mode
                recreate()
            }
        }
    }

    private fun showLoginStatus() {
        status.text = if (settings.isConfigured) {
            getString(R.string.status_logged_in, settings.username)
        } else {
            getString(R.string.status_logged_out)
        }
    }

    private fun startLogin() {
        val server = Settings.normalizeServerUrl(serverField.text.toString())
        if (!server.startsWith("https://")) {
            Toast.makeText(this, R.string.error_bad_url, Toast.LENGTH_SHORT).show()
            return
        }
        serverField.setText(server)
        status.setText(R.string.status_waiting)
        polling = true

        Thread {
            try {
                val session = LoginFlow.start(server)
                runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl))) }

                val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                while (polling && System.currentTimeMillis() < deadline) {
                    val result = LoginFlow.poll(session)
                    if (result != null) {
                        settings.serverUrl = result.server.trimEnd('/')
                        settings.username = result.username
                        settings.appPassword = result.appPassword
                        runOnUiThread { showLoginStatus() }
                        return@Thread
                    }
                    Thread.sleep(2000)
                }
                runOnUiThread { showLoginStatus() }
            } catch (e: Exception) {
                runOnUiThread { status.text = getString(R.string.status_failed, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    private fun renderFolders() {
        foldersContainer.removeAllViews()
        for (path in folders) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(this).apply { text = path },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Button(this).apply {
                setText(R.string.button_remove)
                setOnClickListener {
                    folders.remove(path)
                    renderFolders()
                }
            })
            foldersContainer.addView(row)
        }
    }

    private fun addFolder() {
        if (!settings.isConfigured) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        FolderPicker(this, NextcloudClient(settings), "/") {
            if (it !in folders) folders.add(it)
            renderFolders()
        }.show()
    }

    private fun showListStats() {
        val stats = PhotoState.listStats(this)
        listStats.text = if (stats == null) {
            getString(R.string.stats_none)
        } else {
            getString(
                R.string.stats_format,
                stats.count,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(stats.timestamp)),
                formatDuration(stats.durationMs),
            )
        }
    }

    private fun formatDuration(ms: Long) =
        if (ms < 1000) "$ms ms" else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

    /** Rescans the saved folders now, so changes on the server show up without waiting for the schedule. */
    private fun refreshPhotoList() {
        if (!settings.isConfigured) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        val app = applicationContext
        val savedFolders = settings.folders
        refreshNow.isEnabled = false
        listStats.setText(R.string.stats_scanning)
        Thread {
            try {
                PhotoState.refreshList(app, NextcloudClient(settings), savedFolders)
                runOnUiThread { showListStats() }
            } catch (e: Exception) {
                runOnUiThread {
                    showListStats()
                    Toast.makeText(
                        this, getString(R.string.stats_refresh_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                runOnUiThread { refreshNow.isEnabled = true }
            }
        }.start()
    }

    private fun save() {
        if (!settings.isConfigured) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.error_no_folder, Toast.LENGTH_SHORT).show()
            return
        }
        val foldersChanged = folders != settings.folders
        settings.folders = folders.toList()
        settings.intervalMinutes = Settings.INTERVALS_MINUTES[intervalSpinner.selectedItemPosition]
        settings.listRefreshHours = Settings.LIST_REFRESH_HOURS[listRefreshSpinner.selectedItemPosition]

        if (foldersChanged) PhotoState.reset(this)
        Scheduler.apply(this)
        Scheduler.enqueue(this, PhotoWorker.ACTION_NEXT)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()

        if (configureWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetUpdater.update(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId))
            finish()
        }
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
