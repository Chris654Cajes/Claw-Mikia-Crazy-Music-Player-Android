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
            song = songs.firstOrNull { it.id == songId }
            song?.let { populateSongData(it) }
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
        binding.tvFolder.text = s.folderName

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
                // Clamp: start can't exceed end - 1000ms
                if (progress >= binding.seekTrimEnd.progress - 1000) {
                    sb.progress = (binding.seekTrimEnd.progress - 1000).coerceAtLeast(0)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
        })

        binding.seekTrimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val start = binding.seekTrimStart.progress.toLong()
                updateTrimLabels(start, progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
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
        service.onPlayStateChanged = { playing ->
            runOnUiThread {
                binding.btnPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }
        service.onSongChanged = { s ->
            runOnUiThread {
                songId = s.id
                song = s
                populateSongData(s)
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
