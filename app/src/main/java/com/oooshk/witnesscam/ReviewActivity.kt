package com.oooshk.witnesscam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oooshk.witnesscam.databinding.ActivityReviewBinding
import com.oooshk.witnesscam.databinding.ItemSessionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists recording sessions on this phone with playback, cloud link and delete. */
class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding
    private val adapter = SessionAdapter()

    // Footage can be deleted from here, so it's behind the PIN like Settings.
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) binding.root.isVisible = true else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        if (Prefs(this).hasPin && savedInstanceState == null) {
            binding.root.isVisible = false
            unlock.launch(PinActivity.verifyIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) { ChunkStore.summaries(this@ReviewActivity) }
            adapter.items = sessions
            adapter.notifyDataSetChanged()
            binding.empty.isVisible = sessions.isEmpty()
        }
    }

    private fun play(session: ChunkStore.SessionSummary) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_DIR, session.dir.absolutePath))
    }

    private fun openInCloud(session: ChunkStore.SessionSummary) {
        val url = if (Prefs(this).cloudProvider == Cloud.DROPBOX) {
            "https://www.dropbox.com/home/Apps"
        } else {
            ChunkStore.folderIdFile(session.dir).takeIf { it.exists() }?.readText()?.trim()
                ?.let { DriveFolders.folderLink(it) } ?: "https://drive.google.com/drive/my-drive"
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun confirmDelete(session: ChunkStore.SessionSummary) {
        val message = if (session.pendingCount > 0) {
            getString(R.string.review_delete_pending, session.pendingCount)
        } else {
            getString(R.string.review_delete_uploaded)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.review_delete_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.review_delete) { _, _ ->
                session.dir.deleteRecursively()
                refresh()
            }
            .show()
    }

    private inner class SessionAdapter : RecyclerView.Adapter<SessionAdapter.Holder>() {
        var items: List<ChunkStore.SessionSummary> = emptyList()

        inner class Holder(val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = items[position]
            val ctx = holder.b.root.context
            holder.b.title.text = DateUtils.formatDateTime(
                ctx, s.startedAtMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY,
            )
            val minutes = ((s.endedAtMs - s.startedAtMs) / 60_000).coerceAtLeast(0)
            val cameras = ctx.getString(if (s.dualCamera) R.string.cams_dual else R.string.cams_single)
            val status = when {
                s.isRecording -> ctx.getString(R.string.review_recording_now)
                s.pendingCount > 0 -> ctx.getString(R.string.review_pending, s.pendingCount)
                else -> ctx.getString(R.string.review_all_uploaded)
            }
            holder.b.subtitle.text = ctx.getString(R.string.review_subtitle, minutes, cameras, s.uploadedCount, status)

            val playable = s.rearClips.isNotEmpty() || s.frontClips.isNotEmpty()
            holder.b.localNote.isVisible = !playable
            holder.b.play.isEnabled = playable
            holder.b.play.setOnClickListener { play(s) }
            holder.b.root.setOnClickListener { if (playable) play(s) }
            holder.b.cloud.setOnClickListener { openInCloud(s) }
            holder.b.delete.isEnabled = !s.isRecording
            holder.b.delete.setOnClickListener { confirmDelete(s) }
        }
    }
}
