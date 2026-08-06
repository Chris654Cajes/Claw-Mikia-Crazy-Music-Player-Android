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
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.data.repository.ProfileRepository
import com.mochimochi.clawmikiacrazy.data.repository.SongRepository
import com.mochimochi.clawmikiacrazy.databinding.ActivityNowPlayingCircularBinding
import com.mochimochi.clawmikiacrazy.databinding.DialogEditSongBinding
import com.mochimochi.clawmikiacrazy.service.MusicService
import com.mochimochi.clawmikiacrazy.ui.fragments.ProfilesFragment
import com.mochimochi.clawmikiacrazy.ui.viewmodels.NowPlayingViewModel
import com.mochimochi.clawmikiacrazy.ui.views.CircularSeekBar
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class CircularPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingCircularBinding
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SongRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository
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
    private var favoriteIconType: String = FavoriteIconHelper.ALL_TYPES[0]

    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

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

    private val volumeObserver =
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncVolumeSeekBar()
            }
        }

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
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        fun start(ctx: Context, songId: Long) = ctx.startActivity(
            Intent(
                ctx,
                CircularPlayerActivity::class.java
            ).putExtra(EXTRA_SONG_ID, songId)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNowPlayingCircularBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        repository = SongRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        settingsRepo.favoriteIconLive.observe(this) { iconType ->
            favoriteIconType = iconType
            song?.let { s -> updateFavoriteIcon(s) }
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
                    viewModel.setSong(s, false)
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
            val isBypassing = it.isBypassingProfiles()
            binding.cardStateToggle.switchPlaybackState.isChecked = !isBypassing
            updateStateLabels(!isBypassing)
        }
        syncVolumeSeekBar()
        updateProfileSwitchButtons()
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
        contentResolver.unregisterContentObserver(volumeObserver)
    }

    override fun onDestroy() {
        stopProgressUpdates()
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
        viewModel.profiles.observe(this) { updateProfileSwitchButtons() }
        viewModel.songAnalysis.observe(this) { analysis ->
            if (analysis != null) {
                binding.tvBpm.text = getString(R.string.bpm_format, analysis.bpm.toInt())
                binding.tvKey.text = getString(R.string.key_format, analysis.key)
                binding.tvBpm.visibility = View.VISIBLE
                binding.tvKey.visibility = View.VISIBLE
            } else {
                binding.tvBpm.visibility = View.GONE
                binding.tvKey.visibility = View.GONE
            }
        }
        viewModel.activeProfile.observe(this) { profile ->
            profile?.let { p ->
                musicService?.applyProfile(p)
                pointA = p.abRepeatA
                pointB = p.abRepeatB
                isAbRepeatEnabled = p.abRepeatEnabled
                binding.cardAbRepeat.tvPointA.text =
                    if (pointA >= 0) "A: ${formatDuration(pointA)}" else "A: --:--"
                binding.cardAbRepeat.tvPointB.text =
                    if (pointB >= 0) "B: ${formatDuration(pointB)}" else "B: --:--"
                if (binding.cardAbRepeat.switchAbRepeat.isChecked != isAbRepeatEnabled) {
                    binding.cardAbRepeat.switchAbRepeat.isChecked = isAbRepeatEnabled
                }
                if (binding.cardAbRepeat.switchLoop.isChecked != p.loopEnabled) {
                    binding.cardAbRepeat.switchLoop.isChecked = p.loopEnabled
                }
                binding.cardPitch.tvPitchValue.text = pitchLabel(p.pitchSemitones)
                binding.cardPitch.seekPitch.progress =
                    ((p.pitchSemitones + 6) * 10).roundToInt().coerceIn(0, 120)
                val speedProgress =
                    ((p.playbackSpeed.coerceIn(0.5f, 3.0f) - 0.5f) / 0.05f).roundToInt()
                binding.cardSpeed.seekSpeed.progress = speedProgress
                binding.cardSpeed.tvSpeedValue.text = speedLabel(p.playbackSpeed)
                val songDur = song?.duration ?: 0L
                val trimStart = p.trimStart
                val trimEnd = if (p.trimEnd > 0) p.trimEnd else songDur
                binding.cardTrim.seekTrimStart.progress = trimStart.toInt()
                binding.cardTrim.seekTrimEnd.progress = trimEnd.toInt()
                updateTrimLabels()
                val effectiveDur = (trimEnd - trimStart).coerceAtLeast(0L)
                binding.tvTotalTime.text = formatDuration(effectiveDur)
                syncVolumeSeekBar()

                val isDefault = p.isDefault
                val editAlpha = if (isDefault) 0.6f else 1.0f
                binding.cardPitch.seekPitch.isEnabled = !isDefault
                binding.cardPitch.btnPitchDown.isEnabled = !isDefault
                binding.cardPitch.btnPitchUp.isEnabled = !isDefault
                binding.cardPitch.btnPitchReset.isEnabled = !isDefault
                binding.cardPitch.root.alpha = editAlpha
                binding.cardSpeed.seekSpeed.isEnabled = !isDefault
                binding.cardSpeed.btnSpeedDown.isEnabled = !isDefault
                binding.cardSpeed.btnSpeedUp.isEnabled = !isDefault
                binding.cardSpeed.btnSpeedReset.isEnabled = !isDefault
                binding.cardSpeed.root.alpha = editAlpha
                binding.cardTrim.seekTrimStart.isEnabled = !isDefault
                binding.cardTrim.seekTrimEnd.isEnabled = !isDefault
                binding.cardTrim.btnTrimReset.isEnabled = !isDefault
                binding.cardTrim.btnTrimStartMinus.isEnabled = !isDefault
                binding.cardTrim.btnTrimStartPlus.isEnabled = !isDefault
                binding.cardTrim.btnTrimEndMinus.isEnabled = !isDefault
                binding.cardTrim.btnTrimEndPlus.isEnabled = !isDefault
                binding.cardTrim.root.alpha = editAlpha
                binding.cardAbRepeat.btnSetPointA.isEnabled = !isDefault
                binding.cardAbRepeat.btnSetPointB.isEnabled = !isDefault
                binding.cardAbRepeat.btnResetAb.isEnabled = !isDefault
                binding.cardAbRepeat.switchAbRepeat.isEnabled = !isDefault
                binding.cardAbRepeat.switchLoop.isEnabled = !isDefault
                binding.cardAbRepeat.root.alpha = editAlpha
                binding.cardSkipSections.btnAddSkipSection.isEnabled = true
                binding.cardSkipSections.root.alpha = 1.0f
                updateProfileSwitchButtons()
            }
        }
        viewModel.skipRegions.observe(this) { regions ->
            updateSkipRegionsUI(regions)
            musicService?.applySkipRegions(regions.filter { it.isEnabled })
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }

        binding.cardStateToggle.switchPlaybackState.setOnCheckedChangeListener { _, checked ->
            musicService?.setBypassProfiles(!checked)
            viewModel.setOriginalState(!checked)
            updateStateLabels(checked)
        }

        binding.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.btnPrev.setOnClickListener {
            binding.seekPlayback.progress = 0
            binding.tvCurrentTime.text = formatDuration(0)
            musicService?.skipPrev()
        }

        binding.btnShuffle.setOnClickListener {
            musicService?.toggleShuffle()
            updateShuffleButton()
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

        binding.btnProfiles.setOnClickListener {
            ProfilesFragment().show(supportFragmentManager, "profiles")
        }
        binding.btnEdit.setOnClickListener { showEditDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }

        binding.cardProfiles.btnProfilePrev.setOnClickListener { viewModel.switchToPreviousProfile() }
        binding.cardProfiles.btnProfileNext.setOnClickListener { viewModel.switchToNextProfile() }
        binding.cardProfiles.btnProfileDefault.setOnClickListener { viewModel.switchToDefaultProfile() }

        binding.btnRewind.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tStart = song?.trimStart?.toInt() ?: 0
            val skipMs = settingsRepo.getSkipStep() * 1000
            svc.seekTo((svc.getPosition() - skipMs).coerceAtLeast(tStart))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            val tEnd = if ((song?.trimEnd ?: 0L) > 0L) song!!.trimEnd.toInt() else svc.getDuration()
            val skipMs = settingsRepo.getSkipStep() * 1000
            svc.seekTo((svc.getPosition() + skipMs).coerceAtMost(tEnd))
        }

        binding.cardVolume.btnVolumeDown.setOnClickListener { adjustVolume(-1) }
        binding.cardVolume.btnVolumeUp.setOnClickListener { adjustVolume(1) }
        binding.cardVolume.btnVolumeMute.setOnClickListener { toggleMute() }
        binding.cardVolume.btnVolumeReset.setOnClickListener { resetVolume() }

        binding.cardPitch.seekPitch.max = 120
        binding.cardPitch.seekPitch.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val semitones = (p - 60) / 10.0f
                binding.cardPitch.tvPitchValue.text = pitchLabel(semitones)
                if (fromUser) musicService?.applyPitchToCurrentSong(semitones)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val semitones = (sb.progress - 60) / 10.0f
                activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
            }
        })
        binding.cardPitch.btnPitchDown.setOnClickListener { adjustPitch(-1) }
        binding.cardPitch.btnPitchUp.setOnClickListener { adjustPitch(1) }
        binding.cardPitch.btnPitchReset.setOnClickListener {
            binding.cardPitch.seekPitch.progress = 60
            binding.cardPitch.tvPitchValue.text = pitchLabel(0f)
            musicService?.applyPitchToCurrentSong(0f)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, 0f) }
        }

        binding.cardSpeed.seekSpeed.max = 50
        binding.cardSpeed.seekSpeed.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val speed = 0.5f + p * 0.05f
                binding.cardSpeed.tvSpeedValue.text = speedLabel(speed)
                if (fromUser) musicService?.applySpeedToCurrentSong(speed)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val speed = 0.5f + sb.progress * 0.05f
                activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
            }
        })
        binding.cardSpeed.btnSpeedDown.setOnClickListener { adjustSpeed(-1) }
        binding.cardSpeed.btnSpeedUp.setOnClickListener { adjustSpeed(1) }
        binding.cardSpeed.btnSpeedReset.setOnClickListener {
            binding.cardSpeed.seekSpeed.progress = 10
            binding.cardSpeed.tvSpeedValue.text = speedLabel(1.0f)
            musicService?.applySpeedToCurrentSong(1.0f)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, 1.0f) }
        }

        binding.cardTrim.seekTrimStart.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val end = binding.cardTrim.seekTrimEnd.progress
                val clamped = p.coerceAtMost((end - 1000).coerceAtLeast(0))
                if (p != clamped) {
                    sb.progress = clamped; return
                }
                updateTrimLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
        })
        binding.cardTrim.seekTrimEnd.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                updateTrimLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
        })
        binding.cardTrim.btnTrimStartMinus.setOnClickListener { adjustTrim(true, -1) }
        binding.cardTrim.btnTrimStartPlus.setOnClickListener { adjustTrim(true, 1) }
        binding.cardTrim.btnTrimEndMinus.setOnClickListener { adjustTrim(false, -1) }
        binding.cardTrim.btnTrimEndPlus.setOnClickListener { adjustTrim(false, 1) }
        binding.cardTrim.btnTrimReset.setOnClickListener {
            val s = song ?: return@setOnClickListener
            binding.cardTrim.seekTrimStart.progress = 0
            binding.cardTrim.seekTrimEnd.progress = s.duration.toInt()
            updateTrimLabels()
            musicService?.applyTrimToCurrentSong(0L, -1L)
            activityScope.launch { repository.updateTrimAndSyncProfile(s.id, 0L, -1L) }
        }

        binding.cardAbRepeat.btnSetPointA.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: return@setOnClickListener
            pointA = pos
            binding.cardAbRepeat.tvPointA.text = "A: ${formatDuration(pointA)}"
            saveAbRepeat()
            if (isAbRepeatEnabled) applyAbRepeat()
        }
        binding.cardAbRepeat.btnSetPointB.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: return@setOnClickListener
            if (pos <= pointA) return@setOnClickListener
            pointB = pos
            binding.cardAbRepeat.tvPointB.text = "B: ${formatDuration(pointB)}"
            saveAbRepeat()
            if (isAbRepeatEnabled) applyAbRepeat()
        }
        binding.cardAbRepeat.btnResetAb.setOnClickListener {
            pointA = -1L; pointB = -1L; isAbRepeatEnabled = false
            binding.cardAbRepeat.tvPointA.text = "A: --:--"
            binding.cardAbRepeat.tvPointB.text = "B: --:--"
            binding.cardAbRepeat.switchAbRepeat.isChecked = false
            musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            saveAbRepeat()
        }
        binding.cardAbRepeat.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
            isAbRepeatEnabled = checked
            if (checked) {
                if (pointA >= 0L && pointA < pointB) applyAbRepeat()
                else {
                    binding.cardAbRepeat.switchAbRepeat.isChecked = false
                    Toast.makeText(this, "Set A and B points first", Toast.LENGTH_SHORT).show()
                }
            } else musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            saveAbRepeat()
        }
        binding.cardAbRepeat.switchLoop.setOnCheckedChangeListener { _, checked ->
            val s = song ?: return@setOnCheckedChangeListener
            musicService?.applyLoopToCurrentSong(
                s.trimStart,
                if (s.trimEnd > 0) s.trimEnd else s.duration,
                checked
            )
            viewModel.activeProfile.value?.let { profile ->
                viewModel.updateLoop(profile.id, profile.loopStart, profile.loopEnd, checked)
            }
            activityScope.launch {
                repository.updateRepeatModeAndSyncProfile(s.id, if (checked) 1 else 0)
            }
        }

        binding.btnResetAllStates.setOnClickListener { showResetAllStatesConfirm() }
        binding.cardSkipSections.btnAddSkipSection.setOnClickListener { showAddSkipSectionDialog() }

        binding.seekPlaybackCircular.setOnSeekBarChangeListener(object :
            CircularSeekBar.OnCircularSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: CircularSeekBar,
                progress: Float,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    val svc = musicService ?: return
                    val s = song ?: return
                    val fullDur = svc.getDuration().toLong()
                    val tStart = s.trimStart
                    val tEnd = if (s.trimEnd > 0) s.trimEnd else fullDur
                    val eff = (tEnd - tStart).coerceAtLeast(0L)
                    musicService?.seekTo((tStart + (progress / 100f * eff)).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: CircularSeekBar) {}
            override fun onStopTrackingTouch(seekBar: CircularSeekBar) {}
        })

        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val svc = musicService ?: return
                    val s = song ?: return
                    val fullDur = svc.getDuration().toLong()
                    val tStart = s.trimStart
                    val tEnd = if (s.trimEnd > 0) s.trimEnd else fullDur
                    val eff = (tEnd - tStart).coerceAtLeast(0L)
                    musicService?.seekTo((tStart + (progress / 100.0 * eff)).toInt())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupVolumeSeekBar() {
        binding.cardVolume.seekVolume.max = maxVolume
        syncVolumeSeekBar()
        binding.cardVolume.seekVolume.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                }
                val pct = ((p.toFloat() / maxVolume) * 100).toInt()
                binding.cardVolume.tvVolumeValue.text = pct.toString()
                binding.cardVolume.ivVolumeIcon.setImageResource(
                    if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
                        R.drawable.ic_volume_off else R.drawable.ic_volume
                )
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun populate(s: Song) {
        song = s
        songId = s.id
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = s.albumName.ifBlank { s.folderName }
        binding.ivManualIndicator.visibility = if (s.isManuallyEdited) View.VISIBLE else View.GONE

        updateFavoriteIcon(s)

        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this).load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_music_note).error(R.drawable.ic_music_note)
                .into(binding.ivAlbumArt)
        } else {
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
        }

        binding.cardPitch.seekPitch.progress =
            ((s.pitchSemitones + 6) * 10).toInt().coerceIn(0, 120)
        binding.cardPitch.tvPitchValue.text = pitchLabel(s.pitchSemitones)

        val speedProgress = ((s.playbackSpeed.coerceIn(0.5f, 3.0f) - 0.5f) / 0.05f).toInt()
        binding.cardSpeed.seekSpeed.progress = speedProgress
        binding.cardSpeed.tvSpeedValue.text = speedLabel(s.playbackSpeed)

        val totalMs =
            if (s.duration > 0) s.duration else musicService?.getDuration()?.toLong() ?: 0L
        if (totalMs > 0) {
            binding.cardTrim.seekTrimStart.max = totalMs.toInt()
            binding.cardTrim.seekTrimEnd.max = totalMs.toInt()
        }
        val trimStart = s.trimStart
        val trimEnd = if (s.trimEnd > 0) s.trimEnd else totalMs
        binding.cardTrim.seekTrimStart.progress = trimStart.toInt()
        binding.cardTrim.seekTrimEnd.progress = trimEnd.toInt()
        updateTrimLabels()
        val effectiveDur = (trimEnd - trimStart).coerceAtLeast(0L)
        binding.tvTotalTime.text = formatDuration(effectiveDur)
    }

    private fun updateFavoriteIcon(s: Song) {
        binding.btnFavorite.setImageResource(
            if (s.isFavorite) FavoriteIconHelper.filledRes(favoriteIconType)
            else FavoriteIconHelper.outlineRes(favoriteIconType)
        )
        binding.btnFavorite.setColorFilter(
            ContextCompat.getColor(this, FavoriteIconHelper.colorRes(favoriteIconType))
        )
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
                if (playing) startProgressUpdates() else stopProgressUpdates()
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
        binding.cardStateToggle.switchPlaybackState.isChecked = !isBypassing
        updateStateLabels(!isBypassing)
        val cur = svc.getCurrentSong() ?: return
        songId = cur.id
        populate(cur)
        updateShuffleButton()
        updateRepeatButton()
        activityScope.launch {
            repository.getSongById(cur.id)?.let { fresh ->
                populate(fresh)
                viewModel.setSong(fresh, svc.isPlaying())
            }
        }
    }

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
                        val pct = if (effDur > 0) ((relPos.toFloat() / effDur) * 100).toInt()
                            .coerceIn(0, 100) else 0
                        binding.seekPlaybackCircular.setProgress(pct.toFloat())
                        binding.seekPlayback.progress = pct
                        binding.tvCurrentTime.text = formatDuration(relPos)
                        binding.tvTotalTime.text = formatDuration(effDur)
                        viewModel.onLyricsPositionChanged(absPos)
                    }
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

    private fun toggleFavorite() {
        val s = song ?: return
        activityScope.launch {
            repository.toggleFavorite(s)
            repository.getSongById(s.id)?.let { fresh ->
                song = fresh
                runOnUiThread { updateFavoriteIcon(fresh) }
            }
        }
    }

    private fun updateShuffleButton() {
        val on = musicService?.isShuffleEnabled() ?: false
        binding.btnShuffle.setImageResource(if (on) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
        binding.btnShuffle.setColorFilter(
            ContextCompat.getColor(this, if (on) R.color.neon_cyan else R.color.text_hint)
        )
    }

    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                val color = ContextCompat.getColor(this, R.color.neon_cyan)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = getString(R.string.repeat_all)
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }
            MusicService.REPEAT_ONE -> {
                val color = ContextCompat.getColor(this, R.color.neon_pink)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = getString(R.string.repeat_one)
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }
            MusicService.REPEAT_AUTO -> {
                val color = ContextCompat.getColor(this, R.color.neon_purple)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = "R"
                binding.tvRepeatIndicator.setTextColor(color)
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = getString(R.string.repeat_auto)
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }
            else -> {
                val color = ContextCompat.getColor(this, R.color.text_hint)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = getString(R.string.repeat_none)
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun updateStateLabels(isUpdated: Boolean) {
        binding.cardStateToggle.tvStateUpdated.setTextColor(
            ContextCompat.getColor(this, if (isUpdated) R.color.neon_cyan else R.color.text_hint)
        )
        binding.cardStateToggle.tvStateOriginal.setTextColor(
            ContextCompat.getColor(this, if (isUpdated) R.color.text_hint else R.color.neon_pink)
        )
    }

    private fun updateProfileSwitchButtons() {
        val list = viewModel.profiles.value ?: return
        val active = viewModel.activeProfile.value ?: return
        val idx = list.indexOfFirst { it.id == active.id }
        if (idx != -1) {
            val hasPrev = idx > 0
            val hasNext = idx < list.size - 1
            val hasMulti = list.size > 1
            binding.cardProfiles.btnProfilePrev.isEnabled = hasPrev
            binding.cardProfiles.btnProfileNext.isEnabled = hasNext
            binding.cardProfiles.btnProfileDefault.isEnabled = hasMulti
            binding.cardProfiles.btnProfilePrev.alpha = if (hasPrev) 1f else 0.5f
            binding.cardProfiles.btnProfileNext.alpha = if (hasNext) 1f else 0.5f
            binding.cardProfiles.btnProfileDefault.alpha = if (hasMulti) 1f else 0.5f
        }
    }

    private fun pitchLabel(s: Float) = if (s > 0) "+%.1f".format(s) else "%.1f".format(s)
    private fun speedLabel(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.')
    private fun updateTrimLabels() {
        binding.cardTrim.tvTrimStart.text =
            "Start: ${formatDuration(binding.cardTrim.seekTrimStart.progress.toLong())}"
        binding.cardTrim.tvTrimEnd.text =
            "End: ${formatDuration(binding.cardTrim.seekTrimEnd.progress.toLong())}"
    }

    private fun adjustVolume(delta: Int) {
        val step = maxOf(1, (maxVolume * settingsRepo.getVolumeStep() / 100f).toInt())
        val current = binding.cardVolume.seekVolume.progress
        val newProgress = (current + delta * step).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newProgress, 0)
        syncVolumeSeekBar()
    }

    private fun toggleMute() {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur > 0) {
            lastVolumeBeforeMute = cur
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (lastVolumeBeforeMute > 0) lastVolumeBeforeMute else maxVolume / 2,
                0
            )
        }
        syncVolumeSeekBar()
    }

    private fun resetVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
        syncVolumeSeekBar()
    }

    private fun syncVolumeSeekBar() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.cardVolume.seekVolume.progress = current
        val pct = ((current.toFloat() / maxVolume) * 100).toInt()
        binding.cardVolume.tvVolumeValue.text = pct.toString()
        binding.cardVolume.ivVolumeIcon.setImageResource(
            if (current == 0) R.drawable.ic_volume_off else R.drawable.ic_volume
        )
    }

    private fun adjustPitch(delta: Int) {
        val step = (settingsRepo.getPitchStep() * 10).toInt().coerceAtLeast(1)
        val cur = binding.cardPitch.seekPitch.progress
        binding.cardPitch.seekPitch.progress = (cur + delta * step).coerceIn(0, 120)
        val semitones = (binding.cardPitch.seekPitch.progress - 60) / 10.0f
        musicService?.applyPitchToCurrentSong(semitones)
        activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
    }

    private fun adjustSpeed(delta: Int) {
        val step = settingsRepo.getSpeedStep().coerceAtLeast(1)
        val cur = binding.cardSpeed.seekSpeed.progress
        binding.cardSpeed.seekSpeed.progress = (cur + delta * step).coerceIn(0, 50)
        val speed = 0.5f + binding.cardSpeed.seekSpeed.progress * 0.05f
        musicService?.applySpeedToCurrentSong(speed)
        activityScope.launch { repository.updateSpeedAndSyncProfile(songId, speed) }
    }

    private fun adjustTrim(start: Boolean, delta: Int) {
        val step = (settingsRepo.getTrimStep() * 1000).toInt().coerceAtLeast(100)
        if (start) {
            val cur = binding.cardTrim.seekTrimStart.progress
            val endVal = binding.cardTrim.seekTrimEnd.progress
            binding.cardTrim.seekTrimStart.progress =
                (cur + delta * step).coerceIn(0, (endVal - 1000).coerceAtLeast(0))
        } else {
            val cur = binding.cardTrim.seekTrimEnd.progress
            val startVal = binding.cardTrim.seekTrimStart.progress
            binding.cardTrim.seekTrimEnd.progress = (cur + delta * step).coerceIn(
                startVal + 1000,
                binding.cardTrim.seekTrimEnd.max
            )
        }
        updateTrimLabels()
        saveTrim()
    }

    private fun saveTrim() {
        val start = binding.cardTrim.seekTrimStart.progress.toLong()
        val end = binding.cardTrim.seekTrimEnd.progress.toLong()
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
        viewModel.activeProfile.value?.let { p ->
            viewModel.updateAbRepeat(p.id, pointA, pointB, isAbRepeatEnabled)
        }
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
            repository.updatePitchAndSyncProfile(s.id, 0f)
            repository.updateSpeedAndSyncProfile(s.id, 1.0f)
            repository.updateTrimAndSyncProfile(s.id, 0L, -1L)
            viewModel.activeProfile.value?.let { p ->
                profileRepo.updateProfile(
                    p.copy(
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
                binding.cardAbRepeat.tvPointA.text = "A: --:--"
                binding.cardAbRepeat.tvPointB.text = "B: --:--"
                binding.cardAbRepeat.switchAbRepeat.isChecked = false
                binding.cardStateToggle.switchPlaybackState.isChecked = true
                updateStateLabels(true)
                musicService?.applyPitchToCurrentSong(0f)
                musicService?.applySpeedToCurrentSong(1.0f)
                musicService?.applyTrimToCurrentSong(0L, -1L)
                musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
                musicService?.setBypassProfiles(false)
            }
        }
    }

    private fun showEditDialog() {
        val s = song ?: return
        val dlgBinding = DialogEditSongBinding.inflate(layoutInflater)
        editDialogBinding = dlgBinding
        dlgBinding.etTitle.setText(s.title)
        dlgBinding.etArtist.setText(s.artist)
        dlgBinding.etAlbum.setText(s.albumName)
        if (s.albumArtUrl.isNotBlank()) Glide.with(this).load(s.albumArtUrl)
            .placeholder(R.drawable.ic_music_note).into(dlgBinding.ivEditAlbumArt)
        dlgBinding.btnChangeArt.setOnClickListener { pickArtLauncher.launch("image/*") }
        val dialog = MaterialAlertDialogBuilder(this).setView(dlgBinding.root).create()
        dlgBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnSave.setOnClickListener {
            val title = dlgBinding.etTitle.text.toString().trim()
            if (title.isBlank()) return@setOnClickListener
            val artUrl = selectedArtUri?.toString() ?: s.albumArtUrl
            activityScope.launch {
                repository.updateSongDetailsManual(
                    s.id, title,
                    dlgBinding.etArtist.text.toString().trim(),
                    dlgBinding.etAlbum.text.toString().trim(),
                    artUrl
                )
                runOnUiThread {
                    dialog.dismiss()
                    activityScope.launch {
                        repository.getSongById(s.id)?.let { fresh ->
                            song = fresh
                            populate(fresh)
                        }
                    }
                }
            }
        }
        dialog.show()
        dialog.setOnDismissListener { editDialogBinding = null }
    }

    private fun showDeleteConfirmDialog() {
        val s = song ?: return
        MaterialAlertDialogBuilder(this).setTitle("DELETE SONG")
            .setMessage("Delete \"${s.title}\" from library?")
            .setPositiveButton("DELETE") { _, _ ->
                activityScope.launch { repository.deleteSong(s); runOnUiThread { musicService?.skipNext(); finish() } }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showAddSkipSectionDialog() {
        val s = song ?: return
        val pos = musicService?.getPosition()?.toLong() ?: 0L
        MaterialAlertDialogBuilder(this).setTitle("ADD SKIP SECTION")
            .setMessage("Create a 30-second skip section starting at ${formatDuration(pos)}?")
            .setPositiveButton("ADD") { _, _ ->
                viewModel.addSkipRegion(s.id, "", pos, (pos + 30000).coerceAtMost(s.duration))
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
        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnSave).setOnClickListener {
            val startMs = ((etStartMin.text.toString().toLongOrNull() ?: 0L) * 60 +
                    (etStartSec.text.toString().toLongOrNull() ?: 0L)) * 1000
            val endMs = ((etEndMin.text.toString().toLongOrNull() ?: 0L) * 60 +
                    (etEndSec.text.toString().toLongOrNull() ?: 0L)) * 1000
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
                viewModel.addSkipRegion(s.id, "", startMs, endMs.coerceAtMost(s.duration))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateSkipRegionsUI(regions: List<SkipRegion>) {
        binding.cardSkipSections.layoutSkipSections.removeAllViews()
        binding.cardSkipSections.tvNoSkipSections.visibility =
            if (regions.isEmpty()) View.VISIBLE else View.GONE
        regions.forEach { region ->
            val itemView = layoutInflater.inflate(
                R.layout.item_selection_dialog,
                binding.cardSkipSections.layoutSkipSections,
                false
            )
            itemView.findViewById<android.widget.TextView>(R.id.tvItemName).apply {
                text = "${formatDuration(region.startMs)} ➔ ${formatDuration(region.endMs)}"
                setTextColor(
                    ContextCompat.getColor(
                        this@CircularPlayerActivity,
                        R.color.neon_yellow
                    )
                )
            }
            itemView.findViewById<ImageView>(R.id.ivItemIcon).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(
                    ContextCompat.getColor(
                        this@CircularPlayerActivity,
                        R.color.neon_red
                    )
                )
                setOnClickListener { viewModel.deleteSkipRegion(region) }
            }
            itemView.setOnClickListener { activityScope.launch { profileRepo.toggleSkipRegion(region) } }
            itemView.alpha = if (region.isEnabled) 1.0f else 0.5f
            binding.cardSkipSections.layoutSkipSections.addView(itemView)
        }
    }
}
