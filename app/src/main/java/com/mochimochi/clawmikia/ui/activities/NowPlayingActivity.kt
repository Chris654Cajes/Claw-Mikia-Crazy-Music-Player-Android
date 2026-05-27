package com.mochimochi.clawmikia.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.repository.SongRepository
import com.mochimochi.clawmikia.databinding.ActivityNowPlayingBinding
import com.mochimochi.clawmikia.service.MusicService
import com.mochimochi.clawmikia.ui.viewmodel.NowPlayingViewModel
import com.mochimochi.clawmikia.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SongRepository
    private lateinit var viewModel: NowPlayingViewModel

    private var musicService: MusicService? = null
    private var song: Song? = null
    private var songId: Long = -1
    private var currentRepeatMode = MusicService.REPEAT_NONE

    private lateinit var audioManager: AudioManager
    private var maxVolume = 0
    private var lastVolumeBeforeMute = -1

    private var pointA: Long = -1L
    private var pointB: Long = -1L
    private var isAbRepeatEnabled = false

    // Guard: prevents saveTrim() firing when we programmatically clamp seekTrimStart
    private var isTrimDragging = false

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            currentRepeatMode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
            updateRepeatButton()
            updateShuffleButton()
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
                    .putExtra(EXTRA_SONG_ID, songId),
            )
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        repository = SongRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Initialize shared ViewModel so Equalizer / Lyrics / Profiles fragments work
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        observeViewModel()

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)

        setupControls()
        setupVolumeSeekBar()

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE,
        )

        // Load from DB immediately so title/artist/pitch/trim show before service binds
        if (songId != -1L) {
            activityScope.launch {
                repository.getSongById(songId)?.let { s ->
                    populate(s)
                    viewModel.setSong(s, playing = false)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        musicService?.let {
            registerCallbacks()
            syncNow()
            startProgressUpdates()
        }
        syncVolumeSeekBar()
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
    }

    override fun onDestroy() {
        stopProgressUpdates()
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
        }
        unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun observeViewModel() {
        viewModel.songAnalysis.observe(this) { analysis ->
            if (analysis != null) {
                binding.tvBpm.text = "${analysis.bpm.toInt()} BPM"
                binding.tvKey.text = "Key: ${analysis.key}"
                binding.tvBpm.visibility = android.view.View.VISIBLE
                binding.tvKey.visibility = android.view.View.VISIBLE
            } else {
                binding.tvBpm.visibility = android.view.View.GONE
                binding.tvKey.visibility = android.view.View.GONE
            }
        }

        viewModel.activeProfile.observe(this) { profile ->
            if (profile != null) {
                pointA = profile.abRepeatA
                pointB = profile.abRepeatB
                isAbRepeatEnabled = profile.abRepeatEnabled

                binding.tvPointA.text =
                    if (pointA >= 0) "A: ${formatDuration(pointA)}" else "A: --:--"
                binding.tvPointB.text =
                    if (pointB >= 0) "B: ${formatDuration(pointB)}" else "B: --:--"

                // Use a flag or check if it actually changed to avoid infinite loop from setOnCheckedChangeListener
                if (binding.switchAbRepeat.isChecked != isAbRepeatEnabled) {
                    binding.switchAbRepeat.isChecked = isAbRepeatEnabled
                }
                if (binding.switchLoop.isChecked != profile.loopEnabled) {
                    binding.switchLoop.isChecked = profile.loopEnabled
                }
            }
        }
    }

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    // ─── Volume ──────────────────────────────────────────────────────────────────

    private fun setupVolumeSeekBar() {
        binding.seekVolume.max = maxVolume
        syncVolumeSeekBar()
        binding.seekVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) audioManager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        progress,
                        0
                    )
                    val pct = ((sb.progress.toFloat() / maxVolume) * 100).toInt()
                    binding.tvVolumeValue.text = pct.toString()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            },
        )
    }

    private fun syncVolumeSeekBar() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.progress = current
        val pct = ((current.toFloat() / maxVolume) * 100).toInt()
        binding.tvVolumeValue.text = pct.toString()
        binding.btnVolumeMute.setImageResource(
            if (current == 0) R.drawable.ic_volume_off else R.drawable.ic_volume
        )
    }

    // ─── Service callbacks ───────────────────────────────────────────────────────

    private val songChangedCallback: (Song) -> Unit = { s ->
        runOnUiThread {
            if (!isDestroyed) {
                // --- Fix 2: full UI refresh on song change ---
                songId = s.id
                // Apply immediate fields from the live Song object
                populate(s)
                // Reset playback seekbar for the new song
                binding.seekPlayback.progress = 0
                binding.tvCurrentTime.text = formatDuration(0)
                // Sync repeat mode from service (may differ per-song)
                currentRepeatMode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
                updateRepeatButton()
                updateShuffleButton()
                // Reload from DB to pick up persisted pitch/trim/speed
                activityScope.launch {
                    repository.getSongById(s.id)?.let { fresh ->
                        populate(fresh)
                        viewModel.setSong(fresh, musicService?.isPlaying() ?: false)
                    }
                }
                startProgressUpdates()
            }
        }
    }

    private val playStateCallback: (Boolean) -> Unit = { playing ->
        runOnUiThread {
            if (!isDestroyed) {
                binding.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
                viewModel.setPlaying(playing)
                if (playing) startProgressUpdates() else stopProgressUpdates()
            }
        }
    }

    private fun registerCallbacks() {
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
            service.addSongChangedCallback(songChangedCallback)
            service.addPlayStateCallback(playStateCallback)
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
            repository.getSongById(cur.id)?.let { fresh ->
                populate(fresh)
                viewModel.setSong(fresh, svc.isPlaying())
            }
        }
    }

    // ─── Populate UI ─────────────────────────────────────────────────────────────

    /**
     * Updates every UI element from [s]. Safe to call multiple times (idempotent
     * for the same song). The DB-fresh version is always preferred over the
     * in-memory snapshot to avoid stale pitch/speed/trim values.
     */
    private fun populate(s: Song) {
        song = s
        songId = s.id

        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = s.albumName.ifBlank { s.folderName }

        // Favorite button
        binding.btnFavorite.setImageResource(
            if (s.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        )

        // Album art
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
        binding.seekPitch.progress = ((s.pitchSemitones + 6) * 10).toInt().coerceIn(0, 120)

        // ── Speed ──────────────────────────────────────────────────────────────
        val speedProgress = ((s.playbackSpeed.coerceIn(0.5f, 2.0f) - 0.5f) / 0.05f).toInt()
        binding.seekSpeed.progress = speedProgress
        binding.tvSpeedValue.text = speedLabel(s.playbackSpeed)

        // ── Trim ───────────────────────────────────────────────────────────────
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
        binding.seekTrimStart.progress = trimStart.toInt()
        binding.seekTrimEnd.progress = trimEnd.toInt()
        updateTrimLabels(trimStart, trimEnd)

        // Sync main playback duration label
        val effectiveDur = (trimEnd - trimStart).coerceAtLeast(0L)
        binding.tvTotalTime.text = formatDuration(effectiveDur)
    }

    // ─── Controls ────────────────────────────────────────────────────────────────

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }

        binding.btnNext.setOnClickListener {
            musicService?.skipNext()
        }
        binding.btnPrev.setOnClickListener {
            // Immediate UI feedback for replay
            binding.seekPlayback.progress = 0
            binding.tvCurrentTime.text = formatDuration(0)
            musicService?.skipPrev()
        }

        // ── Loop Switch ────────────────────────────────────────────────────────
        binding.switchLoop.setOnCheckedChangeListener { _, checked ->
            val s = song ?: return@setOnCheckedChangeListener
            musicService?.applyLoopToCurrentSong(
                s.trimStart,
                if (s.trimEnd > 0) s.trimEnd else s.duration,
                checked
            )
            // Save to Profile
            viewModel.activeProfile.value?.let { profile ->
                viewModel.updateLoop(profile.id, profile.loopStart, profile.loopEnd, checked)
            }
            // Also save to Song table for compatibility
            activityScope.launch {
                repository.updateRepeatModeAndSyncProfile(
                    s.id,
                    if (checked) 1 else 0
                )
            }
        }

        // ── Repeat ─────────────────────────────────────────────────────────────
        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL -> MusicService.REPEAT_ONE
                else -> MusicService.REPEAT_NONE
            }
            musicService?.setRepeatMode(currentRepeatMode)
            updateRepeatButton()
        }

        // ── Shuffle ────────────────────────────────────────────────────────────
        binding.btnShuffle.setOnClickListener {
            musicService?.toggleShuffle()
            updateShuffleButton()
        }

        // ── Rewind / Forward ────────────────────────────────────────────────────
        binding.btnRewind.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tStart = song?.trimStart?.toInt() ?: 0
            svc.seekTo((svc.getPosition() - 5000).coerceAtLeast(tStart))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tEnd = if ((song?.trimEnd ?: 0L) > 0L) song!!.trimEnd.toInt() else svc.getDuration()
            svc.seekTo((svc.getPosition() + 5000).coerceAtMost(tEnd))
        }

        // ── Pitch seekbar ───────────────────────────────────────────────────────
        binding.seekPitch.max = 120
        binding.seekPitch.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val semitones = (progress - 60) / 10.0f
                    binding.tvPitchValue.text = pitchLabel(semitones)
                    if (fromUser) musicService?.applyPitchToCurrentSong(semitones)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val semitones = (sb.progress - 60) / 10.0f
                    activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
                }
            },
        )

        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 60
            binding.tvPitchValue.text = pitchLabel(0f)
            musicService?.applyPitchToCurrentSong(0f)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, 0f) }
        }

        binding.btnPitchDown.setOnClickListener {
            val current = binding.seekPitch.progress
            if (current > 0) {
                val newProgress = current - 1
                binding.seekPitch.progress = newProgress
                val semitones = (newProgress - 60) / 10.0f
                binding.tvPitchValue.text = pitchLabel(semitones)
                musicService?.applyPitchToCurrentSong(semitones)
                activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
            }
        }

        binding.btnPitchUp.setOnClickListener {
            val current = binding.seekPitch.progress
            if (current < 120) {
                val newProgress = current + 1
                binding.seekPitch.progress = newProgress
                val semitones = (newProgress - 60) / 10.0f
                binding.tvPitchValue.text = pitchLabel(semitones)
                musicService?.applyPitchToCurrentSong(semitones)
                activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
            }
        }

        // ── Speed seekbar ───────────────────────────────────────────────────────
        binding.seekSpeed.max = 30
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                binding.tvSpeedValue.text = speedLabel(speed)
                if (fromUser) musicService?.applySpeedToCurrentSong(speed)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val speed = progressToSpeed(sb.progress)
                activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
            }
        })

        binding.btnSpeedReset.setOnClickListener {
            binding.seekSpeed.progress = 10
            binding.tvSpeedValue.text = speedLabel(1.0f)
            musicService?.applySpeedToCurrentSong(1.0f)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, 1.0f) }
        }

        binding.btnSpeedDown.setOnClickListener {
            val current = binding.seekSpeed.progress
            if (current > 0) {
                val newProgress = current - 1
                binding.seekSpeed.progress = newProgress
                val speed = progressToSpeed(newProgress)
                binding.tvSpeedValue.text = speedLabel(speed)
                musicService?.applySpeedToCurrentSong(speed)
                activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
            }
        }

        binding.btnSpeedUp.setOnClickListener {
            val current = binding.seekSpeed.progress
            if (current < 30) {
                val newProgress = current + 1
                binding.seekSpeed.progress = newProgress
                val speed = progressToSpeed(newProgress)
                binding.tvSpeedValue.text = speedLabel(speed)
                musicService?.applySpeedToCurrentSong(speed)
                activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
            }
        }

        // ── Trim seekbars ───────────────────────────────────────────────────────
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val endProgress = binding.seekTrimEnd.progress
                val clamped = p.coerceAtMost((endProgress - 1000).coerceAtLeast(0))
                if (p != clamped) {
                    sb.progress = clamped; return
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
            // Apply trim reset immediately to current playback
            musicService?.applyTrimToCurrentSong(0L, -1L)
            // Also persist to database
            activityScope.launch { repository.updateTrimAndSyncProfile(s.id, 0L, -1L) }
        }

        // ── Playback seekbar ────────────────────────────────────────────────────
        binding.seekPlayback.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val svc = musicService ?: return
                        val s = song ?: return
                        val fullDur = svc.getDuration().toLong()
                        val tStart = s.trimStart
                        val tEnd = if (s.trimEnd > 0L) s.trimEnd else fullDur
                        val effectiveDur = (tEnd - tStart).coerceAtLeast(0L)

                        val targetRelativePos = ((progress / 100.0) * effectiveDur).toLong()
                        svc.seekTo((targetRelativePos + tStart).toInt())
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            },
        )

        // ── Favorite ────────────────────────────────────────────────────────────
        binding.btnFavorite.setOnClickListener {
            val s = song ?: return@setOnClickListener
            activityScope.launch {
                repository.toggleFavorite(s)
                repository.getSongById(s.id)?.let { fresh ->
                    song = fresh
                    runOnUiThread {
                        binding.btnFavorite.setImageResource(
                            if (fresh.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                        )
                    }
                }
            }
        }

        // ── Volume buttons ───────────────────────────────────────────────────────
        binding.btnVolumeUp.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = maxOf(1, maxVolume / 10)
            val newVolume = (current + step).coerceAtMost(maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            syncVolumeSeekBar()
        }

        binding.btnVolumeDown.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = maxOf(1, maxVolume / 10)
            val newVolume = (current - step).coerceAtLeast(0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            syncVolumeSeekBar()
        }

        binding.btnVolumeReset.setOnClickListener {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            syncVolumeSeekBar()
        }

        binding.btnVolumeMute.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                lastVolumeBeforeMute = current
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else {
                val restoreVol =
                    if (lastVolumeBeforeMute > 0) lastVolumeBeforeMute else maxVolume / 2
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
            }
            syncVolumeSeekBar()
        }

        // ── Trim buttons ───────────────────────────────────────────────────────
        binding.btnTrimStartMinus.setOnClickListener {
            val current = binding.seekTrimStart.progress
            val newVal = (current - 10000).coerceAtLeast(0)
            binding.seekTrimStart.progress = newVal
            updateTrimLabels(newVal.toLong(), binding.seekTrimEnd.progress.toLong())
            saveTrim()
        }

        binding.btnTrimStartPlus.setOnClickListener {
            val current = binding.seekTrimStart.progress
            val endVal = binding.seekTrimEnd.progress
            val newVal = (current + 10000).coerceAtMost((endVal - 1000).coerceAtLeast(0))
            binding.seekTrimStart.progress = newVal
            updateTrimLabels(newVal.toLong(), endVal.toLong())
            saveTrim()
        }

        binding.btnTrimEndMinus.setOnClickListener {
            val current = binding.seekTrimEnd.progress
            val startVal = binding.seekTrimStart.progress
            val newVal = (current - 10000).coerceAtLeast(startVal + 1000)
            binding.seekTrimEnd.progress = newVal
            updateTrimLabels(startVal.toLong(), newVal.toLong())
            saveTrim()
        }

        binding.btnTrimEndPlus.setOnClickListener {
            val current = binding.seekTrimEnd.progress
            val maxEnd = binding.seekTrimEnd.max
            val newVal = (current + 10000).coerceAtMost(maxEnd)
            binding.seekTrimEnd.progress = newVal
            updateTrimLabels(binding.seekTrimStart.progress.toLong(), newVal.toLong())
            saveTrim()
        }

        // ── A-B Repeat ────────────────────────────────────────────────────────
        binding.btnSetPointA.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: return@setOnClickListener
            pointA = pos
            binding.tvPointA.text = "A: ${formatDuration(pointA)}"
            saveAbRepeat()
            if (isAbRepeatEnabled) applyAbRepeat()
        }

        binding.btnSetPointB.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: return@setOnClickListener
            if (pos <= pointA) return@setOnClickListener
            pointB = pos
            binding.tvPointB.text = "B: ${formatDuration(pointB)}"
            saveAbRepeat()
            if (isAbRepeatEnabled) applyAbRepeat()
        }

        binding.btnResetAb.setOnClickListener {
            pointA = -1L
            pointB = -1L
            isAbRepeatEnabled = false
            binding.tvPointA.text = "A: --:--"
            binding.tvPointB.text = "B: --:--"
            binding.switchAbRepeat.isChecked = false
            musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            saveAbRepeat()
        }

        binding.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
            isAbRepeatEnabled = checked
            if (checked) {
                if (pointA >= 0 && pointB > pointA) {
                    applyAbRepeat()
                } else {
                    binding.switchAbRepeat.isChecked = false
                    Toast.makeText(this, "Set A and B points first", Toast.LENGTH_SHORT).show()
                }
            } else {
                musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            }
            saveAbRepeat()
        }
    }

    // ─── Repeat / Shuffle buttons ────────────────────────────────────────────────

    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_cyan))
                binding.tvRepeatLabel.text = getString(R.string.repeat_all)
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }

            MusicService.REPEAT_ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_pink))
                binding.tvRepeatLabel.text = getString(R.string.repeat_one)
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }

            else -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.text_hint))
                binding.tvRepeatLabel.text = getString(R.string.repeat_none)
                binding.tvRepeatLabel.visibility = android.view.View.INVISIBLE
            }
        }
    }

    private fun updateShuffleButton() {
        val shuffleOn = musicService?.isShuffleEnabled() ?: false
        if (shuffleOn) {
            binding.btnShuffle.setImageResource(R.drawable.ic_shuffle_on)
            binding.btnShuffle.setColorFilter(ContextCompat.getColor(this, R.color.neon_cyan))
        } else {
            binding.btnShuffle.setImageResource(R.drawable.ic_shuffle)
            binding.btnShuffle.setColorFilter(ContextCompat.getColor(this, R.color.text_hint))
        }
    }

    // ─── Trim persistence ────────────────────────────────────────────────────────

    private fun saveTrim() {
        val id = songId.takeIf { it != -1L } ?: return
        val start = binding.seekTrimStart.progress.toLong()
        val end = binding.seekTrimEnd.progress.toLong()

        // Update local song object so startProgressUpdates sees it immediately
        song = song?.copy(trimStart = start, trimEnd = end)

        // Apply trim immediately to current playback
        musicService?.applyTrimToCurrentSong(start, end)
        // Also persist to database
        activityScope.launch { repository.updateTrimAndSyncProfile(id, start, end) }
    }

    private fun applyAbRepeat() {
        if (pointA >= 0 && pointB > pointA) {
            musicService?.applyAbRepeatToCurrentSong(pointA, pointB, true)
        }
    }

    private fun saveAbRepeat() {
        viewModel.activeProfile.value?.let { profile ->
            viewModel.updateAbRepeat(profile.id, pointA, pointB, isAbRepeatEnabled)
        }
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = getString(R.string.trim_start_label, formatDuration(start))
        binding.tvTrimEnd.text = getString(R.string.trim_end_label, formatDuration(end))
    }

    private fun pitchLabel(s: Float) = if (s > 0) "+%.1f".format(s) else "%.1f".format(s)

    private fun progressToSpeed(progress: Int): Float =
        (0.5f + progress * 0.05f).coerceIn(0.5f, 2.0f)

    private fun speedLabel(speed: Float): String =
        "%.2f".format(speed).trimEnd('0').trimEnd('.')

    // ─── Progress updates ────────────────────────────────────────────────────────

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val s = song ?: return

                val fullDur = svc.getDuration().toLong()
                val trimStart = s.trimStart
                val trimEnd = if (s.trimEnd > 0L) s.trimEnd else fullDur
                val effectiveDur = (trimEnd - trimStart).coerceAtLeast(0L)

                val absolutePos = svc.getPosition().toLong()
                val relativePos = (absolutePos - trimStart).coerceAtLeast(0L)

                if (fullDur > 0) {
                    if (effectiveDur > 0) {
                        binding.seekPlayback.progress =
                            ((relativePos.toFloat() / effectiveDur) * 100).toInt().coerceIn(0, 100)
                        binding.tvCurrentTime.text = formatDuration(relativePos)
                        binding.tvTotalTime.text = formatDuration(effectiveDur)
                    } else {
                        binding.seekPlayback.progress = 0
                        binding.tvCurrentTime.text = formatDuration(0)
                        binding.tvTotalTime.text = formatDuration(fullDur)
                    }
                    // Keep lyrics in sync via ViewModel (needs absolute position)
                    viewModel.onLyricsPositionChanged(absolutePos)
                }
                progressHandler.postDelayed(this, 200)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }
}
