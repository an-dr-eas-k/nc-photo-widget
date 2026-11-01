package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
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
 * Chooses the Nextcloud tags to filter by. Each tag cycles through three states: off; half, one of
 * the alternatives of which a photo needs at least one; full, a tag every photo must have. No tag
 * selected means no tag filter. The choice goes back to the caller and only takes effect once the
 * settings are saved.
 */
class TagPickerActivity : AppCompatActivity() {
    private lateinit var client: NextcloudClient
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: TextView
    private lateinit var error: TextView
    private lateinit var retry: View
    private lateinit var summary: TextView
    private val selected = linkedMapOf<Long, Tag>()
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

        val lines = savedInstanceState?.getStringArray(EXTRA_TAGS) ?: intent.getStringArrayExtra(EXTRA_TAGS) ?: emptyArray()
        lines.mapNotNull { Tag.decode(it) }.forEach { selected[it.id] = it }
        showSelection()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(EXTRA_TAGS, encodedSelection())
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

    /** Off, then half (an alternative), then full (required), then off again. */
    private fun cycle(tag: Tag) {
        val current = selected[tag.id]
        when {
            current == null -> selected[tag.id] = tag.copy(required = false)
            !current.required -> selected[tag.id] = tag.copy(required = true)
            else -> selected.remove(tag.id)
        }
        showSelection()
    }

    private fun showSelection() {
        summary.text = if (selected.isEmpty()) getString(R.string.tags_none_selected) else describeTags(this, selected.values)
    }

    private fun encodedSelection() = selected.values.map { it.encode() }.toTypedArray()

    private fun finishWithChoice() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TAGS, encodedSelection()))
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
            holder.check.checkedState = when (selected[tag.id]?.required) {
                null -> MaterialCheckBox.STATE_UNCHECKED
                false -> MaterialCheckBox.STATE_INDETERMINATE
                true -> MaterialCheckBox.STATE_CHECKED
            }
            holder.itemView.setOnClickListener {
                cycle(tag)
                notifyItemChanged(holder.adapterPosition)
            }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val check: MaterialCheckBox = view.findViewById(R.id.check)
        }
    }

    companion object {
        const val EXTRA_TAGS = "tags"

        /** "Any of: a, b; all of: c" for the chosen tags. */
        fun describeTags(context: Context, tags: Collection<Tag>): String = listOfNotNull(
            tags.filter { !it.required }.takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.tags_any_of, it.joinToString(", ") { t -> t.name }) },
            tags.filter { it.required }.takeIf { it.isNotEmpty() }
                ?.let { context.getString(R.string.tags_all_of, it.joinToString(", ") { t -> t.name }) },
        ).joinToString("\n")
    }
}
