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
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.data.repository.SongRepository
import com.musicvault.databinding.ActivityNowPlayingBinding
import com.musicvault.service.MusicService
import com.musicvault.ui.fragments.EqualizerFragment
import com.musicvault.ui.fragments.LyricsFragment
import com.musicvault.ui.fragments.ProfilesFragment
import com.musicvault.ui.fragments.SleepTimerFragment
import com.musicvault.ui.viewmodel.NowPlayingViewModel
import com.musicvault.utils.formatDuration
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

        // Initialize shared ViewModel so Equalizer / Lyrics / Profiles fragments work
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)

        setupControls()
        setupVolumeSeekBar()

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )

        // Load from DB immediately so title/artist/pitch/trim show before service binds
        if (songId != -1L) {
            activityScope.launch {
                repository.getSongById(songId)?.let { s ->
                    populate(s)
                    viewModel.setSong(s, false)
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
        binding.tvFolder.text = if (s.albumName.isNotBlank()) s.albumName else s.folderName

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
        binding.seekPitch.progress = s.pitchSemitones + 6

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

        // ── Repeat ─────────────────────────────────────────────────────────────
        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL  -> MusicService.REPEAT_ONE
                else                     -> MusicService.REPEAT_NONE
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
            svc.seekTo((svc.getPosition() - 5000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            svc.seekTo((svc.getPosition() + 5000).coerceAtMost(svc.getDuration()))
        }

        // ── Pitch seekbar ───────────────────────────────────────────────────────
        binding.seekPitch.max = 12
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val semitones = progress - 6
                binding.tvPitchValue.text = pitchLabel(semitones)
                if (fromUser) musicService?.applyPitchToCurrentSong(semitones)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val semitones = sb.progress - 6
                activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
            }
        })

        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 6
            binding.tvPitchValue.text = pitchLabel(0)
            musicService?.applyPitchToCurrentSong(0)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, 0) }
        }

        binding.btnPitchDown.setOnClickListener {
            val current = binding.seekPitch.progress
            if (current > 0) {
                val newProgress = current - 1
                binding.seekPitch.progress = newProgress
                val semitones = newProgress - 6
                binding.tvPitchValue.text = pitchLabel(semitones)
                musicService?.applyPitchToCurrentSong(semitones)
                activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
            }
        }

        binding.btnPitchUp.setOnClickListener {
            val current = binding.seekPitch.progress
            if (current < 12) {
                val newProgress = current + 1
                binding.seekPitch.progress = newProgress
                val semitones = newProgress - 6
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
            val newVolume = (current + (maxVolume / 10)).coerceAtMost(maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            syncVolumeSeekBar()
        }

        binding.btnVolumeDown.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newVolume = (current - (maxVolume / 10)).coerceAtLeast(0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            syncVolumeSeekBar()
        }

        binding.btnVolumeReset.setOnClickListener {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            syncVolumeSeekBar()
        }

        // ── Trim buttons ───────────────────────────────────────────────────────
        binding.btnTrimStart.setOnClickListener {
            val currentPosition = musicService?.getPosition() ?: 0
            val trimOffset = song?.trimStart?.toInt() ?: 0
            val newStart = (currentPosition - trimOffset).coerceAtLeast(0)
            val endProgress = binding.seekTrimEnd.progress
            val clamped = newStart.coerceAtMost((endProgress - 1000).coerceAtLeast(0))
            binding.seekTrimStart.progress = clamped
            updateTrimLabels(clamped.toLong(), endProgress.toLong())
            saveTrim()
        }

        binding.btnTrimEnd.setOnClickListener {
            val currentPosition = musicService?.getPosition() ?: 0
            val trimOffset = song?.trimStart?.toInt() ?: 0
            val newEnd = (currentPosition - trimOffset)
            val maxEnd = binding.seekTrimEnd.max
            val clampedEnd = newEnd.coerceIn(binding.seekTrimStart.progress + 1000, maxEnd)
            binding.seekTrimEnd.progress = clampedEnd
            updateTrimLabels(binding.seekTrimStart.progress.toLong(), clampedEnd.toLong())
            saveTrim()
        }

        // ── Feature buttons ───────────────────────────────────────────────────────
        binding.btnEqualizer.setOnClickListener {
            val svc = musicService
            val equalizerFragment = EqualizerFragment()
            equalizerFragment.onApplyCallback = { profile -> svc?.applyProfile(profile) }
            showFeatureFragment(equalizerFragment)
        }

        binding.btnLyrics.setOnClickListener {
            showFeatureFragment(LyricsFragment())
        }

        binding.btnProfiles.setOnClickListener {
            val frag = ProfilesFragment()
            frag.onProfileActivated = { musicService?.reloadActiveProfile() }
            showFeatureFragment(frag)
        }

        // ── Sleep Timer ───────────────────────────────────────────────────────
        binding.btnSleepTimer.setOnClickListener {
            val svc = musicService
            val timerFrag = SleepTimerFragment.newInstance(
                onSet = { durationMs -> svc?.setSleepTimer(durationMs) },
                onCancel = { svc?.cancelSleepTimer() },
                getRemaining = { svc?.getSleepTimerRemainingMs() ?: -1L }
            )
            showFeatureFragment(timerFrag)
        }
    }

    // ─── Repeat / Shuffle buttons ────────────────────────────────────────────────

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
        // Apply trim immediately to current playback
        musicService?.applyTrimToCurrentSong(start, end)
        // Also persist to database
        activityScope.launch { repository.updateTrimAndSyncProfile(id, start, end) }
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = "Start: ${formatDuration(start)}"
        binding.tvTrimEnd.text = "End: ${formatDuration(end)}"
    }

    private fun pitchLabel(s: Int) = if (s > 0) "+$s" else "$s"

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
                val trimStart = song?.trimStart?.toInt() ?: 0
                val pos = (svc.getPosition() - trimStart).coerceAtLeast(0)
                val dur = svc.getDuration()
                if (dur > 0) {
                    binding.seekPlayback.progress =
                        ((pos.toFloat() / dur) * 100).toInt().coerceIn(0, 100)
                    binding.tvCurrentTime.text = formatDuration(pos.toLong())
                    binding.tvTotalTime.text = formatDuration(dur.toLong())
                    // Keep lyrics in sync via ViewModel
                    viewModel.onLyricsPositionChanged(pos.toLong())
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

    // ─── Feature Fragments ────────────────────────────────────────────────────────

    private fun showFeatureFragment(fragment: Fragment) {
        if (fragment is BottomSheetDialogFragment) {
            fragment.show(supportFragmentManager, fragment::class.java.simpleName)
        } else {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .addToBackStack(null)
                .commit()
        }
    }
}
