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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var config: WidgetConfig
    private lateinit var state: PhotoState
    private var server = ""
    private var folders = listOf<String>()
    private var tags = listOf<Tag>()
    private var intervalIndex = 0
    private var shape = WidgetShape.ROUNDED
    private var filter = PhotoFilter.NONE
    private var timeFilter = TimeFilter()

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
    private var pollInFlight = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollTask = Runnable { pollLogin() }
    private var scanStartedAt = 0L
    private val scanTicker = Runnable { tickScan() }

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var launchedForConfigure = false

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
        val lines = data.getStringArrayExtra(TagPickerActivity.EXTRA_TAGS) ?: return@registerForActivityResult
        tags = lines.mapNotNull { Tag.decode(it) }
        renderRows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = Settings(this)
        AppCompatDelegate.setDefaultNightMode(nightMode(settings.themeMode))
        super.onCreate(savedInstanceState)
        // Transparent system bars, so the page colour shows behind them.
        enableEdgeToEdge()
        // The settings belong to one widget; without one there is nothing to edit.
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // Launched from the widget's setup/reconfigure button; must answer with the widget id.
        launchedForConfigure = intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
        if (launchedForConfigure) {
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        WidgetConfig.migrateLegacy(this)
        config = WidgetConfig(this, widgetId)
        state = PhotoState(this, widgetId)
        // A force stop cancels the widgets' tap handlers; opening the settings restores them.
        WidgetUpdater.updateAll(this)
        Scheduler.apply(this)
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
        folders = config.folders
        tags = config.tags
        intervalIndex = Settings.INTERVALS_MINUTES.indexOf(config.intervalMinutes).coerceAtLeast(0)
        shape = config.shape
        filter = config.filter
        timeFilter = config.timeFilter
        // Unsaved edits survive the screen being recreated, e.g. by a theme change.
        savedInstanceState?.let { state ->
            state.getStringArrayList(STATE_FOLDERS)?.let { folders = it }
            state.getStringArray(STATE_TAGS)?.let { lines -> tags = lines.mapNotNull { Tag.decode(it) } }
            intervalIndex = state.getInt(STATE_INTERVAL, intervalIndex)
            server = state.getString(STATE_SERVER, server)
            shape = WidgetShape.entries.getOrElse(state.getInt(STATE_SHAPE, shape.ordinal)) { shape }
            filter = PhotoFilter.entries.getOrElse(state.getInt(STATE_FILTER, filter.ordinal)) { filter }
            timeFilter = TimeFilter(state.getInt(STATE_DAYS, timeFilter.days), state.getBoolean(STATE_REPEAT, timeFilter.repeatYears))
        }
        accountDetail = loginStatusText()
        workInfos = WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(Scheduler.scanName(widgetId))

        setUpRow(R.id.rowStyle, R.string.label_widget_style) { chooseShape() }
        setUpRow(R.id.rowFilter, R.string.label_photo_filter) { chooseFilter() }
        setUpRow(R.id.rowFolders, R.string.row_folders) { chooseFolders() }
        setUpRow(R.id.rowTags, R.string.row_tags) { chooseTags() }
        setUpRow(R.id.rowTimeSpan, R.string.row_time_span) { chooseDays() }
        setUpRow(R.id.rowInterval, R.string.label_interval) { chooseInterval() }
        setUpRow(R.id.rowAccount, R.string.row_account) { showAccountDialog() }
        setUpRow(R.id.rowAppearance, R.string.label_appearance) { chooseTheme() }
        renderRows()

        refreshNow.setOnClickListener { refreshPhotoList() }
        saveButton.setOnClickListener { save() }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_FOLDERS, ArrayList(folders))
        outState.putStringArray(STATE_TAGS, tags.map { it.encode() }.toTypedArray())
        outState.putInt(STATE_INTERVAL, intervalIndex)
        outState.putString(STATE_SERVER, server)
        outState.putInt(STATE_SHAPE, shape.ordinal)
        outState.putInt(STATE_FILTER, filter.ordinal)
        outState.putInt(STATE_DAYS, timeFilter.days)
        outState.putBoolean(STATE_REPEAT, timeFilter.repeatYears)
    }

    override fun onResume() {
        super.onResume()
        if (!::workInfos.isInitialized) return
        // Scans run in the background worker; follow them while this screen is visible.
        workInfos.observeForever(workObserver)
        if (settings.pendingLogin != null) pollLogin()
    }

    override fun onPause() {
        if (::workInfos.isInitialized) workInfos.removeObserver(workObserver)
        handler.removeCallbacks(pollTask)
        stopScanTicker()
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
        setDetail(R.id.rowStyle, resources.getStringArray(R.array.shape_labels)[shape.ordinal])
        findViewById<ShapePreviewView>(R.id.stylePreview).shape = shape
        setDetail(R.id.rowFilter, resources.getStringArray(R.array.filter_labels)[filter.ordinal])
        findViewById<FilterPreviewView>(R.id.filterPreview).filter = filter
        setDetail(R.id.rowFolders, if (folders.isEmpty()) getString(R.string.folders_none) else folders.joinToString(", "))
        setDetail(R.id.rowTags, if (tags.isEmpty()) getString(R.string.tags_none) else TagPickerActivity.describeTags(this, tags).replace("\n", "; "))
        setDetail(
            R.id.rowTimeSpan,
            when {
                !timeFilter.active -> getString(R.string.time_off)
                timeFilter.repeatYears && timeFilter.canRepeat -> getString(R.string.time_last_days_repeat, timeFilter.days)
                else -> getString(R.string.time_last_days, timeFilter.days)
            },
        )
        setDetail(R.id.rowInterval, resources.getStringArray(R.array.interval_labels)[intervalIndex])
        findViewById<View>(R.id.rowInterval).findViewById<TextView>(R.id.value).text =
            resources.getStringArray(R.array.interval_short_labels)[intervalIndex]
        setDetail(R.id.rowAccount, accountDetail)
        setDetail(R.id.rowAppearance, resources.getStringArray(R.array.theme_labels)[settings.themeMode])
        statusLabel.setText(if (settings.isConfigured) R.string.home_connected else R.string.home_not_connected)
    }

    private fun setAccountDetail(text: String) {
        accountDetail = text
        renderRows()
    }

    private fun loginStatusText() = if (settings.pendingLogin != null) {
        getString(R.string.status_waiting)
    } else if (settings.isConfigured) {
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

    private fun chooseShape() = chooseOne(R.string.label_widget_style, R.array.shape_labels, shape.ordinal) {
        shape = WidgetShape.entries[it]
    }

    private fun chooseDays() {
        val view = layoutInflater.inflate(R.layout.dialog_days, null)
        val field = view.findViewById<TextInputEditText>(R.id.days)
        val repeat = view.findViewById<MaterialCheckBox>(R.id.repeat)
        val repeatDetail = view.findViewById<TextView>(R.id.repeatDetail)
        field.setText(timeFilter.days.toString())
        repeat.isChecked = timeFilter.repeatYears
        // Repeating only makes sense for spans shorter than a year, so the box follows what is typed.
        fun showRepeat() {
            val canRepeat = TimeFilter(field.text.toString().toIntOrNull() ?: 0).canRepeat
            repeat.isEnabled = canRepeat
            repeatDetail.setText(if (canRepeat) R.string.time_repeat_detail else R.string.time_repeat_unavailable)
        }
        showRepeat()
        field.doAfterTextChanged { showRepeat() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.row_time_span)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // A disabled box keeps its earlier value, so a typo does not lose the choice.
                timeFilter = TimeFilter(
                    field.text.toString().toIntOrNull() ?: 0,
                    if (repeat.isEnabled) repeat.isChecked else timeFilter.repeatYears,
                )
                renderRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseFilter() = chooseOne(R.string.label_photo_filter, R.array.filter_labels, filter.ordinal, R.string.filter_hint) {
        filter = PhotoFilter.entries[it]
    }

    private fun chooseTheme() = chooseOne(R.string.label_appearance, R.array.theme_labels, settings.themeMode) { mode ->
        if (mode != settings.themeMode) {
            settings.themeMode = mode
            renderRows()
            // Recreates the activity when the night mode actually changes.
            AppCompatDelegate.setDefaultNightMode(nightMode(mode))
        }
    }

    /** A single choice dialog; a [hint], when given, is shown under the title. */
    private fun chooseOne(title: Int, labels: Int, selected: Int, hint: Int? = null, onChosen: (Int) -> Unit) {
        val builder = MaterialAlertDialogBuilder(this)
        if (hint == null) {
            builder.setTitle(title)
        } else {
            val header = layoutInflater.inflate(R.layout.dialog_title_hint, null)
            header.findViewById<TextView>(R.id.dialogTitle).setText(title)
            header.findViewById<TextView>(R.id.dialogHint).setText(hint)
            builder.setCustomTitle(header)
        }
        builder
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
                settings.pendingLogin =
                    Settings.PendingLogin(session.pollUrl, session.token, normalized, System.currentTimeMillis() + LOGIN_TIMEOUT_MS)
                // Also polls while this screen is not in front, e.g. after the browser was closed to the home screen.
                LoginPollWorker.enqueue(this, delaySeconds = LOGIN_WORKER_DELAY_SECONDS)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.loginUrl)))
            }.onFailure { setAccountDetail(getString(R.string.status_failed, it.describe())) }
        }
    }

    private fun pollLogin() {
        val pending = settings.pendingLogin
        // Gone when it ran out, or when the background job has finished the login already.
        if (pending == null || System.currentTimeMillis() > pending.deadline) {
            settings.pendingLogin = null
            setAccountDetail(loginStatusText())
            return
        }
        val session = LoginFlow.Session("", pending.pollUrl, pending.token)
        if (pollInFlight) return
        pollInFlight = true

        inBackground({ LoginFlow.poll(session) }) { result ->
            pollInFlight = false
            val login = result.getOrNull()
            if (login != null) {
                settings.completeLogin(pending, login)
                Scheduler.apply(this)
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
                .putExtra(TagPickerActivity.EXTRA_TAGS, tags.map { it.encode() }.toTypedArray()),
        )
    }

    private fun showListStats() {
        val stats = state.listStats()
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
            it.state == WorkInfo.State.RUNNING && it.progress.keyValueMap.containsKey(PhotoWorker.PROGRESS_START)
        }
        val refreshPending = infos.any {
            !it.state.isFinished && (PhotoWorker.ACTION_REFRESH in it.tags || PhotoWorker.ACTION_UPDATE in it.tags)
        }
        when {
            scanning != null -> {
                statusCount.text = scanning.progress.getInt(PhotoWorker.PROGRESS_PHOTOS, 0).toString()
                scanStartedAt = scanning.progress.getLong(PhotoWorker.PROGRESS_START, 0L)
                tickScan()
            }
            refreshPending -> statusSync.setText(R.string.stats_scanning)
            else -> showListStats()
        }
        if (scanning == null) stopScanTicker()
    }

    /** Shows the running scan's duration and again each second, until the scan is over. */
    private fun tickScan() {
        handler.removeCallbacks(scanTicker)
        if (scanStartedAt == 0L) return
        val seconds = ((System.currentTimeMillis() - scanStartedAt) / 1000).coerceAtLeast(0)
        statusSync.text = getString(R.string.stats_scanning_duration, "%d:%02d".format(seconds / 60, seconds % 60))
        handler.postDelayed(scanTicker, 1000)
    }

    private fun stopScanTicker() {
        scanStartedAt = 0L
        handler.removeCallbacks(scanTicker)
    }

    private fun formatDuration(ms: Long) =
        if (ms < 1000) "$ms ms" else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Saves any pending changes and rescans the folders now, so the tile shows fresh numbers. */
    private fun refreshPhotoList() {
        apply(rescan = true)
    }

    private fun save() {
        if (!apply()) return
        toast(R.string.saved)

        if (launchedForConfigure) {
            WidgetUpdater.update(this, widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    /**
     * Stores the pending settings; false (with a message) if they are not complete.
     * The photo list is rescanned only on request, when the folders or tags changed, or if it was never scanned.
     * A changed time span is applied to the list as it is.
     */
    private fun apply(rescan: Boolean = false): Boolean {
        if (!settings.isConfigured) return false.also { toast(R.string.error_not_logged_in) }
        if (folders.isEmpty()) return false.also { toast(R.string.error_no_folder) }
        val sourceChanged = folders != config.folders || tags != config.tags
        val timeChanged = timeFilter != config.timeFilter
        config.folders = folders
        config.tags = tags
        config.intervalMinutes = Settings.INTERVALS_MINUTES[intervalIndex]
        config.timeFilter = timeFilter
        if (shape != config.shape || filter != config.filter) {
            config.shape = shape
            config.filter = filter
            WidgetUpdater.update(this, widgetId)
        }

        if (sourceChanged) state.reset() else if (timeChanged) state.resetHistory()
        Scheduler.apply(this)
        if (rescan || sourceChanged || !state.hasScanned()) {
            Scheduler.enqueue(this, widgetId, PhotoWorker.ACTION_REFRESH)
        } else if (timeChanged) {
            // The list stays; the widget moves on to a photo of the new time span.
            Scheduler.enqueue(this, widgetId, PhotoWorker.ACTION_NEXT)
        }
        return true
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 10 * 60 * 1000L
        const val POLL_INTERVAL_MS = 2000L
        const val LOGIN_WORKER_DELAY_SECONDS = 10L
        const val STATE_FOLDERS = "folders"
        const val STATE_TAGS = "tags"
        const val STATE_INTERVAL = "interval"
        const val STATE_SERVER = "server"
        const val STATE_SHAPE = "shape"
        const val STATE_FILTER = "filter"
        const val STATE_DAYS = "days"
        const val STATE_REPEAT = "repeat"
    }
}

/** Runs [work] on a background thread and hands its outcome to [done] on the UI thread. */
fun <T> Activity.inBackground(work: () -> T, done: (Result<T>) -> Unit) =
    Thread { runCatching(work).let { runOnUiThread { done(it) } } }.start()

fun Throwable.describe(): String = message ?: javaClass.simpleName
