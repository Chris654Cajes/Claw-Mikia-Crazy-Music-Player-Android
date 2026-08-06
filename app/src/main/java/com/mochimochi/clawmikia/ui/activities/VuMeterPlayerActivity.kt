package com.mochimochi.clawmikiacrazy.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.data.repository.ProfileRepository
import com.mochimochi.clawmikiacrazy.data.repository.SongRepository
import com.mochimochi.clawmikiacrazy.databinding.ActivityVumeterPlayerBinding
import com.mochimochi.clawmikiacrazy.databinding.DialogEditSongBinding
import com.mochimochi.clawmikiacrazy.service.MusicService
import com.mochimochi.clawmikiacrazy.ui.fragments.ProfilesFragment
import com.mochimochi.clawmikiacrazy.ui.viewmodels.NowPlayingViewModel
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

class VuMeterPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVumeterPlayerBinding
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SongRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var viewModel: NowPlayingViewModel

    private var favoriteIconType: String = FavoriteIconHelper.ALL_TYPES[0]
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
    private var isTrimDragging = false

    private val volumeObserver =
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncVolumeSeekBar()
            }
        }

    private var selectedArtUri: Uri? = null
    private val pickArtLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedArtUri = it
                editDialogBinding?.let { dlgBinding ->
                    Glide.with(this).load(it).into(dlgBinding.ivEditAlbumArt)
                }
            }
        }
    private var editDialogBinding: DialogEditSongBinding? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var vuRunnable: Runnable? = null

    private val repeatModeCallback: (Int) -> Unit = { mode ->
        runOnUiThread {
            currentRepeatMode = mode
            updateRepeatButton()
        }
    }

    private val shuffleCallback: (Boolean) -> Unit = { _ ->
        runOnUiThread {
            updateShuffleButton()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            registerCallbacks()
            syncNow()
            startProgressUpdates()
            startVuAnimation()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        fun start(ctx: Context, songId: Long) =
            ctx.startActivity(
                Intent(ctx, VuMeterPlayerActivity::class.java).putExtra(
                    EXTRA_SONG_ID,
                    songId
                )
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVumeterPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainContentLayout.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            )
            insets
        }

        repository = SongRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        settingsRepo.favoriteIconLive.observe(this) { iconType ->
            favoriteIconType = iconType
            song?.let { updateFavoriteIcon(it) }
        }

        observeViewModel()
        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)
        setupControls()
        setupVolumeSeekBar()

        bindService(Intent(this, MusicService::class.java), serviceConnection, BIND_AUTO_CREATE)

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
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )
        musicService?.let {
            registerCallbacks()
            syncNow()
            startProgressUpdates()
            startVuAnimation()
            val isBypassing = it.isBypassingProfiles()
            binding.switchPlaybackState.isChecked = !isBypassing
            updateStateLabels(!isBypassing)
        }
        syncVolumeSeekBar()
        updateProfileSwitchButtons()
    }

    override fun onPause() {
        super.onPause(); stopProgressUpdates(); stopVuAnimation()
        contentResolver.unregisterContentObserver(volumeObserver)
    }

    override fun onDestroy() {
        stopProgressUpdates()
        stopVuAnimation()
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
            service.removeRepeatModeCallback(repeatModeCallback)
            service.removeShuffleCallback(shuffleCallback)
        }
        unbindService(serviceConnection)
        super.onDestroy()
    }

    private fun observeViewModel() {
        viewModel.currentSong.observe(this) { }
        viewModel.profiles.observe(this) { updateProfileSwitchButtons() }
        viewModel.songAnalysis.observe(this) { analysis ->
            if (analysis != null) {
                binding.tvBpm.text = getString(R.string.bpm_format, analysis.bpm.toInt())
                binding.tvKey.text = getString(R.string.key_format, analysis.key)
                binding.tvBpm.visibility = android.view.View.VISIBLE
                binding.tvKey.visibility = android.view.View.VISIBLE
            } else {
                binding.tvBpm.visibility = android.view.View.GONE
                binding.tvKey.visibility = android.view.View.GONE
            }
        }
        viewModel.activeProfile.observe(this) { profile ->
            profile?.let { p ->
                musicService?.applyProfile(p)
                pointA = p.abRepeatA
                pointB = p.abRepeatB
                isAbRepeatEnabled = p.abRepeatEnabled
                binding.tvPointA.text =
                    if (pointA >= 0) "A: ${formatDuration(pointA)}" else "A: --:--"
                binding.tvPointB.text =
                    if (pointB >= 0) "B: ${formatDuration(pointB)}" else "B: --:--"
                if (binding.switchAbRepeat.isChecked != isAbRepeatEnabled) binding.switchAbRepeat.isChecked =
                    isAbRepeatEnabled
                if (binding.switchLoop.isChecked != p.loopEnabled) binding.switchLoop.isChecked =
                    p.loopEnabled
                binding.tvPitchValue.text = pitchLabel(p.pitchSemitones)
                binding.seekPitch.progress =
                    ((p.pitchSemitones + 6) * 10).roundToInt().coerceIn(0, 120)
                val speedProgress =
                    ((p.playbackSpeed.coerceIn(0.5f, 3.0f) - 0.5f) / 0.05f).roundToInt()
                binding.seekSpeed.progress = speedProgress
                binding.tvSpeedValue.text = speedLabel(p.playbackSpeed)
                val songDur = song?.duration ?: 0L
                val trimStart = p.trimStart
                val trimEnd = if (p.trimEnd > 0) p.trimEnd else songDur
                binding.seekTrimStart.progress = trimStart.toInt()
                binding.seekTrimEnd.progress = trimEnd.toInt()
                updateTrimLabels(trimStart, trimEnd)
                binding.tvTotalTime.text = formatDuration((trimEnd - trimStart).coerceAtLeast(0L))
                syncVolumeSeekBar()
                val isDefault = p.isDefault
                val editAlpha = if (isDefault) 0.6f else 1.0f
                listOf(
                    binding.seekPitch,
                    binding.btnPitchDown,
                    binding.btnPitchUp,
                    binding.btnPitchReset,
                    binding.cardPitch,
                    binding.seekSpeed,
                    binding.btnSpeedDown,
                    binding.btnSpeedUp,
                    binding.btnSpeedReset,
                    binding.cardSpeed,
                    binding.seekTrimStart,
                    binding.seekTrimEnd,
                    binding.btnTrimReset,
                    binding.btnTrimStartMinus,
                    binding.btnTrimStartPlus,
                    binding.btnTrimEndMinus,
                    binding.btnTrimEndPlus,
                    binding.cardTrim,
                    binding.btnSetPointA,
                    binding.btnSetPointB,
                    binding.btnResetAb,
                    binding.switchAbRepeat,
                    binding.switchLoop,
                    binding.cardAbRepeat
                ).forEach {
                    it.isEnabled =
                        !isDefault; if (it is android.view.View && (it.id == R.id.cardPitch || it.id == R.id.cardSpeed || it.id == R.id.cardTrim || it.id == R.id.cardAbRepeat)) it.alpha =
                    editAlpha
                }
                updateProfileSwitchButtons()
            }
        }
        viewModel.skipRegions.observe(this) { regions ->
            updateSkipRegionsUI(regions)
            musicService?.applySkipRegions(regions.filter { it.isEnabled })
            song?.let { s -> binding.skipRegionsOverlay.setRegions(regions, s.duration) }
        }
    }

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun setupVolumeSeekBar() {
        binding.seekVolume.max = maxVolume
        syncVolumeSeekBar()
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
                binding.tvVolumeValue.text =
                    ((progress.toFloat() / maxVolume) * 100).toInt().toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun syncVolumeSeekBar() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekVolume.progress = current
        binding.tvVolumeValue.text =
            ((current.toFloat() / maxVolume) * 100).toInt().toString()
        binding.btnVolumeMute.setImageResource(if (current == 0) R.drawable.ic_volume_off else R.drawable.ic_speaker)
    }

    private val songChangedCallback: (Song) -> Unit = { s ->
        runOnUiThread {
            if (!isDestroyed) {
                songId = s.id
                populate(s)
                binding.seekPlayback.progress = 0
                binding.tvCurrentTime.text = formatDuration(0)
                currentRepeatMode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
                updateRepeatButton()
                updateShuffleButton()
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
                binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                viewModel.setPlaying(playing)
                if (playing) {
                    startProgressUpdates(); startVuAnimation()
                } else {
                    stopProgressUpdates(); stopVuAnimation()
                }
            }
        }
    }

    private fun registerCallbacks() {
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
            service.removeRepeatModeCallback(repeatModeCallback)
            service.removeShuffleCallback(shuffleCallback)
            service.addSongChangedCallback(songChangedCallback)
            service.addPlayStateCallback(playStateCallback)
            service.addRepeatModeCallback(repeatModeCallback)
            service.addShuffleCallback(shuffleCallback)
        }
    }

    private fun syncNow() {
        val svc = musicService ?: return
        binding.btnPlayPause.setImageResource(if (svc.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        val isBypassing = svc.isBypassingProfiles()
        binding.switchPlaybackState.isChecked = !isBypassing
        updateStateLabels(!isBypassing)
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

    private fun populate(s: Song) {
        song = s
        songId = s.id
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = s.albumName.ifBlank { s.folderName }
        binding.ivManualIndicator.visibility =
            if (s.isManuallyEdited) android.view.View.VISIBLE else android.view.View.GONE
        updateFavoriteIcon(s)
        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this).load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_music_note).error(R.drawable.ic_music_note)
                .into(binding.ivAlbumArt)
            Glide.with(this).load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade()).into(binding.ivBackgroundArt)
        } else {
            Glide.with(this).clear(binding.ivAlbumArt)
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
            binding.ivAlbumArt.setColorFilter(ContextCompat.getColor(this, R.color.neon_pink))
            binding.ivBackgroundArt.setImageDrawable(null)
        }
        binding.tvPitchValue.text = pitchLabel(s.pitchSemitones)
        binding.seekPitch.progress = ((s.pitchSemitones + 6) * 10).toInt().coerceIn(0, 120)
        val speedProgress = ((s.playbackSpeed.coerceIn(0.5f, 3.0f) - 0.5f) / 0.05f).toInt()
        binding.seekSpeed.progress = speedProgress
        binding.tvSpeedValue.text = speedLabel(s.playbackSpeed)
        val totalMs =
            if (s.duration > 0) s.duration else musicService?.getDuration()?.toLong() ?: 0L
        if (totalMs > 0) {
            binding.seekTrimStart.max = totalMs.toInt(); binding.seekTrimEnd.max = totalMs.toInt()
        }
        val trimStart = s.trimStart
        val trimEnd = if (s.trimEnd > 0) s.trimEnd else totalMs
        binding.seekTrimStart.progress = trimStart.toInt()
        binding.seekTrimEnd.progress = trimEnd.toInt()
        updateTrimLabels(trimStart, trimEnd)
        binding.tvTotalTime.text = formatDuration((trimEnd - trimStart).coerceAtLeast(0L))
    }

    private fun updateFavoriteIcon(s: Song) {
        binding.btnFavorite.setImageResource(
            if (s.isFavorite) FavoriteIconHelper.filledRes(
                favoriteIconType
            ) else FavoriteIconHelper.outlineRes(favoriteIconType)
        )
        binding.btnFavorite.setColorFilter(
            ContextCompat.getColor(
                this,
                FavoriteIconHelper.colorRes(favoriteIconType)
            )
        )
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        binding.switchPlaybackState.setOnCheckedChangeListener { _, checked ->
            musicService?.setBypassProfiles(!checked)
            viewModel.setOriginalState(!checked)
            updateStateLabels(checked)
        }
        binding.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.btnPrev.setOnClickListener {
            binding.seekPlayback.progress = 0; binding.tvCurrentTime.text =
            formatDuration(0); musicService?.skipPrev()
        }
        binding.switchLoop.setOnCheckedChangeListener { _, checked ->
            val s = song ?: return@setOnCheckedChangeListener
            musicService?.applyLoopToCurrentSong(
                s.trimStart,
                if (s.trimEnd > 0) s.trimEnd else s.duration,
                checked
            )
            viewModel.activeProfile.value?.let { profile ->
                viewModel.updateLoop(
                    profile.id,
                    profile.loopStart,
                    profile.loopEnd,
                    checked
                )
            }
            activityScope.launch {
                repository.updateRepeatModeAndSyncProfile(
                    s.id,
                    if (checked) 1 else 0
                )
            }
        }
        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL -> MusicService.REPEAT_ONE
                MusicService.REPEAT_ONE -> MusicService.REPEAT_AUTO
                else -> MusicService.REPEAT_NONE
            }
            musicService?.setRepeatMode(currentRepeatMode)
            updateRepeatButton()
        }
        binding.btnShuffle.setOnClickListener { musicService?.toggleShuffle(); updateShuffleButton() }
        binding.btnEdit.setOnClickListener { showEditDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
        binding.btnProfiles.setOnClickListener {
            ProfilesFragment().show(
                supportFragmentManager,
                "profiles"
            )
        }
        binding.btnProfileDefault.setOnClickListener { viewModel.switchToDefaultProfile() }
        binding.btnProfilePrev.setOnClickListener { viewModel.switchToPreviousProfile() }
        binding.btnProfileNext.setOnClickListener { viewModel.switchToNextProfile() }
        binding.btnRewind.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tStart = song?.trimStart?.toInt() ?: 0
            svc.seekTo((svc.getPosition() - settingsRepo.getSkipStep() * 1000).coerceAtLeast(tStart))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tEnd = if ((song?.trimEnd ?: 0L) > 0L) song!!.trimEnd.toInt() else svc.getDuration()
            svc.seekTo((svc.getPosition() + settingsRepo.getSkipStep() * 1000).coerceAtMost(tEnd))
        }
        binding.seekPitch.max = 120
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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
        })
        binding.btnPitchReset.setOnClickListener {
            binding.seekPitch.progress = 60
            binding.tvPitchValue.text = pitchLabel(0f)
            musicService?.applyPitchToCurrentSong(0f)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, 0f) }
        }
        binding.btnPitchDown.setOnClickListener {
            val step = (settingsRepo.getPitchStep() * 10).toInt().coerceAtLeast(1)
            val newVal = (binding.seekPitch.progress - step).coerceAtLeast(0)
            binding.seekPitch.progress = newVal
            val semitones = (newVal - 60) / 10.0f
            binding.tvPitchValue.text = pitchLabel(semitones)
            musicService?.applyPitchToCurrentSong(semitones)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
        }
        binding.btnPitchUp.setOnClickListener {
            val step = (settingsRepo.getPitchStep() * 10).toInt().coerceAtLeast(1)
            val newVal = (binding.seekPitch.progress + step).coerceAtMost(120)
            binding.seekPitch.progress = newVal
            val semitones = (newVal - 60) / 10.0f
            binding.tvPitchValue.text = pitchLabel(semitones)
            musicService?.applyPitchToCurrentSong(semitones)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
        }
        binding.seekSpeed.max = 50
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
            val step = settingsRepo.getSpeedStep().coerceAtLeast(1)
            val newVal = (binding.seekSpeed.progress - step).coerceAtLeast(0)
            binding.seekSpeed.progress = newVal
            val speed = progressToSpeed(newVal)
            binding.tvSpeedValue.text = speedLabel(speed)
            musicService?.applySpeedToCurrentSong(speed)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
        }
        binding.btnSpeedUp.setOnClickListener {
            val step = settingsRepo.getSpeedStep().coerceAtLeast(1)
            val newVal = (binding.seekSpeed.progress + step).coerceAtMost(50)
            binding.seekSpeed.progress = newVal
            val speed = progressToSpeed(newVal)
            binding.tvSpeedValue.text = speedLabel(speed)
            musicService?.applySpeedToCurrentSong(speed)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
        }
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val end = binding.seekTrimEnd.progress
                val clamped = p.coerceAtMost((end - 1000).coerceAtLeast(0))
                if (p != clamped) {
                    sb.progress = clamped; return
                }
                updateTrimLabels(clamped.toLong(), end.toLong())
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
            binding.seekTrimStart.progress = 0
            binding.seekTrimEnd.progress = s.duration.toInt()
            updateTrimLabels(0L, s.duration)
            musicService?.applyTrimToCurrentSong(0L, -1L)
            activityScope.launch { repository.updateTrimAndSyncProfile(s.id, 0L, -1L) }
        }
        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val svc = musicService ?: return
                    val s = song ?: return
                    val tStart = s.trimStart
                    val tEnd = if (s.trimEnd > 0L) s.trimEnd else svc.getDuration().toLong()
                    val target = ((progress / 100.0) * (tEnd - tStart).coerceAtLeast(0L)).toLong()
                    svc.seekTo((target + tStart).toInt())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        binding.btnFavorite.setOnClickListener {
            val s = song ?: return@setOnClickListener
            activityScope.launch {
                repository.toggleFavorite(s)
                repository.getSongById(s.id)?.let { fresh ->
                    song = fresh
                    runOnUiThread { updateFavoriteIcon(fresh) }
                }
            }
        }
        binding.btnVolumeUp.setOnClickListener { adjustVolume(true) }
        binding.btnVolumeDown.setOnClickListener { adjustVolume(false) }
        binding.btnVolumeReset.setOnClickListener {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                maxVolume,
                0
            ); syncVolumeSeekBar()
        }
        binding.btnVolumeMute.setOnClickListener {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                lastVolumeBeforeMute =
                    current; audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } else {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (lastVolumeBeforeMute > 0) lastVolumeBeforeMute else maxVolume / 2,
                    0
                )
            }
            syncVolumeSeekBar()
        }
        binding.btnTrimStartMinus.setOnClickListener {
            val newVal =
                (binding.seekTrimStart.progress - (settingsRepo.getTrimStep() * 1000).toInt()).coerceAtLeast(
                    0
                ); binding.seekTrimStart.progress = newVal; updateTrimLabels(
            newVal.toLong(),
            binding.seekTrimEnd.progress.toLong()
        ); saveTrim()
        }
        binding.btnTrimStartPlus.setOnClickListener {
            val newVal =
                (binding.seekTrimStart.progress + (settingsRepo.getTrimStep() * 1000).toInt()).coerceAtMost(
                    binding.seekTrimEnd.progress - 1000
                ); binding.seekTrimStart.progress = newVal; updateTrimLabels(
            newVal.toLong(),
            binding.seekTrimEnd.progress.toLong()
        ); saveTrim()
        }
        binding.btnTrimEndMinus.setOnClickListener {
            val newVal =
                (binding.seekTrimEnd.progress - (settingsRepo.getTrimStep() * 1000).toInt()).coerceAtLeast(
                    binding.seekTrimStart.progress + 1000
                ); binding.seekTrimEnd.progress = newVal; updateTrimLabels(
            binding.seekTrimStart.progress.toLong(),
            newVal.toLong()
        ); saveTrim()
        }
        binding.btnTrimEndPlus.setOnClickListener {
            val newVal =
                (binding.seekTrimEnd.progress + (settingsRepo.getTrimStep() * 1000).toInt()).coerceAtMost(
                    binding.seekTrimEnd.max
                ); binding.seekTrimEnd.progress = newVal; updateTrimLabels(
            binding.seekTrimStart.progress.toLong(),
            newVal.toLong()
        ); saveTrim()
        }
        binding.btnSetPointA.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: return@setOnClickListener; pointA =
            pos; binding.tvPointA.text =
            "A: ${formatDuration(pointA)}"; saveAbRepeat(); if (isAbRepeatEnabled) applyAbRepeat()
        }
        binding.btnSetPointB.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong()
                ?: return@setOnClickListener; if (pos <= pointA) return@setOnClickListener; pointB =
            pos; binding.tvPointB.text =
            "B: ${formatDuration(pointB)}"; saveAbRepeat(); if (isAbRepeatEnabled) applyAbRepeat()
        }
        binding.btnResetAb.setOnClickListener {
            pointA = -1L; pointB = -1L; isAbRepeatEnabled = false; binding.tvPointA.text =
            "A: --:--"; binding.tvPointB.text = "B: --:--"; binding.switchAbRepeat.isChecked =
            false; musicService?.applyAbRepeatToCurrentSong(-1, -1, false); saveAbRepeat()
        }
        binding.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
            isAbRepeatEnabled = checked; if (checked) {
            if (pointA >= 0L && pointA < pointB) applyAbRepeat() else {
                binding.switchAbRepeat.isChecked = false; Toast.makeText(
                    this,
                    "Set A and B points first",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else musicService?.applyAbRepeatToCurrentSong(-1, -1, false); saveAbRepeat()
        }
        binding.btnAddSkipSection.setOnClickListener { showAddSkipSectionDialog() }
        binding.btnResetAllStates.setOnClickListener { showResetAllStatesConfirm() }
    }

    private fun adjustVolume(up: Boolean) {
        val step = maxOf(1, (maxVolume * settingsRepo.getVolumeStep() / 100f).toInt())
        val newProgress =
            (binding.seekVolume.progress + (if (up) step else -step)).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            newProgress,
            0
        )
        syncVolumeSeekBar()
    }

    private fun updateSkipRegionsUI(regions: List<SkipRegion>) {
        binding.layoutSkipSections.removeAllViews()
        binding.tvNoSkipSections.visibility =
            if (regions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        regions.forEach { region ->
            val itemView = layoutInflater.inflate(
                R.layout.item_selection_dialog,
                binding.layoutSkipSections,
                false
            )
            itemView.findViewById<android.widget.TextView>(R.id.tvItemName).apply {
                text =
                    "${formatDuration(region.startMs)} ➔ ${formatDuration(region.endMs)}"; setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                12f
            ); setTextColor(ContextCompat.getColor(this@VuMeterPlayerActivity, R.color.neon_yellow))
            }
            itemView.findViewById<ImageView>(R.id.ivItemIcon).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(
                ContextCompat.getColor(
                    this@VuMeterPlayerActivity,
                    R.color.neon_red
                )
            ); setOnClickListener { viewModel.deleteSkipRegion(region) }
            }
            itemView.setOnClickListener { activityScope.launch { profileRepo.toggleSkipRegion(region) } }
            itemView.alpha = if (region.isEnabled) 1.0f else 0.5f
            binding.layoutSkipSections.addView(itemView)
        }
    }

    private fun showAddSkipSectionDialog() {
        val s = song ?: return
        val pos = musicService?.getPosition()?.toLong() ?: 0L
        MaterialAlertDialogBuilder(this).setTitle("ADD SKIP SECTION")
            .setMessage("Create a 30-second skip section starting at ${formatDuration(pos)}?")
            .setPositiveButton("ADD") { _, _ ->
                viewModel.addSkipRegion(
                    s.id,
                    "",
                    pos,
                    (pos + 30000).coerceAtMost(s.duration)
                )
            }
            .setNeutralButton("CUSTOM") { _, _ -> showCustomSkipDialog() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun showCustomSkipDialog() {
        val s = song ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_skip, null)
        val etStartMin = dialogView.findViewById<EditText>(R.id.etStartMin)
        val etStartSec = dialogView.findViewById<EditText>(R.id.etStartSec)
        val etEndMin = dialogView.findViewById<EditText>(R.id.etEndMin)
        val etEndSec = dialogView.findViewById<EditText>(R.id.etEndSec)
        val curPos = musicService?.getPosition()?.toLong() ?: 0L
        etStartMin.setText(((curPos / 1000) / 60).toString())
        etStartSec.setText(((curPos / 1000) % 60).toString())
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogView.findViewById<android.view.View>(R.id.btnCancel)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            val startMs = ((etStartMin.text.toString().toLongOrNull()
                ?: 0L) * 60 + (etStartSec.text.toString().toLongOrNull() ?: 0L)) * 1000
            val endMs =
                ((etEndMin.text.toString().toLongOrNull() ?: 0L) * 60 + (etEndSec.text.toString()
                    .toLongOrNull() ?: 0L)) * 1000
            if (endMs <= startMs) Toast.makeText(
                this,
                "End time must be after start time",
                Toast.LENGTH_SHORT
            ).show()
            else if (startMs >= s.duration) Toast.makeText(
                this,
                "Start time is beyond song duration",
                Toast.LENGTH_SHORT
            ).show()
            else {
                viewModel.addSkipRegion(
                    s.id,
                    "",
                    startMs,
                    endMs.coerceAtMost(s.duration)
                ); dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showResetAllStatesConfirm() {
        MaterialAlertDialogBuilder(this).setTitle("RESET ALL STATES").setIcon(R.drawable.ic_skull)
            .setMessage("Reset all Pitch, Speed, Trim, Volume, Loops, A-B Repeat, and Skip Sections?")
            .setPositiveButton("RESET EVERYTHING") { _, _ -> resetAllStates() }
            .setNegativeButton("CANCEL", null).show()
    }

    private fun resetAllStates() {
        val s = song ?: return
        activityScope.launch {
            repository.updatePitchAndSyncProfile(
                s.id,
                0f
            ); repository.updateSpeedAndSyncProfile(
            s.id,
            1.0f
        ); repository.updateTrimAndSyncProfile(s.id, 0L, -1L)
            viewModel.activeProfile.value?.let {
                profileRepo.updateProfile(
                    it.copy(
                        pitchSemitones = 0f,
                        playbackSpeed = 1.0f,
                        trimStart = 0L,
                        trimEnd = -1L,
                        abRepeatEnabled = false
                    )
                )
            }
            profileRepo.deleteAllSkipRegions(s.id)
            runOnUiThread {
                pointA = -1L; pointB = -1L; isAbRepeatEnabled = false
                populate(
                    s.copy(
                        pitchSemitones = 0f,
                        playbackSpeed = 1.0f,
                        trimStart = 0L,
                        trimEnd = -1L
                    )
                )
                binding.tvPointA.text = "A: --:--"; binding.tvPointB.text =
                "B: --:--"; binding.switchAbRepeat.isChecked = false
                musicService?.applyPitchToCurrentSong(0f); musicService?.applySpeedToCurrentSong(
                1.0f
            ); musicService?.applyTrimToCurrentSong(
                0L,
                -1L
            ); musicService?.applyAbRepeatToCurrentSong(
                -1,
                -1,
                false
            ); musicService?.setBypassProfiles(false)
                Toast.makeText(this@VuMeterPlayerActivity, "All states reset", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun updateRepeatButton() {
        val color = when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> ContextCompat.getColor(this, R.color.neon_cyan)
            MusicService.REPEAT_ONE -> ContextCompat.getColor(this, R.color.neon_pink)
            MusicService.REPEAT_AUTO -> ContextCompat.getColor(this, R.color.neon_purple)
            else -> ContextCompat.getColor(this, R.color.text_hint)
        }
        binding.btnRepeat.setImageResource(if (currentRepeatMode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        binding.btnRepeat.setColorFilter(color); binding.tvRepeatIndicator.text =
            if (currentRepeatMode == MusicService.REPEAT_AUTO) "R" else ""; binding.tvRepeatIndicator.setTextColor(
            color
        )
        binding.tvRepeatLabel.setTextColor(color); binding.tvRepeatLabel.text = getString(
            when (currentRepeatMode) {
                MusicService.REPEAT_ALL -> R.string.repeat_all; MusicService.REPEAT_ONE -> R.string.repeat_one; MusicService.REPEAT_AUTO -> R.string.repeat_auto; else -> R.string.repeat_none
            }
        )
        binding.tvRepeatLabel.visibility = android.view.View.VISIBLE
    }

    private fun updateShuffleButton() {
        val shuffleOn = musicService?.isShuffleEnabled() ?: false
        binding.btnShuffle.setImageResource(if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
        binding.btnShuffle.setColorFilter(
            ContextCompat.getColor(
                this,
                if (shuffleOn) R.color.neon_cyan else R.color.text_hint
            )
        )
    }

    private fun showDeleteConfirmDialog() {
        val s = song ?: return
        MaterialAlertDialogBuilder(this).setTitle("DELETE SONG")
            .setMessage("Delete \"${s.title}\" from library?").setPositiveButton("DELETE") { _, _ ->
            activityScope.launch { repository.deleteSong(s); runOnUiThread { musicService?.skipNext(); finish() } }
        }.setNegativeButton("CANCEL", null).show()
    }

    private fun showEditDialog() {
        val s = song ?: return
        val dlgBinding = DialogEditSongBinding.inflate(layoutInflater)
        editDialogBinding = dlgBinding
        dlgBinding.etTitle.setText(s.title); dlgBinding.etArtist.setText(s.artist); dlgBinding.etAlbum.setText(
            s.albumName
        )
        selectedArtUri =
            if (s.albumArtUrl.startsWith("content://") || s.albumArtUrl.startsWith("file://")) Uri.parse(
                s.albumArtUrl
            ) else null
        if (s.albumArtUrl.isNotBlank()) Glide.with(this).load(s.albumArtUrl)
            .placeholder(R.drawable.ic_music_note).into(dlgBinding.ivEditAlbumArt)
        dlgBinding.btnChangeArt.setOnClickListener { pickArtLauncher.launch("image/*") }
        val dialog = MaterialAlertDialogBuilder(this).setView(dlgBinding.root).create()
        dlgBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnSave.setOnClickListener {
            val title = dlgBinding.etTitle.text.toString().trim()
            if (title.isBlank()) {
                Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT)
                    .show(); return@setOnClickListener
            }
            activityScope.launch {
                repository.updateSongDetailsManual(
                    s.id,
                    title,
                    dlgBinding.etArtist.text.toString().trim(),
                    dlgBinding.etAlbum.text.toString().trim(),
                    selectedArtUri?.toString() ?: s.albumArtUrl
                )
                runOnUiThread { dialog.dismiss() }
            }
        }
        dialog.show(); dialog.setOnDismissListener { editDialogBinding = null }
    }

    private fun saveTrim() {
        val start = binding.seekTrimStart.progress.toLong()
        val end = binding.seekTrimEnd.progress.toLong()
        song = song?.copy(trimStart = start, trimEnd = end)
        musicService?.applyTrimToCurrentSong(start, end)
        activityScope.launch { repository.updateTrimAndSyncProfile(songId, start, end) }
    }

    private fun applyAbRepeat() {
        if (pointA >= 0 && pointB > pointA) musicService?.applyAbRepeatToCurrentSong(
            pointA,
            pointB,
            true
        )
    }

    private fun saveAbRepeat() {
        viewModel.activeProfile.value?.let {
            viewModel.updateAbRepeat(
                it.id,
                pointA,
                pointB,
                isAbRepeatEnabled
            )
        }
    }

    private fun updateProfileSwitchButtons() {
        val profiles = viewModel.profiles.value ?: return
        val active = viewModel.activeProfile.value ?: return
        val idx = profiles.indexOfFirst { it.id == active.id }
        if (idx != -1) {
            val hasPrev = idx > 0
            val hasNext = idx < profiles.size - 1
            val hasMult = profiles.size > 1
            binding.btnProfilePrev.isEnabled = hasPrev; binding.btnProfileNext.isEnabled =
                hasNext; binding.btnProfileDefault.isEnabled = hasMult
            binding.btnProfilePrev.alpha =
                if (hasPrev) 1.0f else 0.5f; binding.btnProfileNext.alpha =
                if (hasNext) 1.0f else 0.5f; binding.btnProfileDefault.alpha =
                if (hasMult) 1.0f else 0.5f
        }
    }

    private fun updateStateLabels(isUpdated: Boolean) {
        binding.tvStateUpdated.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUpdated) R.color.neon_cyan else R.color.text_hint
            )
        )
        binding.tvStateOriginal.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUpdated) R.color.text_hint else R.color.neon_pink
            )
        )
    }

    private fun updateTrimLabels(start: Long, end: Long) {
        binding.tvTrimStart.text = getString(R.string.trim_start_label, formatDuration(start))
        binding.tvTrimEnd.text = getString(R.string.trim_end_label, formatDuration(end))
    }

    private fun pitchLabel(s: Float) = if (s > 0) "+%.1f".format(s) else "%.1f".format(s)
    private fun progressToSpeed(progress: Int): Float =
        (0.5f + progress * 0.05f).coerceIn(0.5f, 3.0f)

    private fun speedLabel(speed: Float): String = "%.2f".format(speed).trimEnd('0').trimEnd('.')

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService
                val s = song
                if (svc != null && s != null) {
                    val fullDur = svc.getDuration().toLong()
                    val tStart = s.trimStart
                    val tEnd = if (s.trimEnd > 0L) s.trimEnd else fullDur
                    val effDur = (tEnd - tStart).coerceAtLeast(0L)
                    val absPos = svc.getPosition().toLong()
                    val relPos = (absPos - tStart).coerceAtLeast(0L)
                    if (fullDur > 0) {
                        binding.seekPlayback.progress =
                            if (effDur > 0) ((relPos.toFloat() / effDur) * 100).toInt()
                                .coerceIn(0, 100) else 0
                        binding.tvCurrentTime.text =
                            formatDuration(relPos); binding.tvTotalTime.text =
                            formatDuration(if (effDur > 0) effDur else fullDur)
                        viewModel.onLyricsPositionChanged(absPos)
                    }
                }
                progressHandler.postDelayed(this, 200)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }; progressRunnable = null
    }

    private fun startVuAnimation() {
        stopVuAnimation()
        vuRunnable = object : Runnable {
            override fun run() {
                if (musicService?.isPlaying() == true) {
                    binding.vuMeterLeft.progress = (Random.nextInt(40, 95))
                    binding.vuMeterRight.progress = (Random.nextInt(40, 95))
                } else {
                    binding.vuMeterLeft.progress = binding.vuMeterLeft.progress.coerceAtLeast(5) - 5
                    binding.vuMeterRight.progress =
                        binding.vuMeterRight.progress.coerceAtLeast(5) - 5
                }
                progressHandler.postDelayed(this, 100)
            }
        }
        progressHandler.post(vuRunnable!!)
    }

    private fun stopVuAnimation() {
        vuRunnable?.let { progressHandler.removeCallbacks(it) }; vuRunnable = null
    }
}
