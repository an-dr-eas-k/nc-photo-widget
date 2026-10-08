package io.github.an_dr_eas_k.ncphotowidget

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import java.io.IOException

/** Keeps the dialog the same size whatever the number of folders. */
private class FixedHeightListView(activity: Activity) : ListView(activity) {
    private val heightPx = minOf(
        (360 * activity.resources.displayMetrics.density).toInt(),
        activity.resources.displayMetrics.heightPixels / 2,
    )

    init {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
    }
}

/** Dialog that browses the Nextcloud folder tree one level at a time. */
class FolderPicker(
    private val activity: Activity,
    private val client: NextcloudClient,
    startPath: String,
    private val onPick: (String) -> Unit,
) {
    private var path = startPath
    private var folders = emptyList<String>()
    private val labels = ArrayList<String>()
    private val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
    private val listView = FixedHeightListView(activity)
    private lateinit var dialog: AlertDialog
    private var generation = 0

    private val hasParent get() = path != "/"

    fun show() {
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> onItemClick(position) }
        dialog = AlertDialog.Builder(activity)
            .setView(listView)
            .setPositiveButton(R.string.folder_select) { _, _ -> onPick(path) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        load()
    }

    private fun onItemClick(position: Int) {
        val offset = if (hasParent) 1 else 0
        path = if (hasParent && position == 0) {
            path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        } else {
            val name = folders.getOrNull(position - offset) ?: return
            if (hasParent) "$path/$name" else "/$name"
        }
        load()
    }

    private fun load() {
        val requested = path
        val current = ++generation
        dialog.setTitle(requested)
        folders = emptyList()
        render()

        Thread {
            try {
                val names = client.listSubfolders(requested)
                activity.runOnUiThread {
                    if (current == generation && dialog.isShowing) {
                        folders = names
                        render()
                    }
                }
            } catch (e: IOException) {
                activity.runOnUiThread {
                    if (current != generation || !dialog.isShowing) return@runOnUiThread
                    if (e is HttpException && e.code == 404 && requested != "/") {
                        path = "/"
                        load()
                    } else {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.error_list_failed, e.message ?: e.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun render() {
        labels.clear()
        if (hasParent) labels.add(activity.getString(R.string.folder_up))
        labels.addAll(folders)
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
    }
}
