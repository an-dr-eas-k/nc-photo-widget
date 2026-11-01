package io.github.an_dr_eas_k.ncphotowidget

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Browses the Nextcloud folder tree and chooses the photo folders. Tapping a folder opens it and its
 * checkbox selects it; a folder inside a selected one is already covered. The choice goes back to the
 * caller and only takes effect once the settings are saved.
 */
class FolderPickerActivity : AppCompatActivity() {
    private lateinit var client: NextcloudClient
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: View
    private lateinit var error: TextView
    private lateinit var retry: View
    private lateinit var summary: TextView
    private lateinit var done: MaterialButton
    private val selected = sortedSetOf<String>()
    private val adapter = FolderAdapter()
    private var path = "/"
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        client = NextcloudClient(Settings(this))

        toolbar = findViewById(R.id.toolbar)
        toolbar.setTitle(R.string.folders_title)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        error = findViewById(R.id.error)
        retry = findViewById(R.id.retryButton)
        summary = findViewById(R.id.selectionSummary)
        done = findViewById(R.id.doneButton)

        toolbar.setNavigationOnClickListener { goBack() }
        findViewById<RecyclerView>(R.id.folders).apply {
            layoutManager = LinearLayoutManager(this@FolderPickerActivity)
            adapter = this@FolderPickerActivity.adapter
        }
        done.setOnClickListener { finishWithChoice() }
        retry.setOnClickListener { load() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        if (savedInstanceState != null) {
            path = savedInstanceState.getString(STATE_PATH, "/")
            selected += savedInstanceState.getStringArrayList(STATE_SELECTED).orEmpty()
        } else {
            selected += intent.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()
            path = commonParent(selected)
        }
        showSelection()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
    }

    private fun load() {
        val requested = path
        val current = ++generation
        toolbar.subtitle = requested
        progress.visibility = View.VISIBLE
        error.visibility = View.GONE
        retry.visibility = View.GONE
        empty.visibility = View.GONE
        adapter.submit(emptyList())

        inBackground({ client.listSubfolders(requested) }) { result ->
            if (current != generation) return@inBackground
            progress.visibility = View.GONE
            result.onSuccess { names ->
                adapter.submit(names.map { child(requested, it) })
                empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure { e ->
                if (e is HttpException && e.code == 404 && requested != "/") {
                    path = "/"
                    load()
                } else {
                    error.text = getString(R.string.error_list_failed, e.describe())
                    error.visibility = View.VISIBLE
                    retry.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun open(folder: String) {
        path = folder
        load()
    }

    private fun goBack() {
        if (path == "/") finish() else open(path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" })
    }

    private fun toggle(folder: String, checked: Boolean) {
        if (checked) {
            // Selecting a folder covers whatever was selected inside it.
            selected.removeAll { it.startsWith("$folder/") }
            selected += folder
        } else {
            selected -= folder
        }
        showSelection()
        adapter.notifyDataSetChanged()
    }

    private fun showSelection() {
        summary.text = if (selected.isEmpty()) {
            getString(R.string.folders_none_selected)
        } else {
            getString(R.string.folders_selected, selected.joinToString(", "))
        }
    }

    private fun finishWithChoice() {
        if (selected.isEmpty()) return Toast.makeText(this, R.string.error_no_folder, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)))
        finish()
    }

    /** The deepest folder that contains all of [folders], so every chosen folder is visible or one level below. */
    private fun commonParent(folders: Collection<String>): String {
        val parents = folders.map { it.trim('/').split('/').dropLast(1) }
        val first = parents.firstOrNull() ?: return "/"
        val shared = first.indices.takeWhile { i -> parents.all { it.getOrNull(i) == first[i] } }.size
        return if (shared == 0) "/" else "/" + first.take(shared).joinToString("/")
    }

    private fun child(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

    private fun coveredBySelection(folder: String) = selected.any { folder.startsWith("$it/") }

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.Holder>() {
        private var folders: List<String> = emptyList()

        fun submit(items: List<String>) {
            folders = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = folders.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val folder = folders[position]
            val covered = coveredBySelection(folder)
            val name = folder.substringAfterLast('/')
            holder.name.text = name
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = covered || folder in selected
            holder.check.isEnabled = !covered
            holder.check.contentDescription = getString(R.string.folders_select, name)
            holder.check.setOnCheckedChangeListener { _, checked -> toggle(folder, checked) }
            holder.itemView.setOnClickListener { open(folder) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val check: MaterialCheckBox = view.findViewById(R.id.check)
        }
    }

    companion object {
        const val EXTRA_SELECTED = "selected"
        private const val STATE_PATH = "path"
        private const val STATE_SELECTED = "selected_state"
    }
}
