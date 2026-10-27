package com.mitv.player

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.mitv.player.databinding.ActivityPlayerBinding

/** Fullscreen player: ExoPlayer + TV OSD. Back exits; position memory for
 *  VOD/series is written to HistoryStore so Continuar works (Windows rule). */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isLive = false
    private var itemKey = ""
    private var startMs = 0L
    private var osdVisible = true
    private var lastProgressSave = 0L

    private val hideOsd = Runnable { setOsd(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateTimeUi()
            saveProgressThrottled()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        isLive = intent.getBooleanExtra("live", false)
        itemKey = intent.getStringExtra("key") ?: ""
        startMs = intent.getLongExtra("startMs", 0)

        b.osdTitle.text = intent.getStringExtra("title") ?: ""
        b.osdSub.text = intent.getStringExtra("sub") ?: ""

        if (isLive) {
            b.btnBack10.visibility = View.GONE
            b.btnFwd10.visibility = View.GONE
            b.seekBar.visibility = View.GONE
        }

        player = ExoPlayer.Builder(this).build().also { p ->
            b.playerView.player = p
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            if (!isLive && startMs > 5000) p.seekTo(startMs)
            p.playWhenReady = true
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    b.loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_ENDED && !isLive) saveProgressFinal()
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    b.btnPlay.text = if (playing) "⏸ Pausa" else "▶ Reproducir"
                }
            })
        }

        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnPlay.setOnKeyListener { _, kc, ev -> if (ev.action == KeyEvent.ACTION_DOWN && (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) { togglePlay(); true } else false }
        b.btnBack10.setOnClickListener { seekBy(-10_000) }
        b.btnFwd10.setOnClickListener { seekBy(10_000) }
        b.btnAudio.setOnClickListener { showTrackDialog(audio = true) }
        b.btnSubs.setOnClickListener { showTrackDialog(audio = false) }
        b.btnAspect.setOnClickListener { cycleAspect() }

        b.seekBar.setOnKeyListener { _, kc, ev ->
            if (!isLive && ev.action == KeyEvent.ACTION_UP &&
                (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
                 kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) {
                val p = player
                if (p != null && p.duration > 0) p.seekTo(p.duration * b.seekBar.progress / 1000)
                scheduleHide()
                true
            } else false
        }
        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLive) {
                    val p = player ?: return
                    val dur = p.duration
                    if (dur > 0) {
                        val label = "${Util.durationText(dur * progress / 1000)} / ${Util.durationText(dur)}"
                        b.timeText.text = label
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(hideOsd) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = player ?: return
                val dur = p.duration
                if (!isLive && dur > 0) p.seekTo(dur * b.seekBar.progress / 1000)
                scheduleHide()
            }
        })

        setOsd(true)
        b.btnPlay.requestFocus()
        handler.post(ticker)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        scheduleHide()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (isLive) return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        p.seekTo(target)
        scheduleHide()
    }

    private val aspectModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
        AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    )
    private val aspectNames = arrayOf("Ajustar", "Zoom", "Rellenar", "Ancho fijo", "Alto fijo")
    private var aspectIdx = 0
    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectModes.size
        b.playerView.resizeMode = aspectModes[aspectIdx]
        Toast.makeText(this, "Aspecto: ${aspectNames[aspectIdx]}", Toast.LENGTH_SHORT).show()
        scheduleHide()
    }

    private fun showTrackDialog(audio: Boolean) {
        val p = player ?: return
        val tracks = p.currentTracks
        val type = if (audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val labels = ArrayList<String>()
        val overrides = ArrayList<TrackSelectionOverride?>()
        if (!audio) { labels.add("Desactivados"); overrides.add(null) }
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language?.takeIf { it.isNotEmpty() && it != "und" } ?: ""
                val label = listOfNotNull(
                    fmt.label?.takeIf { it.isNotEmpty() },
                    lang.takeIf { it.isNotEmpty() },
                    if (audio) fmt.channelCount.let { if (it > 0) "${it}ch" else null } else null,
                    fmt.codecs
                ).joinToString(" · ").ifEmpty { if (audio) "Audio ${labels.size + 1}" else "Subtítulo ${labels.size}" }
                labels.add(label)
                overrides.add(TrackSelectionOverride(group.mediaTrackGroup, i))
            }
        }
        if (labels.isEmpty() || (audio && labels.isEmpty())) {
            Toast.makeText(this, if (audio) "Sin pistas de audio alternativas" else "Sin subtítulos en este contenido", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (audio) "Audio" else "💬 Subtítulos")
            .setItems(labels.toTypedArray()) { _, which ->
                val ov = overrides[which]
                val params = p.trackSelectionParameters.buildUpon()
                if (ov == null) {
                    params.setTrackTypeDisabled(type, true)
                } else {
                    params.setTrackTypeDisabled(type, false)
                    params.addOverride(ov)
                }
                p.trackSelectionParameters = params.build()
            }
            .setOnDismissListener { scheduleHide(); b.btnPlay.requestFocus() }
            .show()
    }

    private fun updateTimeUi() {
        val p = player ?: return
        if (isLive) {
            b.timeText.text = "● EN VIVO"
            return
        }
        val dur = p.duration
        val pos = p.currentPosition
        if (dur > 0) {
            if (!b.seekBar.hasFocus()) b.seekBar.progress = (pos * 1000 / dur).toInt()
            b.timeText.text = "${Util.durationText(pos)} / ${Util.durationText(dur)}"
        }
    }

    private fun saveProgressThrottled() {
        if (isLive || itemKey.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastProgressSave < 5000) return
        lastProgressSave = now
        val p = player ?: return
        if (p.duration > 0) HistoryStore.updateProgress(itemKey, p.currentPosition, p.duration)
    }

    private fun saveProgressFinal() {
        if (isLive || itemKey.isEmpty()) return
        val p = player ?: return
        if (p.duration > 0) HistoryStore.updateProgress(itemKey, p.currentPosition, p.duration)
    }

    private fun setOsd(show: Boolean) {
        osdVisible = show
        b.osd.visibility = if (show) View.VISIBLE else View.GONE
        if (show) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 5000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlay(); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!osdVisible) { setOsd(true); b.btnPlay.requestFocus(); return true }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!osdVisible) { setOsd(true); b.btnPlay.requestFocus(); return true }
            }
            KeyEvent.KEYCODE_BACK -> {
                saveProgressFinal()
                finish(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        saveProgressFinal()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgressFinal()
        player?.release()
        player = null
        super.onDestroy()
    }
}
