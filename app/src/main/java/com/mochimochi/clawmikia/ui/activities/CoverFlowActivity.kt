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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import com.mochimochi.clawmikiacrazy.data.model.SongAnalysis
import com.mochimochi.clawmikiacrazy.data.model.PlaybackProfile
import com.mochimochi.clawmikiacrazy.data.repository.ProfileRepository
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.data.repository.SongRepository
import com.mochimochi.clawmikiacrazy.databinding.ActivityCoverFlowPlayerBinding
import com.mochimochi.clawmikiacrazy.databinding.ItemCoverBinding
import com.mochimochi.clawmikiacrazy.databinding.DialogEditSongBinding
import com.mochimochi.clawmikiacrazy.service.MusicService
import com.mochimochi.clawmikiacrazy.ui.fragments.ProfilesFragment
import com.mochimochi.clawmikiacrazy.ui.viewmodels.NowPlayingViewModel
import com.mochimochi.clawmikiacrazy.ui.views.CoverFlowTransformer
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CoverFlowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCoverFlowPlayerBinding
    private val viewModel: NowPlayingViewModel by viewModels()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var repository: SongRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var settingsRepo: SettingsRepository

    private var musicService: MusicService? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private var songs: List<Song> = emptyList()
    private var song: Song? = null
    private var songId: Long = -1
    private lateinit var adapter: CoverFlowAdapter
    private var favoriteIconType: String = "heart"
    private var currentRepeatMode: Int = 0

    private lateinit var audioManager: AudioManager
    private var maxVolume: Int = 0
    private var lastVolumeBeforeMute: Int = 0

    private var pointA: Long = -1L
    private var pointB: Long = -1L
    private var isAbRepeatEnabled: Boolean = false
    private var isTrimDragging: Boolean = false

    private val volumeObserver =
        object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncVolumeSeekBar()
            }
        }

    private val songChangedCallback: (Song) -> Unit = { s ->
        song = s
        songId = s.id
        viewModel.setSong(s, musicService?.isPlaying() ?: false)
        populate(s)
    }

    private val playStateCallback: (Boolean) -> Unit = { playing ->
        viewModel.setPlaying(playing)
        updatePlayButton(playing)
        if (playing) startProgressUpdates() else stopProgressUpdates()
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
            syncVolumeSeekBar()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCoverFlowPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mainContentLayout.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        repository = SongRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        songId = intent.getLongExtra("EXTRA_SONG_ID", -1)

        setupViewPager()
        setupControls()
        observeViewModel()

        bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )

        // Initial load from DB
        if (songId != -1L) {
            activityScope.launch {
                repository.getSongById(songId)?.let { s ->
                    song = s
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
        if (musicService?.isPlaying() == true) {
            startProgressUpdates()
        }
        syncNow()
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
        contentResolver.unregisterContentObserver(volumeObserver)
    }

    private fun setupViewPager() {
        adapter = CoverFlowAdapter(emptyList())
        binding.viewPagerCovers.adapter = adapter
        binding.viewPagerCovers.setPageTransformer(CoverFlowTransformer())
        binding.viewPagerCovers.offscreenPageLimit = 3

        binding.viewPagerCovers.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (songs.isNotEmpty()) {
                    val s = songs[position]
                    if (musicService?.getCurrentSong()?.id != s.id) {
                        musicService?.playAt(position)
                    }
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }

        binding.btnNext.setOnClickListener {
            val current = binding.viewPagerCovers.currentItem
            if (current < adapter.itemCount - 1) {
                binding.viewPagerCovers.setCurrentItem(current + 1, true)
            }
        }
        binding.btnPrev.setOnClickListener {
            // Immediate UI feedback for replay
            binding.seekPlayback.progress = 0
            binding.tvCurrentTime.text = formatDuration(0)
            musicService?.skipPrev()
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

        binding.btnShuffle.setOnClickListener {
            musicService?.toggleShuffle()
            updateShuffleButton()
        }

        binding.switchLoop.setOnCheckedChangeListener { _, checked ->
            val s = song ?: return@setOnCheckedChangeListener
            musicService?.applyLoopToCurrentSong(
                s.trimStart,
                if (s.trimEnd > 0) s.trimEnd else s.duration,
                checked
            )
            viewModel.activeProfile.value?.let { profile ->
                viewModel.updateLoop(profile.id, profile.loopStart, profile.loopEnd, checked)
            }
        }

        binding.btnEdit.setOnClickListener { showEditDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
        binding.btnProfiles.setOnClickListener {
            ProfilesFragment().show(supportFragmentManager, "profiles")
        }

        binding.btnProfileDefault.setOnClickListener { viewModel.switchToDefaultProfile() }
        binding.btnProfilePrev.setOnClickListener { viewModel.switchToPreviousProfile() }
        binding.btnProfileNext.setOnClickListener { viewModel.switchToNextProfile() }

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

        // Pitch
        binding.seekPitch.max = 120
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val semitones = (p - 60) / 10.0f
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
            val p =
                (binding.seekPitch.progress - (settingsRepo.getPitchStep() * 10).toInt()).coerceAtLeast(
                    0
                )
            binding.seekPitch.progress = p
            val semitones = (p - 60) / 10.0f
            musicService?.applyPitchToCurrentSong(semitones)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
        }
        binding.btnPitchUp.setOnClickListener {
            val p =
                (binding.seekPitch.progress + (settingsRepo.getPitchStep() * 10).toInt()).coerceAtMost(
                    120
                )
            binding.seekPitch.progress = p
            val semitones = (p - 60) / 10.0f
            musicService?.applyPitchToCurrentSong(semitones)
            activityScope.launch { repository.updatePitchAndSyncProfile(songId, semitones) }
        }

        // Speed
        binding.seekSpeed.max = 50
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val s = progressToSpeed(p)
                binding.tvSpeedValue.text = speedLabel(s)
                if (fromUser) musicService?.applySpeedToCurrentSong(s)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val s = progressToSpeed(sb.progress)
                activityScope.launch { repository.updateSpeedAndSyncProfile(songId, s) }
            }
        })
        binding.btnSpeedReset.setOnClickListener {
            binding.seekSpeed.progress = 10
            binding.tvSpeedValue.text = speedLabel(1.0f)
            musicService?.applySpeedToCurrentSong(1.0f)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, 1.0f) }
        }
        binding.btnSpeedDown.setOnClickListener {
            val p = (binding.seekSpeed.progress - settingsRepo.getSpeedStep()).coerceAtLeast(0)
            binding.seekSpeed.progress = p
            val s = progressToSpeed(p)
            musicService?.applySpeedToCurrentSong(s)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, s) }
        }
        binding.btnSpeedUp.setOnClickListener {
            val p = (binding.seekSpeed.progress + settingsRepo.getSpeedStep()).coerceAtMost(50)
            binding.seekSpeed.progress = p
            val s = progressToSpeed(p)
            musicService?.applySpeedToCurrentSong(s)
            activityScope.launch { repository.updateSpeedAndSyncProfile(songId, s) }
        }

        // Volume
        binding.seekVolume.max = maxVolume * 2
        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (p <= maxVolume) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                        settingsRepo.setVolumeBoost(0)
                    } else {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                        settingsRepo.setVolumeBoost((p - maxVolume) * 10)
                    }
                    syncVolumeSeekBar()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnVolumeUp.setOnClickListener {
            val step = maxOf(1, (maxVolume * settingsRepo.getVolumeStep() / 100f).toInt())
            val p = (binding.seekVolume.progress + step).coerceAtMost(maxVolume * 2)
            if (p <= maxVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                settingsRepo.setVolumeBoost(0)
            } else {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                settingsRepo.setVolumeBoost((p - maxVolume) * 10)
            }
            syncVolumeSeekBar()
        }
        binding.btnVolumeDown.setOnClickListener {
            val step = maxOf(1, (maxVolume * settingsRepo.getVolumeStep() / 100f).toInt())
            val p = (binding.seekVolume.progress - step).coerceAtLeast(0)
            if (p <= maxVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                settingsRepo.setVolumeBoost(0)
            } else {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                settingsRepo.setVolumeBoost((p - maxVolume) * 10)
            }
            syncVolumeSeekBar()
        }
        binding.btnVolumeReset.setOnClickListener {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            settingsRepo.setVolumeBoost(0)
            syncVolumeSeekBar()
        }
        binding.btnVolumeMute.setOnClickListener {
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

        // Trim
        binding.seekTrimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val endP = binding.seekTrimEnd.progress
                val cl = p.coerceAtMost((endP - 1000).coerceAtLeast(0))
                if (p != cl) {
                    sb.progress = cl; return
                }
                updateTrimLabels(cl.toLong(), endP.toLong())
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

        binding.btnTrimStartMinus.setOnClickListener {
            val current = binding.seekTrimStart.progress
            val trimStepMs = (settingsRepo.getTrimStep() * 1000).toInt().coerceAtLeast(100)
            val newVal = (current - trimStepMs).coerceAtLeast(0)
            binding.seekTrimStart.progress = newVal
            updateTrimLabels(newVal.toLong(), binding.seekTrimEnd.progress.toLong())
            saveTrim()
        }

        binding.btnTrimStartPlus.setOnClickListener {
            val current = binding.seekTrimStart.progress
            val endVal = binding.seekTrimEnd.progress
            val trimStepMs = (settingsRepo.getTrimStep() * 1000).toInt().coerceAtLeast(100)
            val newVal = (current + trimStepMs).coerceAtMost((endVal - 1000).coerceAtLeast(0))
            binding.seekTrimStart.progress = newVal
            updateTrimLabels(newVal.toLong(), endVal.toLong())
            saveTrim()
        }

        binding.btnTrimEndMinus.setOnClickListener {
            val current = binding.seekTrimEnd.progress
            val startVal = binding.seekTrimStart.progress
            val trimStepMs = (settingsRepo.getTrimStep() * 1000).toInt().coerceAtLeast(100)
            val newVal = (current - trimStepMs).coerceAtLeast(startVal + 1000)
            binding.seekTrimEnd.progress = newVal
            updateTrimLabels(startVal.toLong(), newVal.toLong())
            saveTrim()
        }

        binding.btnTrimEndPlus.setOnClickListener {
            val current = binding.seekTrimEnd.progress
            val maxEnd = binding.seekTrimEnd.max
            val trimStepMs = (settingsRepo.getTrimStep() * 1000).toInt().coerceAtLeast(100)
            val newVal = (current + trimStepMs).coerceAtMost(maxEnd)
            binding.seekTrimEnd.progress = newVal
            updateTrimLabels(binding.seekTrimStart.progress.toLong(), newVal.toLong())
            saveTrim()
        }

        binding.btnTrimReset.setOnClickListener {
            val s = song ?: return@setOnClickListener
            val total = s.duration
            binding.seekTrimStart.progress = 0
            binding.seekTrimEnd.progress = total.toInt()
            updateTrimLabels(0L, total)
            musicService?.applyTrimToCurrentSong(0L, -1L)
            activityScope.launch { repository.updateTrimAndSyncProfile(s.id, 0L, -1L) }
        }

        // A-B Repeat
        binding.btnSetPointA.setOnClickListener {
            pointA = musicService?.getPosition()?.toLong() ?: -1L
            binding.tvPointA.text = "A: ${formatDuration(pointA)}"
            saveAbRepeat()
        }
        binding.btnSetPointB.setOnClickListener {
            val pos = musicService?.getPosition()?.toLong() ?: -1L
            if (pos > pointA) {
                pointB = pos
                binding.tvPointB.text = "B: ${formatDuration(pointB)}"
                saveAbRepeat()
            }
        }
        binding.btnResetAb.setOnClickListener {
            pointA = -1L; pointB = -1L; isAbRepeatEnabled = false
            binding.tvPointA.text = "A: --:--"; binding.tvPointB.text = "B: --:--"
            binding.switchAbRepeat.isChecked = false
            musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            saveAbRepeat()
        }
        binding.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
            isAbRepeatEnabled = checked
            if (checked && pointA >= 0 && pointB > pointA) {
                musicService?.applyAbRepeatToCurrentSong(pointA, pointB, true)
            } else {
                if (checked) {
                    binding.switchAbRepeat.isChecked = false
                    Toast.makeText(this, "Set A and B first", Toast.LENGTH_SHORT).show()
                }
                musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            }
            saveAbRepeat()
        }

        binding.btnAddSkipSection.setOnClickListener { showAddSkipSectionDialog() }
        binding.btnResetAllStates.setOnClickListener { showResetAllStatesConfirm() }

        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val s = song ?: return
                    val dur = musicService?.getDuration()?.toLong() ?: return
                    val tStart = s.trimStart
                    val tEnd = if (s.trimEnd > 0L) s.trimEnd else dur
                    val eff = (tEnd - tStart).coerceAtLeast(0L)
                    musicService?.seekTo((tStart + (p / 100.0 * eff)).toInt())
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

        binding.switchPlaybackState.setOnCheckedChangeListener { _, checked ->
            musicService?.setBypassProfiles(!checked)
            viewModel.setOriginalState(!checked)
        }
    }

    private fun observeViewModel() {
        viewModel.currentSong.observe(this) { s -> s?.let { populate(it) } }
        viewModel.isPlaying.observe(this) { p ->
            updatePlayButton(p)
            if (p) startProgressUpdates() else stopProgressUpdates()
        }
        viewModel.songAnalysis.observe(this) { analysis: SongAnalysis? ->
            if (analysis != null) {
                binding.tvBpm.text = "BPM: ${analysis.bpm.toInt()}"
                binding.tvKey.text = analysis.key
                binding.tvBpm.visibility = View.VISIBLE
                binding.tvKey.visibility = View.VISIBLE
            } else {
                binding.tvBpm.visibility = View.GONE
                binding.tvKey.visibility = View.GONE
            }
        }
        viewModel.activeProfile.observe(this) { profile: PlaybackProfile? ->
            profile?.let {
                // Apply profile to service
                musicService?.applyProfile(it)

                binding.seekPitch.progress = (it.pitchSemitones * 10 + 60).toInt()
                binding.seekSpeed.progress = ((it.playbackSpeed - 0.5f) / 0.05f).toInt()
                binding.tvPitchValue.text = pitchLabel(it.pitchSemitones)
                binding.tvSpeedValue.text = speedLabel(it.playbackSpeed)
                pointA = it.abRepeatA
                pointB = it.abRepeatB
                isAbRepeatEnabled = it.abRepeatEnabled
                binding.switchAbRepeat.isChecked = it.abRepeatEnabled
                binding.switchLoop.isChecked = it.loopEnabled
                binding.tvPointA.text =
                    if (pointA >= 0) "A: ${formatDuration(pointA)}" else "A: --:--"
                binding.tvPointB.text =
                    if (pointB >= 0) "B: ${formatDuration(pointB)}" else "B: --:--"

                // Disable editing for Default profile
                val isDefault = it.isDefault
                val editAlpha = if (isDefault) 0.6f else 1.0f

                binding.seekPitch.isEnabled = !isDefault
                binding.btnPitchDown.isEnabled = !isDefault
                binding.btnPitchUp.isEnabled = !isDefault
                binding.btnPitchReset.isEnabled = !isDefault
                binding.cardPitch.alpha = editAlpha

                binding.seekSpeed.isEnabled = !isDefault
                binding.btnSpeedDown.isEnabled = !isDefault
                binding.btnSpeedUp.isEnabled = !isDefault
                binding.btnSpeedReset.isEnabled = !isDefault
                binding.cardSpeed.alpha = editAlpha

                binding.seekTrimStart.isEnabled = !isDefault
                binding.seekTrimEnd.isEnabled = !isDefault
                binding.btnTrimReset.isEnabled = !isDefault
                binding.btnTrimStartMinus.isEnabled = !isDefault
                binding.btnTrimStartPlus.isEnabled = !isDefault
                binding.btnTrimEndMinus.isEnabled = !isDefault
                binding.btnTrimEndPlus.isEnabled = !isDefault
                binding.cardTrim.alpha = editAlpha

                binding.btnSetPointA.isEnabled = !isDefault
                binding.btnSetPointB.isEnabled = !isDefault
                binding.btnResetAb.isEnabled = !isDefault
                binding.switchAbRepeat.isEnabled = !isDefault
                binding.switchLoop.isEnabled = !isDefault
                binding.cardAbRepeat.alpha = editAlpha

                // Skip sections and Reset All are Song-level, so keep them enabled
                binding.btnAddSkipSection.isEnabled = true
                binding.cardSkipSections.alpha = 1.0f

                binding.btnResetAllStates.isEnabled = true

                updateProfileSwitchButtons()
            }
        }
        viewModel.profiles.observe(this) { updateProfileSwitchButtons() }
        viewModel.skipRegions.observe(this) { regions ->
            updateSkipRegionsUI(regions)
            musicService?.applySkipRegions(regions.filter { it.isEnabled })
        }
        settingsRepo.favoriteIconLive.observe(this) {
            favoriteIconType = it
            song?.let { s -> updateFavoriteIcon(s) }
        }
    }

    private fun populate(s: Song) {
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = s.folderName

        binding.ivManualIndicator.visibility = if (s.isManuallyEdited) View.VISIBLE else View.GONE

        // Blurred Background
        Glide.with(this)
            .load(s.albumArtUrl)
            .override(50) // Load tiny version for implicit blur
            .placeholder(R.drawable.bg_mini_player)
            .into(binding.ivBackgroundArt)

        binding.seekTrimStart.max = s.duration.toInt()
        binding.seekTrimEnd.max = s.duration.toInt()
        binding.seekTrimStart.progress = s.trimStart.toInt()
        binding.seekTrimEnd.progress = (if (s.trimEnd > 0) s.trimEnd else s.duration).toInt()
        updateTrimLabels(s.trimStart, if (s.trimEnd > 0) s.trimEnd else s.duration)

        updateFavoriteIcon(s)

        // ViewPager sync
        val idx = songs.indexOfFirst { it.id == s.id }
        if (idx != -1 && binding.viewPagerCovers.currentItem != idx) {
            binding.viewPagerCovers.setCurrentItem(idx, true)
        }
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
            service.getQueue()?.let {
                songs = it
                adapter = CoverFlowAdapter(it)
                binding.viewPagerCovers.adapter = adapter
                val cur = service.getCurrentSong()
                val idx = it.indexOfFirst { s -> s.id == cur?.id }
                if (idx != -1) binding.viewPagerCovers.setCurrentItem(idx, false)
            }
        }
    }

    private fun syncNow() {
        musicService?.getCurrentSong()?.let { s ->
            song = s
            songId = s.id
            viewModel.setSong(s, musicService?.isPlaying() ?: false)
            populate(s)
        }
        currentRepeatMode = musicService?.getRepeatMode() ?: 0
        updateRepeatButton()
        updateShuffleButton()
    }

    private fun syncVolumeSeekBar() {
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val boost = settingsRepo.getVolumeBoost()
        val progress = if (boost > 0) maxVolume + (boost / 10) else curVol
        binding.seekVolume.progress = progress
        binding.tvVolumeValue.text = ((progress.toFloat() / maxVolume) * 100).toInt().toString()
    }

    private fun updatePlayButton(p: Boolean) {
        binding.btnPlayPause.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateRepeatButton() {
        when (currentRepeatMode) {
            MusicService.REPEAT_ALL -> {
                val color = ContextCompat.getColor(this, R.color.neon_cyan)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = "ALL"
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }

            MusicService.REPEAT_ONE -> {
                val color = ContextCompat.getColor(this, R.color.neon_pink)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = "ONE"
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }

            MusicService.REPEAT_AUTO -> {
                val color = ContextCompat.getColor(this, R.color.neon_purple)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = "R"
                binding.tvRepeatIndicator.setTextColor(color)
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = "AUTO"
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }

            else -> {
                val color = ContextCompat.getColor(this, R.color.text_hint)
                binding.btnRepeat.setImageResource(R.drawable.ic_repeat)
                binding.btnRepeat.setColorFilter(color)
                binding.tvRepeatIndicator.text = ""
                binding.tvRepeatLabel.setTextColor(color)
                binding.tvRepeatLabel.text = "OFF"
                binding.tvRepeatLabel.visibility = View.VISIBLE
            }
        }
    }

    private fun updateShuffleButton() {
        val on = musicService?.isShuffleEnabled() ?: false
        binding.btnShuffle.setColorFilter(
            ContextCompat.getColor(
                this,
                if (on) R.color.neon_cyan else R.color.text_hint
            )
        )
    }

    private fun updateSkipRegionsUI(regions: List<SkipRegion>) {
        binding.layoutSkipSections.removeAllViews()
        binding.tvNoSkipSections.visibility = if (regions.isEmpty()) View.VISIBLE else View.GONE
        regions.forEach { r ->
            val v = layoutInflater.inflate(
                R.layout.item_selection_dialog,
                binding.layoutSkipSections,
                false
            )
            v.findViewById<TextView>(R.id.tvItemName).text =
                "${formatDuration(r.startMs)} ➔ ${formatDuration(r.endMs)}"
            v.findViewById<ImageView>(R.id.ivItemIcon)
                .setOnClickListener { viewModel.deleteSkipRegion(r) }

            v.setOnClickListener {
                activityScope.launch { profileRepo.toggleSkipRegion(r) }
            }
            v.alpha = if (r.isEnabled) 1.0f else 0.5f

            binding.layoutSkipSections.addView(v)
        }
    }

    private fun showAddSkipSectionDialog() {
        val s = song ?: return
        val pos = musicService?.getPosition()?.toLong() ?: 0L
        MaterialAlertDialogBuilder(this).setTitle("ADD SKIP SECTION")
            .setMessage("Add 30s skip at ${formatDuration(pos)}?")
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
        val btnSave = dialogView.findViewById<View>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<View>(R.id.btnCancel)

        val curPos = musicService?.getPosition()?.toLong() ?: 0L
        etStartMin.setText(((curPos / 1000) / 60).toString())
        etStartSec.setText(((curPos / 1000) % 60).toString())

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val startMs =
                ((etStartMin.text.toString().toLongOrNull() ?: 0) * 60 + (etStartSec.text.toString()
                    .toLongOrNull() ?: 0)) * 1000
            val endMs =
                ((etEndMin.text.toString().toLongOrNull() ?: 0) * 60 + (etEndSec.text.toString()
                    .toLongOrNull() ?: 0)) * 1000
            if (endMs > startMs && startMs < s.duration) {
                viewModel.addSkipRegion(s.id, "", startMs, endMs.coerceAtMost(s.duration))
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Invalid range", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showResetAllStatesConfirm() {
        MaterialAlertDialogBuilder(this).setTitle("RESET ALL").setMessage("Sure?")
            .setPositiveButton("YES") { _, _ -> resetAllStates() }.show()
    }

    private fun resetAllStates() {
        val s = song ?: return
        val id = s.id

        activityScope.launch {
            // 1. Reset Song entity fields
            repository.updatePitchAndSyncProfile(id, 0f)
            repository.updateSpeedAndSyncProfile(id, 1.0f)
            repository.updateTrimAndSyncProfile(id, 0L, -1L)

            // 2. Reset Profile fields
            viewModel.activeProfile.value?.let { profile ->
                val resetProfile = profile.copy(
                    pitchSemitones = 0f,
                    playbackSpeed = 1.0f,
                    trimStart = 0L,
                    trimEnd = -1L,
                    abRepeatEnabled = false,
                    bassBoostEnabled = false,
                    reverbEnabled = false,
                    loudnessEnabled = false,
                    compressorEnabled = false
                )
                profileRepo.updateProfile(resetProfile)
            }

            // 3. Delete all skip regions
            profileRepo.deleteAllSkipRegions(id)

            runOnUiThread {
                pointA = -1L
                pointB = -1L
                isAbRepeatEnabled = false

                // Refresh Service
                musicService?.applyPitchToCurrentSong(0f)
                musicService?.applySpeedToCurrentSong(1.0f)
                musicService?.applyTrimToCurrentSong(0L, -1L)
                musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
                musicService?.setBypassProfiles(false)

                syncNow()
                Toast.makeText(this@CoverFlowActivity, "All states reset", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun showEditDialog() {
        val s = song ?: return
        val dlgBinding = DialogEditSongBinding.inflate(layoutInflater)
        dlgBinding.etTitle.setText(s.title)
        dlgBinding.etArtist.setText(s.artist)
        dlgBinding.etAlbum.setText(s.albumName)

        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this).load(s.albumArtUrl).placeholder(R.drawable.ic_music_note)
                .into(dlgBinding.ivEditAlbumArt)
        }

        val dialog = MaterialAlertDialogBuilder(this).setView(dlgBinding.root).create()
        dlgBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnSave.setOnClickListener {
            val title = dlgBinding.etTitle.text.toString().trim()
            if (title.isNotEmpty()) {
                activityScope.launch {
                    repository.updateSongDetailsManual(
                        s.id,
                        title,
                        dlgBinding.etArtist.text.toString().trim(),
                        dlgBinding.etAlbum.text.toString().trim(),
                        s.albumArtUrl
                    )
                    runOnUiThread { syncNow(); dialog.dismiss() }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmDialog() {
        val s = song ?: return
        MaterialAlertDialogBuilder(this).setTitle("DELETE SONG")
            .setMessage("Delete \"${s.title}\" from library?")
            .setPositiveButton("DELETE") { _, _ ->
                activityScope.launch {
                    repository.deleteSong(s)
                    runOnUiThread { musicService?.skipNext(); finish() }
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun saveTrim() {
        val s = binding.seekTrimStart.progress.toLong()
        val e = binding.seekTrimEnd.progress.toLong()
        song = song?.copy(trimStart = s, trimEnd = e)
        musicService?.applyTrimToCurrentSong(s, e)
        activityScope.launch { repository.updateTrimAndSyncProfile(songId, s, e) }
    }

    private fun saveAbRepeat() {
        viewModel.activeProfile.value?.let { p ->
            viewModel.updateAbRepeat(
                p.id,
                pointA,
                pointB,
                isAbRepeatEnabled
            )
        }
    }

    private fun updateProfileSwitchButtons() {
        val currentProfiles = viewModel.profiles.value ?: return
        val active = viewModel.activeProfile.value ?: return

        val currentIndex = currentProfiles.indexOfFirst { it.id == active.id }
        if (currentIndex != -1) {
            val hasPrev = currentIndex > 0
            val hasNext = currentIndex < currentProfiles.size - 1
            val hasMultipleProfiles = currentProfiles.size > 1

            binding.btnProfilePrev.isEnabled = hasPrev
            binding.btnProfileNext.isEnabled = hasNext
            binding.btnProfileDefault.isEnabled = hasMultipleProfiles

            binding.btnProfilePrev.alpha = if (hasPrev) 1.0f else 0.5f
            binding.btnProfileNext.alpha = if (hasNext) 1.0f else 0.5f
            binding.btnProfileDefault.alpha = if (hasMultipleProfiles) 1.0f else 0.5f
        }
    }

    private fun updateTrimLabels(s: Long, e: Long) {
        binding.tvTrimStart.text = "Start: ${formatDuration(s)}"
        binding.tvTrimEnd.text = "End: ${formatDuration(e)}"
    }

    private fun pitchLabel(s: Float) = if (s > 0) "+%.1f".format(s) else "%.1f".format(s)
    private fun progressToSpeed(p: Int) = (0.5f + p * 0.05f).coerceIn(0.5f, 3.0f)
    private fun speedLabel(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.')

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val s = song ?: return
                val dur = svc.getDuration().toLong()
                val rel = (svc.getPosition() - s.trimStart).coerceAtLeast(0L)
                val eff = ((if (s.trimEnd > 0) s.trimEnd else dur) - s.trimStart).coerceAtLeast(1L)
                binding.seekPlayback.progress = (rel.toFloat() / eff * 100).toInt()
                binding.tvCurrentTime.text = formatDuration(rel)
                binding.tvTotalTime.text = formatDuration(eff)
                progressHandler.postDelayed(this, 500)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
            service.removeRepeatModeCallback(repeatModeCallback)
            service.removeShuffleCallback(shuffleCallback)
        }
        unbindService(serviceConnection)
    }

    companion object {
        fun start(context: Context, songId: Long) {
            val intent = Intent(context, CoverFlowActivity::class.java)
            intent.putExtra("EXTRA_SONG_ID", songId)
            context.startActivity(intent)
        }
    }

    inner class CoverFlowAdapter(private val songs: List<Song>) :
        RecyclerView.Adapter<CoverFlowAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemCoverBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            ViewHolder(ItemCoverBinding.inflate(LayoutInflater.from(p.context), p, false))

        override fun onBindViewHolder(h: ViewHolder, p: Int) {
            val s = songs[p]

            // Album Art
            Glide.with(h.binding.ivCover)
                .load(s.albumArtUrl)
                .placeholder(R.drawable.ic_music_note)
                .into(h.binding.ivCover)

            // Indicators
            h.binding.ivFavoriteIndicator.visibility = if (s.isFavorite) View.VISIBLE else View.GONE
            h.binding.ivEditedIndicator.visibility =
                if (s.isManuallyEdited) View.VISIBLE else View.GONE

            // Song Index
            h.binding.tvIndex.text = "${p + 1} / ${songs.size}"

            // Format Info (Dynamic lookup based on extension)
            val ext = s.filePath.substringAfterLast('.', "MP3").uppercase()
            val sizeMb = "%.1f MB".format(s.fileSize / (1024f * 1024f))
            h.binding.tvFormat.text = "$ext • $sizeMb"

            // Dynamic Glow Color
            val glowColor = if (s.isFavorite) R.color.neon_pink else R.color.neon_cyan
            h.binding.cardCover.strokeColor =
                ContextCompat.getColor(h.binding.root.context, glowColor)
        }

        override fun getItemCount() = songs.size
    }
}
