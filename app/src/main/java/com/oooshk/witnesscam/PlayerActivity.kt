package com.oooshk.witnesscam

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.oooshk.witnesscam.databinding.ActivityPlayerBinding
import java.io.File

/** Plays a session's clips back to back, with a switch between rear and front camera. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private var rear: List<File> = emptyList()
    private var front: List<File> = emptyList()
    private var showingFront = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = ChunkStore.summary(File(intent.getStringExtra(EXTRA_DIR) ?: ""))
        if (session == null) {
            finish()
            return
        }
        rear = session.rearClips
        front = session.frontClips

        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = renderLabel()
        })

        binding.switchCamera.isVisible = rear.isNotEmpty() && front.isNotEmpty()
        binding.switchCamera.setOnClickListener { show(!showingFront) }

        show(front = rear.isEmpty(), index = savedInstanceState?.getInt(KEY_INDEX) ?: 0,
            positionMs = savedInstanceState?.getLong(KEY_POSITION) ?: 0)
        if (savedInstanceState?.getBoolean(KEY_FRONT) == true && front.isNotEmpty()) show(true)
        player.play()
    }

    /** Switches camera, staying on the same clip number and position where possible. */
    private fun show(front: Boolean, index: Int = player.currentMediaItemIndex, positionMs: Long = player.currentPosition) {
        val clips = if (front) this.front else rear
        if (clips.isEmpty()) return
        showingFront = front
        val items = clips.map {
            MediaItem.Builder().setUri(Uri.fromFile(it)).setMimeType(MimeTypes.VIDEO_MP4).setMediaId(it.name).build()
        }
        player.setMediaItems(items, index.coerceIn(0, items.lastIndex), positionMs)
        player.prepare()
        binding.switchCamera.setText(if (front) R.string.player_show_rear else R.string.player_show_front)
        renderLabel()
    }

    private fun renderLabel() {
        val clips = if (showingFront) front else rear
        val i = player.currentMediaItemIndex
        val clip = clips.getOrNull(i) ?: return
        val started = ChunkStore.readMeta(clip).optString("startedAt")
        binding.label.text = getString(
            R.string.player_label, i + 1, clips.size,
            getString(if (showingFront) R.string.camera_front else R.string.camera_rear), started,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::player.isInitialized) {
            outState.putInt(KEY_INDEX, player.currentMediaItemIndex)
            outState.putLong(KEY_POSITION, player.currentPosition)
            outState.putBoolean(KEY_FRONT, showingFront)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DIR = "dir"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val KEY_FRONT = "front"
    }
}
