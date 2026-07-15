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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private var pointA: Long = -1L
    private var pointB: Long = -1L
    private var isAbRepeatEnabled = false

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

        repository = SongRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]

        setupControls()
        setupVolumeSeekBar()
        observeViewModel()

        songId = intent.getLongExtra(EXTRA_SONG_ID, -1)
        if (songId != -1L) {
            activityScope.launch {
                repository.getSongById(songId)?.let { s ->
                    populate(s)
                    viewModel.setSong(s, false)
                }
            }
        }

        bindService(Intent(this, MusicService::class.java), serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI,
            true,
            volumeObserver
        )
        syncVolumeSeekBar()
        musicService?.let { syncNow() }
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(volumeObserver)
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

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        binding.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.btnPrev.setOnClickListener { musicService?.skipPrev() }
        binding.btnRewind.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            svc.seekTo((svc.getPosition() - 5000).coerceAtLeast(0))
        }
        binding.btnForward.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            svc.seekTo((svc.getPosition() + 5000).coerceAtMost(svc.getDuration()))
        }

        binding.btnShuffle.setOnClickListener {
            musicService?.toggleShuffle()
            updateShuffleButton()
        }

        binding.btnRepeat.setOnClickListener {
            currentRepeatMode = when (currentRepeatMode) {
                MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
                MusicService.REPEAT_ALL -> MusicService.REPEAT_ONE
                else -> MusicService.REPEAT_NONE
            }
            musicService?.setRepeatMode(currentRepeatMode)
            updateRepeatButton()
        }

        binding.btnProfiles.setOnClickListener {
            ProfilesFragment().show(
                supportFragmentManager,
                "profiles"
            )
        }
        binding.btnEdit.setOnClickListener { showEditDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }

        // Pitch
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
        binding.cardPitch.btnPitchReset.setOnClickListener { resetPitch() }

        // Speed
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
        binding.cardSpeed.btnSpeedReset.setOnClickListener { resetSpeed() }

        // Trim
        binding.cardTrim.seekTrimStart.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, f: Boolean) {
                if (f) updateTrimLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
        })
        binding.cardTrim.seekTrimEnd.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, f: Boolean) {
                if (f) updateTrimLabels()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                saveTrim()
            }
        })
        binding.cardTrim.btnTrimReset.setOnClickListener { resetTrim() }

        // A-B Repeat
        binding.cardAbRepeat.btnSetPointA.setOnClickListener { setPointA() }
        binding.cardAbRepeat.btnSetPointB.setOnClickListener { setPointB() }
        binding.cardAbRepeat.btnResetAb.setOnClickListener { resetAb() }
        binding.cardAbRepeat.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
            isAbRepeatEnabled = checked
            if (checked && pointA >= 0 && pointB > pointA) applyAbRepeat()
            else musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
            saveAbRepeat()
        }
        binding.cardAbRepeat.switchLoop.setOnCheckedChangeListener { _, checked ->
            song?.let { s ->
                musicService?.applyLoopToCurrentSong(
                    s.trimStart,
                    if (s.trimEnd > 0) s.trimEnd else s.duration,
                    checked
                )
            }
        }

        // Profile Buttons
        binding.cardProfiles.btnProfilePrev.setOnClickListener { viewModel.switchToPreviousProfile() }
        binding.cardProfiles.btnProfileNext.setOnClickListener { viewModel.switchToNextProfile() }
        binding.cardProfiles.btnProfileDefault.setOnClickListener { viewModel.switchToDefaultProfile() }

        // State Toggle
        binding.cardStateToggle.switchPlaybackState.setOnCheckedChangeListener { _, checked ->
            musicService?.setBypassProfiles(!checked)
            updateStateLabels(checked)
        }

        binding.btnResetAllStates.setOnClickListener { resetAllStates() }

        binding.cardSkipSections.btnAddSkipSection.setOnClickListener { showAddSkipSectionDialog() }

        binding.seekPlaybackCircular.setOnSeekBarChangeListener(object :
            CircularSeekBar.OnCircularSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: CircularSeekBar,
                progress: Float,
                fromUser: Boolean
            ) {
                if (fromUser) {
                    val duration = musicService?.getDuration() ?: 0
                    musicService?.seekTo((progress / 100f * duration).toInt())
                }
            }

            override fun onStartTrackingTouch(seekBar: CircularSeekBar) {}
            override fun onStopTrackingTouch(seekBar: CircularSeekBar) {}
        })
    }

    private fun populate(s: Song) {
        song = s
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist
        binding.tvFolder.text = s.albumName.ifBlank { s.folderName }
        binding.ivManualIndicator.visibility = if (s.isManuallyEdited) View.VISIBLE else View.GONE

        if (s.albumArtUrl.isNotBlank()) {
            Glide.with(this).load(s.albumArtUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.ic_music_note).into(binding.ivAlbumArt)
        } else {
            binding.ivAlbumArt.setImageResource(R.drawable.ic_music_note)
        }

        val favIcon = settingsRepo.getFavoriteIcon()
        binding.btnFavorite.setImageResource(
            if (s.isFavorite) FavoriteIconHelper.filledRes(favIcon) else FavoriteIconHelper.outlineRes(
                favIcon
            )
        )
        binding.btnFavorite.setColorFilter(
            ContextCompat.getColor(
                this,
                FavoriteIconHelper.colorRes(favIcon)
            )
        )

        binding.cardPitch.seekPitch.progress =
            ((s.pitchSemitones + 6) * 10).toInt().coerceIn(0, 120)
        binding.cardPitch.tvPitchValue.text = pitchLabel(s.pitchSemitones)

        binding.cardSpeed.seekSpeed.progress =
            ((s.playbackSpeed - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        binding.cardSpeed.tvSpeedValue.text = speedLabel(s.playbackSpeed)

        val total = if (s.duration > 0) s.duration else musicService?.getDuration()?.toLong() ?: 0L
        binding.cardTrim.seekTrimStart.max = total.toInt()
        binding.cardTrim.seekTrimEnd.max = total.toInt()
        binding.cardTrim.seekTrimStart.progress = s.trimStart.toInt()
        binding.cardTrim.seekTrimEnd.progress = (if (s.trimEnd > 0) s.trimEnd else total).toInt()
        updateTrimLabels()
    }

    private fun observeViewModel() {
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
            profile?.let {
                pointA = it.abRepeatA
                pointB = it.abRepeatB
                isAbRepeatEnabled = it.abRepeatEnabled
                binding.cardAbRepeat.tvPointA.text =
                    if (pointA >= 0) "A: ${formatDuration(pointA)}" else "A: --:--"
                binding.cardAbRepeat.tvPointB.text =
                    if (pointB >= 0) "B: ${formatDuration(pointB)}" else "B: --:--"
                binding.cardAbRepeat.switchAbRepeat.isChecked = isAbRepeatEnabled
                binding.cardAbRepeat.switchLoop.isChecked = it.loopEnabled
            }
        }
        viewModel.skipRegions.observe(this) { updateSkipRegionsUI(it) }
    }

    private fun registerCallbacks() {
        musicService?.let {
            it.addSongChangedCallback(songChangedCallback)
            it.addPlayStateCallback(playStateCallback)
        }
    }

    private val songChangedCallback: (Song) -> Unit = { s -> runOnUiThread { populate(s) } }
    private val playStateCallback: (Boolean) -> Unit = { playing ->
        runOnUiThread { binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play) }
    }

    private fun syncNow() {
        val svc = musicService ?: return
        binding.btnPlayPause.setImageResource(if (svc.isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        svc.getCurrentSong()?.let { populate(it) }
        updateShuffleButton()
        updateRepeatButton()
        updateStateLabels(!svc.isBypassingProfiles())
        binding.cardStateToggle.switchPlaybackState.isChecked = !svc.isBypassingProfiles()
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val current = svc.getPosition().toLong()
                val total = svc.getDuration().toLong()
                if (total > 0) {
                    binding.seekPlaybackCircular.setProgress((current.toFloat() / total) * 100f)
                    binding.tvCurrentTime.text = formatDuration(current)
                    binding.tvTotalTime.text = formatDuration(total)
                }
                progressHandler.postDelayed(this, 500)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
    }

    private fun toggleFavorite() {
        song?.let { s ->
            activityScope.launch {
                repository.toggleFavorite(s)
                repository.getSongById(s.id)?.let { populate(it) }
            }
        }
    }

    private fun updateShuffleButton() {
        val on = musicService?.isShuffleEnabled() ?: false
        binding.btnShuffle.alpha = if (on) 1.0f else 0.5f
    }

    private fun updateRepeatButton() {
        val mode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
        binding.btnRepeat.alpha = if (mode != MusicService.REPEAT_NONE) 1.0f else 0.5f
        binding.tvRepeatIndicator.text = if (mode == MusicService.REPEAT_ONE) "1" else ""
    }

    private fun updateStateLabels(isUpdated: Boolean) {
        binding.cardStateToggle.tvStateUpdated.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUpdated) R.color.neon_cyan else R.color.text_hint
            )
        )
        binding.cardStateToggle.tvStateOriginal.setTextColor(
            ContextCompat.getColor(
                this,
                if (isUpdated) R.color.text_hint else R.color.neon_pink
            )
        )
    }

    private fun pitchLabel(s: Float) = if (s > 0) "+%.1f".format(s) else "%.1f".format(s)
    private fun speedLabel(s: Float) = "%.2f".format(s).trimEnd('0').trimEnd('.')
    private fun updateTrimLabels() {
        binding.cardTrim.tvTrimStart.text =
            "Start: ${formatDuration(binding.cardTrim.seekTrimStart.progress.toLong())}"
        binding.cardTrim.tvTrimEnd.text =
            "End: ${formatDuration(binding.cardTrim.seekTrimEnd.progress.toLong())}"
    }

    private fun resetPitch() {
        binding.cardPitch.seekPitch.progress = 60
        binding.cardPitch.tvPitchValue.text = "0.0"
        musicService?.applyPitchToCurrentSong(0f)
        activityScope.launch { repository.updatePitchAndSyncProfile(songId, 0f) }
    }

    private fun resetSpeed() {
        binding.cardSpeed.seekSpeed.progress = 10
        binding.cardSpeed.tvSpeedValue.text = "1.0"
        musicService?.applySpeedToCurrentSong(1.0f)
        activityScope.launch { repository.updateSpeedAndSyncProfile(songId, 1.0f) }
    }

    private fun resetTrim() {
        val total = musicService?.getDuration() ?: 0
        binding.cardTrim.seekTrimStart.progress = 0
        binding.cardTrim.seekTrimEnd.progress = total
        updateTrimLabels()
        saveTrim()
    }

    private fun saveTrim() {
        val start = binding.cardTrim.seekTrimStart.progress.toLong()
        val end = binding.cardTrim.seekTrimEnd.progress.toLong()
        musicService?.applyTrimToCurrentSong(start, end)
        activityScope.launch { repository.updateTrimAndSyncProfile(songId, start, end) }
    }

    private fun setPointA() {
        pointA = musicService?.getPosition()?.toLong() ?: 0L
        binding.cardAbRepeat.tvPointA.text = "A: ${formatDuration(pointA)}"
        saveAbRepeat()
    }

    private fun setPointB() {
        pointB = musicService?.getPosition()?.toLong() ?: 0L
        binding.cardAbRepeat.tvPointB.text = "B: ${formatDuration(pointB)}"
        saveAbRepeat()
    }

    private fun resetAb() {
        pointA = -1; pointB = -1; isAbRepeatEnabled = false
        binding.cardAbRepeat.tvPointA.text = "A: --:--"; binding.cardAbRepeat.tvPointB.text =
            "B: --:--"
        binding.cardAbRepeat.switchAbRepeat.isChecked = false
        musicService?.applyAbRepeatToCurrentSong(-1, -1, false)
        saveAbRepeat()
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

    private fun setupVolumeSeekBar() {
        binding.cardVolume.seekVolume.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (p / 100f * maxVolume).toInt(),
                    0
                )
                binding.cardVolume.tvVolumeValue.text = p.toString()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun syncVolumeSeekBar() {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = (cur.toFloat() / maxVolume * 100).toInt()
        binding.cardVolume.seekVolume.progress = pct
        binding.cardVolume.tvVolumeValue.text = pct.toString()
    }

    private fun resetAllStates() {
        MaterialAlertDialogBuilder(this).setTitle("RESET ALL").setMessage("Reset everything?")
            .setPositiveButton("YES") { _, _ ->
                activityScope.launch {
                    repository.updatePitchAndSyncProfile(songId, 0f)
                    repository.updateSpeedAndSyncProfile(songId, 1.0f)
                    repository.updateTrimAndSyncProfile(songId, 0, -1)
                    profileRepo.deleteAllSkipRegions(songId)
                    runOnUiThread { syncNow() }
                }
            }.setNegativeButton("NO", null).show()
    }

    private fun showEditDialog() {
        val s = song ?: return
        val dlgBinding = DialogEditSongBinding.inflate(layoutInflater)
        editDialogBinding = dlgBinding
        dlgBinding.etTitle.setText(s.title); dlgBinding.etArtist.setText(s.artist); dlgBinding.etAlbum.setText(
            s.albumName
        )
        if (s.albumArtUrl.isNotBlank()) Glide.with(this).load(s.albumArtUrl)
            .into(dlgBinding.ivEditAlbumArt)
        dlgBinding.btnChangeArt.setOnClickListener { pickArtLauncher.launch("image/*") }
        val dialog = MaterialAlertDialogBuilder(this).setView(dlgBinding.root).create()
        dlgBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dlgBinding.btnSave.setOnClickListener {
            val artUrl = selectedArtUri?.toString() ?: s.albumArtUrl
            activityScope.launch {
                repository.updateSongDetailsManual(
                    s.id,
                    dlgBinding.etTitle.text.toString(),
                    dlgBinding.etArtist.text.toString(),
                    dlgBinding.etAlbum.text.toString(),
                    artUrl
                )
                runOnUiThread { dialog.dismiss(); populate(s.copy(title = dlgBinding.etTitle.text.toString())) }
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmDialog() {
        val s = song ?: return
        MaterialAlertDialogBuilder(this).setTitle("DELETE").setMessage("Delete song?")
            .setPositiveButton("DELETE") { _, _ ->
                activityScope.launch { repository.deleteSong(s); runOnUiThread { musicService?.skipNext(); finish() } }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun showAddSkipSectionDialog() {
        val pos = musicService?.getPosition()?.toLong() ?: 0L
        viewModel.addSkipRegion(songId, "", pos, (pos + 30000).coerceAtMost(song?.duration ?: pos))
    }

    private fun updateSkipRegionsUI(regions: List<SkipRegion>) {
        binding.cardSkipSections.layoutSkipSections.removeAllViews()
        regions.forEach { region ->
            val v = layoutInflater.inflate(
                R.layout.item_selection_dialog,
                binding.cardSkipSections.layoutSkipSections,
                false
            )
            v.findViewById<android.widget.TextView>(R.id.tvItemName).text =
                "${formatDuration(region.startMs)} -> ${formatDuration(region.endMs)}"
            binding.cardSkipSections.layoutSkipSections.addView(v)
        }
    }
}
