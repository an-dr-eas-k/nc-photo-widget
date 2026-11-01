package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private var server = ""
    private var folders = listOf<String>()
    private var tags = listOf<Tag>()
    private var intervalIndex = 0
    private var listRefreshIndex = 0

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusCount: TextView
    private lateinit var statusItems: TextView
    private lateinit var statusSync: TextView
    private lateinit var saveButton: Button
    private lateinit var refreshNow: Button
    private var accountDetail = ""

    // Login in progress: polled only while this screen is visible, as the system may block the
    // network of an app that sits in the background behind the browser.
    private var pendingSession: LoginFlow.Session? = null
    private var pendingServer = ""
    private var pendingDeadline = 0L
    private var pollInFlight = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollTask = Runnable { pollLogin() }

    private var configureWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var workInfos: LiveData<List<WorkInfo>>
    private val workObserver = Observer<List<WorkInfo>> { renderScanState(it) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(FolderPickerActivity.EXTRA_SELECTED)?.takeIf { result.resultCode == RESULT_OK }?.let {
            folders = it.toList()
            renderRows()
        }
    }

    private val tagPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data?.takeIf { result.resultCode == RESULT_OK } ?: return@registerForActivityResult
        val ids = data.getLongArrayExtra(TagPickerActivity.EXTRA_IDS) ?: return@registerForActivityResult
        val names = data.getStringArrayExtra(TagPickerActivity.EXTRA_NAMES).orEmpty()
        tags = ids.mapIndexed { i, id -> Tag(id, names.getOrElse(i) { "" }) }
        renderRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        // A force stop cancels the widget's tap handlers; opening the settings restores them.
        WidgetUpdater.update(this)
        Scheduler.apply(this)
        // Launched from the widget's setup/reconfigure button; must answer with the widget id.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            configureWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId))
        }
        AppCompatDelegate.setDefaultNightMode(nightMode(settings.themeMode))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusCard = findViewById(R.id.statusCard)
        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        statusCount = findViewById(R.id.statusCount)
        statusItems = findViewById(R.id.statusItems)
        statusSync = findViewById(R.id.statusSync)
        saveButton = findViewById(R.id.save)
        refreshNow = findViewById(R.id.refresh_now)
        styleStatusCard()

        server = settings.serverUrl
        folders = settings.folders
        tags = settings.tags
        intervalIndex = Settings.INTERVALS_MINUTES.indexOf(settings.intervalMinutes).coerceAtLeast(0)
        listRefreshIndex = Settings.LIST_REFRESH_HOURS.indexOf(settings.listRefreshHours).coerceAtLeast(0)
        // Unsaved edits survive the screen being recreated, e.g. by a theme change.
        savedInstanceState?.let { state ->
            state.getStringArrayList(STATE_FOLDERS)?.let { folders = it }
            state.getLongArray(STATE_TAG_IDS)?.let { ids ->
                val names = state.getStringArray(STATE_TAG_NAMES).orEmpty()
                tags = ids.mapIndexed { i, id -> Tag(id, names.getOrElse(i) { "" }) }
            }
            intervalIndex = state.getInt(STATE_INTERVAL, intervalIndex)
            listRefreshIndex = state.getInt(STATE_LIST_REFRESH, listRefreshIndex)
            server = state.getString(STATE_SERVER, server)
        }
        accountDetail = loginStatusText()
        workInfos = WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(Scheduler.SCAN)

        setUpRow(R.id.rowFolders, R.string.row_folders) { chooseFolders() }
        setUpRow(R.id.rowTags, R.string.row_tags) { chooseTags() }
        setUpRow(R.id.rowInterval, R.string.label_interval) { chooseInterval() }
        setUpRow(R.id.rowListRefresh, R.string.label_list_refresh) { chooseListRefresh() }
        setUpRow(R.id.rowAccount, R.string.row_account) { showAccountDialog() }
        setUpRow(R.id.rowAppearance, R.string.label_appearance) { chooseTheme() }
        renderRows()

        refreshNow.setOnClickListener { refreshPhotoList() }
        saveButton.setOnClickListener { save() }

        savedInstanceState?.getString(STATE_LOGIN_TOKEN)?.let { token ->
            pendingSession = LoginFlow.Session("", savedInstanceState.getString(STATE_LOGIN_POLL_URL, ""), token)
            pendingServer = savedInstanceState.getString(STATE_LOGIN_SERVER, "")
            pendingDeadline = savedInstanceState.getLong(STATE_LOGIN_DEADLINE)
            setAccountDetail(getString(R.string.status_waiting))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_FOLDERS, ArrayList(folders))
        outState.putLongArray(STATE_TAG_IDS, tags.map { it.id }.toLongArray())
        outState.putStringArray(STATE_TAG_NAMES, tags.map { it.name }.toTypedArray())
        outState.putInt(STATE_INTERVAL, intervalIndex)
        outState.putInt(STATE_LIST_REFRESH, listRefreshIndex)
        outState.putString(STATE_SERVER, server)
        pendingSession?.let {
            outState.putString(STATE_LOGIN_TOKEN, it.token)
            outState.putString(STATE_LOGIN_POLL_URL, it.pollUrl)
            outState.putString(STATE_LOGIN_SERVER, pendingServer)
            outState.putLong(STATE_LOGIN_DEADLINE, pendingDeadline)
        }
    }

    override fun onResume() {
        super.onResume()
        // Scans run in the background worker; follow them while this screen is visible.
        workInfos.observeForever(workObserver)
        if (pendingSession != null) pollLogin()
    }

    override fun onPause() {
        workInfos.removeObserver(workObserver)
        handler.removeCallbacks(pollTask)
        super.onPause()
    }

    private fun nightMode(mode: Int) = when (mode) {
        Settings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        Settings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun color(attribute: Int): Int = MaterialColors.getColor(statusCard, attribute)

    /** Primary-coloured in light mode and a primary container in dark mode, like the reference app. */
    private fun styleStatusCard() {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val background = color(if (dark) MaterialR.attr.colorPrimaryContainer else AppCompatR.attr.colorPrimary)
        val foreground = color(if (dark) MaterialR.attr.colorOnPrimaryContainer else MaterialR.attr.colorOnPrimary)
        statusCard.setCardBackgroundColor(background)
        for (text in listOf(statusLabel, statusCount, statusItems, statusSync)) text.setTextColor(foreground)
        statusDot.backgroundTintList =
            ColorStateList.valueOf(color(if (dark) AppCompatR.attr.colorPrimary else MaterialR.attr.colorPrimaryInverse))
        saveButton.backgroundTintList = ColorStateList.valueOf(foreground)
        saveButton.setTextColor(background)
        refreshNow.setTextColor(foreground)
        (refreshNow as MaterialButton).strokeColor = ColorStateList.valueOf(foreground)
    }

    private fun setUpRow(id: Int, title: Int, onClick: () -> Unit) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.title).setText(title)
        row.setOnClickListener { onClick() }
    }

    private fun setDetail(id: Int, detail: String) {
        findViewById<View>(id).findViewById<TextView>(R.id.detail).text = detail
    }

    private fun renderRows() {
        setDetail(R.id.rowFolders, if (folders.isEmpty()) getString(R.string.folders_none) else folders.joinToString(", "))
        setDetail(R.id.rowTags, if (tags.isEmpty()) getString(R.string.tags_none) else tags.joinToString(", ") { it.name })
        setDetail(R.id.rowInterval, resources.getStringArray(R.array.interval_labels)[intervalIndex])
        setDetail(R.id.rowListRefresh, resources.getStringArray(R.array.list_refresh_labels)[listRefreshIndex])
        setDetail(R.id.rowAccount, accountDetail)
        setDetail(R.id.rowAppearance, resources.getStringArray(R.array.theme_labels)[settings.themeMode])
        statusLabel.setText(if (settings.isConfigured) R.string.home_connected else R.string.home_not_connected)
    }

    private fun setAccountDetail(text: String) {
        accountDetail = text
        renderRows()
    }

    private fun loginStatusText() = if (settings.isConfigured) {
        getString(R.string.status_logged_in, settings.username)
    } else {
        getString(R.string.status_logged_out)
    }

    private fun chooseFolders() {
        if (!settings.isConfigured) return toast(R.string.error_not_logged_in)
        folderPicker.launch(
            Intent(this, FolderPickerActivity::class.java)
                .putStringArrayListExtra(FolderPickerActivity.EXTRA_SELECTED, ArrayList(folders)),
        )
    }

    private fun chooseInterval() = chooseOne(R.string.label_interval, R.array.interval_labels, intervalIndex) {
        intervalIndex = it
    }

    private fun chooseListRefresh() = chooseOne(R.string.label_list_refresh, R.array.list_refresh_labels, listRefreshIndex) {
        listRefreshIndex = it
    }

    private fun chooseTheme() = chooseOne(R.string.label_appearance, R.array.theme_labels, settings.themeMode) { mode ->
        if (mode != settings.themeMode) {
            settings.themeMode = mode
            renderRows()
            // Recreates the activity when the night mode actually changes.
            AppCompatDelegate.setDefaultNightMode(nightMode(mode))
        }
    }

    private fun chooseOne(title: Int, labels: Int, selected: Int, onChosen: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                onChosen(which)
                renderRows()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAccountDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_account, null)
        val field = view.findViewById<TextInputEditText>(R.id.server)
        field.setText(server)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.row_account)
            .setMessage(accountDetail)
            .setView(view)
            .setPositiveButton(R.string.button_login) { _, _ -> startLogin(field.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startLogin(input: String) {
        val normalized = Settings.normalizeServerUrl(input)
        if (!normalized.startsWith("https://")) return toast(R.string.error_bad_url)
        server = normalized
        setAccountDetail(getString(R.string.status_waiting))

        inBackground({ LoginFlow.start(normalized) }) { result ->
            result.onSuccess { session ->
                pendingSession = session
                pendingServer = normalized
                pendingDeadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl)))
            }.onFailure { setAccountDetail(getString(R.string.status_failed, it.describe())) }
        }
    }

    private fun pollLogin() {
        val session = pendingSession ?: return
        if (System.currentTimeMillis() > pendingDeadline) {
            pendingSession = null
            setAccountDetail(loginStatusText())
            return
        }
        if (pollInFlight) return
        pollInFlight = true

        inBackground({ LoginFlow.poll(session) }) { result ->
            pollInFlight = false
            val login = result.getOrNull()
            if (login != null) {
                // Keep the address the user typed: the server-reported one may be an internal name.
                settings.serverUrl = pendingServer
                settings.username = login.username
                settings.appPassword = login.appPassword
                pendingSession = null
                setAccountDetail(loginStatusText())
            } else {
                setAccountDetail(
                    result.exceptionOrNull()?.let { getString(R.string.status_waiting_error, it.describe()) }
                        ?: getString(R.string.status_waiting),
                )
                handler.postDelayed(pollTask, POLL_INTERVAL_MS)
            }
        }
    }

    private fun chooseTags() {
        if (!settings.isConfigured) return toast(R.string.error_not_logged_in)
        tagPicker.launch(
            Intent(this, TagPickerActivity::class.java)
                .putExtra(TagPickerActivity.EXTRA_IDS, tags.map { it.id }.toLongArray())
                .putExtra(TagPickerActivity.EXTRA_NAMES, tags.map { it.name }.toTypedArray()),
        )
    }

    private fun showListStats() {
        val stats = PhotoState.listStats(this)
        statusCount.text = stats?.takeIf { it.timestamp != 0L }?.count?.toString() ?: getString(R.string.home_no_count)
        val text = when {
            stats == null -> getString(R.string.stats_none)
            stats.timestamp == 0L -> ""
            else -> getString(
                R.string.stats_format,
                stats.count,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(stats.timestamp)),
                formatDuration(stats.durationMs),
            )
        }
        val error = stats?.error?.let { getString(R.string.stats_error, it) }
        statusSync.text = listOf(text, error).filter { !it.isNullOrEmpty() }.joinToString("\n")
    }

    private fun renderScanState(infos: List<WorkInfo>) {
        val scanning = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING && it.progress.keyValueMap.containsKey(PhotoWorker.PROGRESS_FOLDERS)
        }
        val refreshPending = infos.any { !it.state.isFinished && PhotoWorker.ACTION_REFRESH in it.tags }
        when {
            scanning != null -> {
                val photos = scanning.progress.getInt(PhotoWorker.PROGRESS_PHOTOS, 0)
                statusCount.text = photos.toString()
                statusSync.text = getString(
                    R.string.stats_scanning_progress,
                    scanning.progress.getInt(PhotoWorker.PROGRESS_FOLDERS, 0),
                    photos,
                )
            }
            refreshPending -> statusSync.setText(R.string.stats_scanning)
            else -> showListStats()
        }
        refreshNow.isEnabled = !refreshPending
    }

    private fun formatDuration(ms: Long) =
        if (ms < 1000) "$ms ms" else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Saves any pending changes and rescans the folders now, so the tile shows fresh numbers. */
    private fun refreshPhotoList() {
        apply()
    }

    private fun save() {
        if (!apply()) return
        toast(R.string.saved)

        if (configureWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetUpdater.update(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configureWidgetId))
            finish()
        }
    }

    /** Stores the pending settings and starts a rescan; false (with a message) if they are not complete. */
    private fun apply(): Boolean {
        if (!settings.isConfigured) return false.also { toast(R.string.error_not_logged_in) }
        if (folders.isEmpty()) return false.also { toast(R.string.error_no_folder) }
        val filterChanged = folders != settings.folders || tags != settings.tags
        settings.folders = folders
        settings.tags = tags
        settings.intervalMinutes = Settings.INTERVALS_MINUTES[intervalIndex]
        settings.listRefreshHours = Settings.LIST_REFRESH_HOURS[listRefreshIndex]

        if (filterChanged) PhotoState.reset(this)
        Scheduler.apply(this)
        Scheduler.enqueue(this, PhotoWorker.ACTION_REFRESH)
        return true
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L
        const val POLL_INTERVAL_MS = 2000L
        const val STATE_LOGIN_TOKEN = "login_token"
        const val STATE_LOGIN_POLL_URL = "login_poll_url"
        const val STATE_LOGIN_SERVER = "login_server"
        const val STATE_LOGIN_DEADLINE = "login_deadline"
        const val STATE_FOLDERS = "folders"
        const val STATE_TAG_IDS = "tag_ids"
        const val STATE_TAG_NAMES = "tag_names"
        const val STATE_INTERVAL = "interval"
        const val STATE_LIST_REFRESH = "list_refresh"
        const val STATE_SERVER = "server"
    }
}

/** Runs [work] on a background thread and hands its outcome to [done] on the UI thread. */
fun <T> Activity.inBackground(work: () -> T, done: (Result<T>) -> Unit) =
    Thread { runCatching(work).let { runOnUiThread { done(it) } } }.start()

fun Throwable.describe(): String = message ?: javaClass.simpleName
