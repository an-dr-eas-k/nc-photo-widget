package io.github.an_dr_eas_k.ncphotowidget

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Chooses the Nextcloud tags a photo must all have. No tag selected means no tag filter. The choice
 * goes back to the caller and only takes effect once the settings are saved.
 */
class TagPickerActivity : AppCompatActivity() {
    private lateinit var client: NextcloudClient
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: TextView
    private lateinit var error: TextView
    private lateinit var retry: View
    private lateinit var summary: TextView
    private val selected = linkedMapOf<Long, String>()
    private val adapter = TagAdapter()
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        client = NextcloudClient(Settings(this))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.tags_title)
            setNavigationOnClickListener { finish() }
        }
        progress = findViewById(R.id.progress)
        empty = findViewById<TextView>(R.id.empty).also { it.setText(R.string.error_no_tags) }
        error = findViewById(R.id.error)
        retry = findViewById(R.id.retryButton)
        summary = findViewById(R.id.selectionSummary)

        findViewById<RecyclerView>(R.id.folders).apply {
            layoutManager = LinearLayoutManager(this@TagPickerActivity)
            adapter = this@TagPickerActivity.adapter
        }
        findViewById<MaterialButton>(R.id.doneButton).setOnClickListener { finishWithChoice() }
        retry.setOnClickListener { load() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        val ids = savedInstanceState?.getLongArray(EXTRA_IDS) ?: intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
        val names = savedInstanceState?.getStringArray(EXTRA_NAMES) ?: intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        ids.forEachIndexed { i, id -> selected[id] = names.getOrElse(i) { "" } }
        showSelection()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(EXTRA_IDS, selected.keys.toLongArray())
        outState.putStringArray(EXTRA_NAMES, selected.values.toTypedArray())
    }

    private fun load() {
        val current = ++generation
        progress.visibility = View.VISIBLE
        error.visibility = View.GONE
        retry.visibility = View.GONE
        empty.visibility = View.GONE
        adapter.submit(emptyList())

        inBackground({ client.listTags() }) { result ->
            if (current != generation) return@inBackground
            progress.visibility = View.GONE
            result.onSuccess { tags ->
                adapter.submit(tags)
                empty.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                error.text = getString(R.string.error_tags_failed, it.describe())
                error.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
            }
        }
    }

    private fun toggle(tag: Tag, checked: Boolean) {
        if (checked) selected[tag.id] = tag.name else selected.remove(tag.id)
        showSelection()
    }

    private fun showSelection() {
        summary.text = if (selected.isEmpty()) {
            getString(R.string.tags_none_selected)
        } else {
            getString(R.string.tags_selected, selected.values.joinToString(", "))
        }
    }

    private fun finishWithChoice() {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_IDS, selected.keys.toLongArray())
                .putExtra(EXTRA_NAMES, selected.values.toTypedArray()),
        )
        finish()
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagAdapter.Holder>() {
        private var tags: List<Tag> = emptyList()

        fun submit(items: List<Tag>) {
            tags = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = tags.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_tag, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tag = tags[position]
            holder.name.text = tag.name
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = tag.id in selected
            holder.check.setOnCheckedChangeListener { _, checked -> toggle(tag, checked) }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val check: MaterialCheckBox = view.findViewById(R.id.check)
        }
    }

    companion object {
        const val EXTRA_IDS = "tag_ids"
        const val EXTRA_NAMES = "tag_names"
    }
}
