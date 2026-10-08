package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var serverField: EditText
    private lateinit var folderButton: Button
    private var selectedFolder = "/"
    private lateinit var intervalSpinner: Spinner
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
        folderButton = findViewById(R.id.folder)
        intervalSpinner = findViewById(R.id.interval)
        status = findViewById(R.id.status)

        intervalSpinner.adapter = ArrayAdapter.createFromResource(
            this, R.array.interval_labels, android.R.layout.simple_spinner_item,
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        serverField.setText(settings.serverUrl)
        selectedFolder = settings.folder
        folderButton.text = selectedFolder
        folderButton.setOnClickListener { chooseFolder() }
        intervalSpinner.setSelection(Settings.INTERVALS_MINUTES.indexOf(settings.intervalMinutes).coerceAtLeast(0))
        showLoginStatus()

        findViewById<Button>(R.id.login).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
    }

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

    private fun chooseFolder() {
        if (!settings.isConfigured) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        FolderPicker(this, NextcloudClient(settings), selectedFolder) {
            selectedFolder = it
            folderButton.text = it
        }.show()
    }

    private fun save() {
        if (!settings.isConfigured) {
            Toast.makeText(this, R.string.error_not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        settings.folder = selectedFolder
        settings.intervalMinutes = Settings.INTERVALS_MINUTES[intervalSpinner.selectedItemPosition]

        PhotoState.reset(this)
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
