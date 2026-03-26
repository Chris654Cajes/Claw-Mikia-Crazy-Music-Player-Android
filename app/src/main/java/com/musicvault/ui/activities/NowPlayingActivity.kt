package com.musicvault.ui.activities

import android.content.*
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.data.repository.SongRepository
import com.musicvault.databinding.ActivityNowPlayingBinding
import com.musicvault.service.MusicService
import com.musicvault.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SongRepository

    private var musicService: MusicService? = null
    private var song: Song? = null
    private var songId: Long = -1
    private var currentRepeatMode = MusicService.REPEAT_NONE

    private lateinit var audioManager: AudioManager
    private var maxVolume = 0

    // Guard: prevents saveTrim() firing when we programmatically clamp seekTrimStart
    private var isTrimDragging = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            currentRepeatMode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
            updateRepeatButton()
            registerCallbacks()
            syncNow()
            startProgressUpdates()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        fun start(ctx: Context, songId: Long) =
            ctx.startActivity(
                Intent(ctx, NowPlayingActivity::class.java)
                    .putExtra(EXTRA_SONG_ID, songId)
            )
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        repository = SongRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)

        setupControls()
        setupVolumeSeekBar()

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )

        // Load from DB immediately so pitch/trim are correct before service binds
        if (songId != -1L) {
            activityScope.launch {
                repository.getSongById(songId)?.let { populate(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        musicService?.let { registerCallbacks(); syncNow(); startProgressUpdates() }
        syncVolumeSeekBar()
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
    }

    override fun onDestroy() {
        stopProgressUpdates()
        musicService?.onSongChanged = null
        musicService?.onPlayStateChanged = null
        unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
    }

    // ─── Volume ──────────────────────────────────────────────────────────────────

    private fun setupVolumeSeekBar() {
        binding.seekVolume.max = maxVolume
        syncVolumeSeekBar()
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                val pct = ((sb.progress.toFloat() / maxVolume) * 100).toInt()
                binding.tvVolumeValue.text = "$pct"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun syncVolumeSeekBar() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.progress = current
        val pct = ((current.toFloat() / maxVolume) * 100).toInt()
        binding.tvVolumeValue.text = "$pct"
    }

    // ─── Service callbacks ───────────────────────────────────────────────────────

    private fun registerCallbacks() {
        val svc = musicService ?: return
        svc.onSongChanged = { s ->
            runOnUiThread {
                if (!isDestroyed) {
                    songId = s.id
                    populate(s)
                    binding.seekPlayback.progress = 0
                    binding.tvCurrentTime.text = formatDuration(0)
                    // Reload from DB to get latest persisted pitch/trim
                    activityScope.launch {
                        repository.getSongById(s.id)?.let { populate(it) }
                    }
                    startProgressUpdates()
                }
            }
        }
        svc.onPlayStateChanged = { playing ->
            runOnUiThread {
                if (!isDestroyed) {
                    binding.btnPlayPause.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    if (playing) startProgressUpdates() else stopProgressUpdates()
                }
            }
        }
    }

    private fun syncNow() {
        val svc = musicService ?: return
        binding.btnPlayPause.setImageResource(
            if (svc.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play
        )
        val cur = svc.getCurrentSong() ?: return
        songId = cur.id
        populate(cur)
        activityScope.launch {
            repository.getSongById(cur.id)?.let { populate(it) }
        }
    }

    // ─── Populate UI ─────────────────────────────────────────────────────────────

    private fun populate(s: Song) {
        song = s
        songId = s.id

        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = if (s.albumName.isNotBlank()) s.albumName else s.folderName

        binding.ivAlbumArt.clearColorFilter()
        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this).load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_music_note).error(R.drawable.ic_music_note)
                .into(binding.ivAlbumArt)
        } else {
            Glide.with(this).clear(binding.ivAlbumArt)
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            binding.ivAlbumArt.setColorFilter(ContextCompat.getColor(this, R.color.neon_pink))
        }

        // ── Pitch ──────────────────────────────────────────────────────────────
        binding.tvPitchValue.text = pitchLabel(s.pitchSemitones)
        // seekPitch range is 0–12 where 6 = 0 semitones
        binding.seekPitch.progress = s.pitchSemitones + 6

        // ── Trim ───────────────────────────────────────────────────────────────
        // Use the song's stored duration as the seekbar ceiling (in milliseconds).
        // Fall back to the service's reported duration if the stored one is 0.
        val totalMs = when {
            s.duration > 0 -> s.duration
            musicService?.getDuration() != 0 -> musicService!!.getDuration().toLong()
            else -> 0L
        }
        if (totalMs > 0) {
            binding.seekTrimStart.max = totalMs.toInt()
            binding.seekTrimEnd.max = totalMs.toInt()
        }
        val trimStart = s.trimStart
        val trimEnd = if (s.trimEnd > 0) s.trimEnd else totalMs
        // Set progress without triggering our drag-listener side-effects
        binding.seekTrimStart.progress = trimStart.toInt()
        binding.seekTrimEnd.progress = trimEnd.toInt()
        updateTrimLabels(trimStart, trimEnd)
    }

    // ─── Controls ────────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }

        binding.btnNext.setOnClickListener {
            binding.seekPlayback.progress = 0
            binding.tvCurrentTime.text = formatDuration(0)
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            musicService?.skipNext()
        }
        binding.btnPrev.setOnClickListener {
            binding.seekPlayback.progress = 0
            binding.tvCurrentTime.text = formatDuration(0)
            binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            musicService?.skipPrev()
        }

        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL  -> MusicService.REPEAT_ONE
                else                     -> MusicService.REPEAT_NONE
            }
            musicService?.setRepeatMode(currentRepeatMode)
            updateRepeatButton()
        }

        binding.btnRewind.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            svc.seekTo((svc.getPosition() - 5000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            svc.seekTo((svc.getPosition() + 5000).coerceAtMost(svc.getDuration()))
        }

        // ── Pitch seekbar ───────────────────────────────────────────────────────
        // Range 0–12, where progress 6 = 0 semitones (centre).
        binding.seekPitch.max = 12
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val semitones = progress - 6
                binding.tvPitchValue.text = pitchLabel(semitones)
                // Apply to the player live so the user hears the change immediately
                if (fromUser) musicService?.applyPitchToCurrentSong(semitones)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                // Persist when the user lifts their finger
                val semitones = sb.progress - 6
                activityScope.launch { repository.updatePitch(songId, semitones) }
            }
        })

        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 6      // 6 = 0 semitones
            musicService?.applyPitchToCurrentSong(0)
            activityScope.launch { repository.updatePitch(songId, 0) }
        }

        // ── Trim seekbars ───────────────────────────────────────────────────────
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return   // ignore programmatic changes (e.g. from populate())
                val endProgress = binding.seekTrimEnd.progress
                // Clamp so start never passes (end - 1 s)
                val clamped = p.coerceAtMost((endProgress - 1000).coerceAtLeast(0))
                if (p != clamped) {
                    sb.progress = clamped   // programmatic → fromUser=false → won't recurse
                    return
                }
                updateTrimLabels(clamped.toLong(), endProgress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                isTrimDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                isTrimDragging = false; saveTrim()
            }
        })

        binding.seekTrimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateTrimLabels(binding.seekTrimStart.progress.toLong(), p.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                isTrimDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                isTrimDragging = false; saveTrim()
            }
        })

        binding.btnTrimReset.setOnClickListener {
            val s = song ?: return@setOnClickListener
            val total = s.duration
            binding.seekTrimStart.progress = 0
            binding.seekTrimEnd.progress = total.toInt()
            updateTrimLabels(0L, total)
            // -1 means "use full duration" in the DB schema
            activityScope.launch { repository.updateTrim(s.id, 0L, -1L) }
        }

        // ── Playback seekbar ────────────────────────────────────────────────────
        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = musicService?.getDuration() ?: return
                    val trimStart = song?.trimStart?.toInt() ?: 0
                    musicService?.seekTo((progress / 100f * dur).toInt() + trimStart)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnFavorite.setOnClickListener {
            song?.let { s -> activityScope.launch { repository.toggleFavorite(s) } }
            song!!.isFavorite = !song!!.isFavorite

            binding.btnFavorite.setImageResource(
                if (song!!.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )
        }
    }

    // ─── Repeat button ───────────────────────────────────────────────────────────

    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_cyan))
                binding.tvRepeatLabel.text = "ALL"
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }
            MusicService.REPEAT_ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_pink))
                binding.tvRepeatLabel.text = "1"
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.text_hint))
                binding.tvRepeatLabel.visibility = android.view.View.INVISIBLE
            }
        }
    }

    // ─── Trim persistence ────────────────────────────────────────────────────────

    /**
     * Called only from onStopTrackingTouch — never during programmatic progress
     * changes — so there is no feedback loop risk.
     */
    private fun saveTrim() {
        val id = songId.takeIf { it != -1L } ?: return
        val start = binding.seekTrimStart.progress.toLong()
        val end = binding.seekTrimEnd.progress.toLong()
        activityScope.launch { repository.updateTrim(id, start, end) }
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = "Start: ${formatDuration(start)}"
        binding.tvTrimEnd.text = "End: ${formatDuration(end)}"
    }

    private fun pitchLabel(s: Int) = if (s > 0) "+$s" else "$s"

    // ─── Progress updates ────────────────────────────────────────────────────────

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val trimStart = song?.trimStart?.toInt() ?: 0
                val pos = (svc.getPosition() - trimStart).coerceAtLeast(0)
                val dur = svc.getDuration()
                if (dur > 0) {
                    binding.seekPlayback.progress =
                        ((pos.toFloat() / dur) * 100).toInt().coerceIn(0, 100)
                    binding.tvCurrentTime.text = formatDuration(pos.toLong())
                    binding.tvTotalTime.text = formatDuration(dur.toLong())
                }
                progressHandler.postDelayed(this, 500)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }
}
