package com.musicvault.ui.activities

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.databinding.ActivityNowPlayingBinding
import com.musicvault.service.MusicService
import com.musicvault.ui.viewmodels.MainViewModel
import com.musicvault.utils.formatDuration

class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val viewModel: MainViewModel by viewModels()

    private var musicService: MusicService? = null
    private var song: Song? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var songId: Long = -1

    // Tracks current repeat mode locally so we can cycle it: NONE → ALL → ONE → NONE
    private var currentRepeatMode = MusicService.REPEAT_NONE

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            updateUI()
            startProgressUpdates()
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        fun start(ctx: Context, songId: Long) {
            ctx.startActivity(Intent(ctx, NowPlayingActivity::class.java).apply {
                putExtra(EXTRA_SONG_ID, songId)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        viewModel.allSongs.observe(this) { songs ->
            // Use the service's current song id if available (handles auto-next correctly)
            val activeSongId = musicService?.getCurrentSong()?.id ?: songId
            val fresh = songs.firstOrNull { it.id == activeSongId }
                ?: songs.firstOrNull { it.id == songId }
            fresh?.let {
                songId = it.id
                song = it
                populateSongData(it)
            }
        }

        setupControls()
    }

    private fun setupSystemBars() {
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.isAppearanceLightStatusBars = false
        ctrl.isAppearanceLightNavigationBars = false
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
    }

    private fun populateSongData(s: Song) {
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        // Show album name when available, fall back to folder name
        binding.tvFolder.text = if (s.albumName.isNotBlank()) s.albumName else s.folderName

        // Load album art — clear tint first so Glide renders the real image correctly
        binding.ivAlbumArt.clearColorFilter()
        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this)
                .load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(binding.ivAlbumArt)
        } else {
            Glide.with(this).clear(binding.ivAlbumArt)
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            binding.ivAlbumArt.setColorFilter(
                androidx.core.content.ContextCompat.getColor(this, R.color.neon_pink)
            )
        }

        // Pitch
        binding.tvPitchValue.text = pitchLabel(s.pitchSemitones)
        binding.seekPitch.progress = s.pitchSemitones + 6  // 0..12 where 6 = 0 semitones

        // Trim
        val totalDuration = s.duration
        val trimEnd = if (s.trimEnd > 0) s.trimEnd else totalDuration
        binding.seekTrimStart.max = totalDuration.toInt()
        binding.seekTrimEnd.max = totalDuration.toInt()
        binding.seekTrimStart.progress = s.trimStart.toInt()
        binding.seekTrimEnd.progress = trimEnd.toInt()
        updateTrimLabels(s.trimStart, trimEnd)
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }
        binding.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.btnPrev.setOnClickListener { musicService?.skipPrev() }

        // Repeat button — cycles: NONE → ALL → ONE → NONE
        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL  -> MusicService.REPEAT_ONE
                else                     -> MusicService.REPEAT_NONE
            }
            musicService?.setRepeatMode(currentRepeatMode)
            updateRepeatButton()
        }

        // Pitch seekbar (-6 to +6, displayed as 0..12)
        binding.seekPitch.max = 12
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val semitones = progress - 6
                binding.tvPitchValue.text = pitchLabel(semitones)
                if (fromUser) {
                    musicService?.applyPitchToCurrentSong(semitones)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val semitones = sb.progress - 6
                song?.let { viewModel.updatePitch(it.id, semitones) }
            }
        })

        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 6
            song?.let {
                viewModel.updatePitch(it.id, 0)
                musicService?.applyPitchToCurrentSong(0)
            }
        }

        // Trim seekbars
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val end = binding.seekTrimEnd.progress.toLong()
                updateTrimLabels(progress.toLong(), end)
                if (progress >= binding.seekTrimEnd.progress - 1000) {
                    sb.progress = (binding.seekTrimEnd.progress - 1000).coerceAtLeast(0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveTrim() }
        })

        binding.seekTrimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val start = binding.seekTrimStart.progress.toLong()
                updateTrimLabels(start, progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveTrim() }
        })

        binding.btnTrimReset.setOnClickListener {
            song?.let { s ->
                binding.seekTrimStart.progress = 0
                binding.seekTrimEnd.progress = s.duration.toInt()
                updateTrimLabels(0, s.duration)
                viewModel.updateTrim(s.id, 0, -1)
            }
        }

        // Playback seekbar
        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = musicService?.getDuration() ?: return
                    val ms = (progress / 100f * dur).toInt()
                    val offset = song?.trimStart?.toInt() ?: 0
                    musicService?.seekTo(ms + offset)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnFavorite.setOnClickListener {
            song?.let { viewModel.toggleFavorite(it) }
        }
    }

    /** Update the repeat button icon and tint to reflect current mode. */
    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(this, R.color.neon_cyan)
                )
                binding.tvRepeatLabel.text = "ALL"
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }
            MusicService.REPEAT_ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(this, R.color.neon_pink)
                )
                binding.tvRepeatLabel.text = "1"
                binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
            }
            else -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(this, R.color.text_hint)
                )
                binding.tvRepeatLabel.visibility = android.view.View.INVISIBLE
            }
        }
    }

    private fun saveTrim() {
        val s = song ?: return
        val start = binding.seekTrimStart.progress.toLong()
        val end = binding.seekTrimEnd.progress.toLong()
        viewModel.updateTrim(s.id, start, end)
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = "Start: ${formatDuration(start)}"
        binding.tvTrimEnd.text = "End: ${formatDuration(end)}"
    }

    private fun pitchLabel(semitones: Int): String = when {
        semitones > 0 -> "+$semitones"
        else -> "$semitones"
    }

    private fun updateUI() {
        val service = musicService ?: return
        val playing = service.isPlaying()
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )

        // Sync with whatever song the service is currently playing
        service.getCurrentSong()?.let { current ->
            if (current.id != songId) {
                songId = current.id
                song = current
                populateSongData(current)
                viewModel.allSongs.value?.firstOrNull { it.id == current.id }?.let {
                    song = it
                    populateSongData(it)
                }
            }
        }

        service.onPlayStateChanged = { isPlaying ->
            runOnUiThread {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                if (!isPlaying) binding.seekPlayback.progress = 0
            }
        }
        service.onSongChanged = { s ->
            runOnUiThread {
                songId = s.id
                song = s
                populateSongData(s)
                binding.seekPlayback.progress = 0
                binding.tvCurrentTime.text = formatDuration(0)
                viewModel.allSongs.value?.firstOrNull { it.id == s.id }?.let { fresh ->
                    song = fresh
                    populateSongData(fresh)
                }
            }
        }
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val service = musicService ?: return
                val pos = service.getPosition() - (song?.trimStart?.toInt() ?: 0)
                val dur = service.getDuration()
                if (dur > 0) {
                    val prog = ((pos.coerceAtLeast(0).toFloat() / dur) * 100).toInt()
                    binding.seekPlayback.progress = prog.coerceIn(0, 100)
                    binding.tvCurrentTime.text = formatDuration(pos.toLong().coerceAtLeast(0))
                    binding.tvTotalTime.text = formatDuration(dur.toLong())
                }
                progressHandler.postDelayed(this, 500)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
