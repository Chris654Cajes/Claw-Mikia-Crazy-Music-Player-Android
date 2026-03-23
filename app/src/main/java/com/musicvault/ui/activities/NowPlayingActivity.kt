package com.musicvault.ui.activities

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var songId: Long = -1
    private var currentRepeatMode = MusicService.REPEAT_NONE

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
                Intent(ctx, NowPlayingActivity::class.java).putExtra(
                    EXTRA_SONG_ID,
                    songId
                )
            )
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
            BIND_AUTO_CREATE
        )
        viewModel.allSongs.observe(this) { songs ->
            val id = musicService?.getCurrentSong()?.id ?: songId
            songs.firstOrNull { it.id == id }?.let { populate(it) }
        }
        setupControls()
    }

    override fun onResume() {
        super.onResume()
        musicService?.let { registerCallbacks(); syncNow(); startProgressUpdates() }
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
            isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
    }

    private fun registerCallbacks() {
        val svc = musicService ?: return
        svc.onSongChanged = { s ->
            runOnUiThread {
                if (!isDestroyed) {
                    songId = s.id; song = s
                    populate(s)
                    binding.seekPlayback.progress = 0
                    binding.tvCurrentTime.text = formatDuration(0)
                    // Use DB record if available (has online metadata)
                    viewModel.allSongs.value?.firstOrNull { it.id == s.id }?.let { populate(it) }
                    startProgressUpdates()
                }
            }
        }
        svc.onPlayStateChanged = { playing ->
            runOnUiThread {
                if (!isDestroyed) {
                    binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                    if (playing) startProgressUpdates() else stopProgressUpdates()
                }
            }
        }
    }

    private fun syncNow() {
        val svc = musicService ?: return
        binding.btnPlayPause.setImageResource(if (svc.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        val cur = svc.getCurrentSong() ?: return
        songId = cur.id; song = cur
        populate(cur)
        viewModel.allSongs.value?.firstOrNull { it.id == cur.id }?.let { populate(it) }
    }

    private fun populate(s: Song) {
        song = s
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
        binding.tvPitchValue.text = pitchLabel(s.pitchSemitones)
        binding.seekPitch.progress = s.pitchSemitones + 6
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
            val newPos = (svc.getPosition() - 5000).coerceAtLeast(0)
            svc.seekTo(newPos)
        }

        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val duration = svc.getDuration()
            val newPos = (svc.getPosition() + 5000).coerceAtMost(duration)
            svc.seekTo(newPos)
        }

        binding.seekPitch.max = 12
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                binding.tvPitchValue.text = pitchLabel(progress - 6)
                if (fromUser) musicService?.applyPitchToCurrentSong(progress - 6)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                song?.let { viewModel.updatePitch(it.id, sb.progress - 6) }
            }
        })
        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 6
            song?.let { viewModel.updatePitch(it.id, 0); musicService?.applyPitchToCurrentSong(0) }
        }

        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateTrimLabels(p.toLong(), binding.seekTrimEnd.progress.toLong())
                if (p >= binding.seekTrimEnd.progress - 1000) sb.progress =
                    (binding.seekTrimEnd.progress - 1000).coerceAtLeast(0)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { saveTrim() }
        })
        binding.seekTrimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateTrimLabels(binding.seekTrimStart.progress.toLong(), p.toLong())
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

        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = musicService?.getDuration() ?: return
                    musicService?.seekTo(
                        (progress / 100f * dur).toInt() + (song?.trimStart?.toInt() ?: 0)
                    )
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnFavorite.setOnClickListener { song?.let { viewModel.toggleFavorite(it) } }
    }

    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_cyan))
                binding.tvRepeatLabel.text = "ALL"; binding.tvRepeatLabel.visibility =
                    android.view.View.VISIBLE
            }
            MusicService.REPEAT_ONE -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.neon_pink))
                binding.tvRepeatLabel.text = "1"; binding.tvRepeatLabel.visibility =
                    android.view.View.VISIBLE
            }
            else -> {
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(ContextCompat.getColor(this, R.color.text_hint))
                binding.tvRepeatLabel.visibility = android.view.View.INVISIBLE
            }
        }
    }

    private fun saveTrim() {
        song?.let {
            viewModel.updateTrim(
                it.id,
                binding.seekTrimStart.progress.toLong(),
                binding.seekTrimEnd.progress.toLong()
            )
        }
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = "Start: ${formatDuration(start)}"
        binding.tvTrimEnd.text = "End: ${formatDuration(end)}"
    }

    private fun pitchLabel(s: Int) = if (s > 0) "+$s" else "$s"

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val pos = svc.getPosition() - (song?.trimStart?.toInt() ?: 0)
                val dur = svc.getDuration()
                if (dur > 0) {
                    binding.seekPlayback.progress =
                        ((pos.coerceAtLeast(0).toFloat() / dur) * 100).toInt().coerceIn(0, 100)
                    binding.tvCurrentTime.text = formatDuration(pos.toLong().coerceAtLeast(0))
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
