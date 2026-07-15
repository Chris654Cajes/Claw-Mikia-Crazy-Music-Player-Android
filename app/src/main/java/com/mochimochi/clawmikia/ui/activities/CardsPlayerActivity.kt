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
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.data.repository.SongRepository
import com.mochimochi.clawmikiacrazy.data.repository.ProfileRepository
import com.mochimochi.clawmikiacrazy.databinding.*
import com.mochimochi.clawmikiacrazy.service.MusicService
import com.mochimochi.clawmikiacrazy.ui.fragments.ProfilesFragment
import com.mochimochi.clawmikiacrazy.ui.viewmodels.NowPlayingViewModel
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CardsPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingCardsBinding
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SongRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var viewModel: NowPlayingViewModel
    private lateinit var audioManager: AudioManager

    private var musicService: MusicService? = null
    private var song: Song? = null
    private var songId: Long = -1
    private var currentRepeatMode = MusicService.REPEAT_NONE

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
            Intent(ctx, CardsPlayerActivity::class.java).putExtra(
                EXTRA_SONG_ID,
                songId
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNowPlayingCardsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = SongRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        profileRepo = ProfileRepository(applicationContext)
        viewModel = ViewModelProvider(this)[NowPlayingViewModel::class.java]
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        setupControls()
        setupViewPager()
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

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        binding.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.btnPrev.setOnClickListener { musicService?.skipPrev() }
        binding.btnRewind.setOnClickListener {
            musicService?.seekTo(
                (musicService?.getPosition() ?: 0) - 5000
            )
        }
        binding.btnForward.setOnClickListener {
            musicService?.seekTo(
                (musicService?.getPosition() ?: 0) + 5000
            )
        }

        binding.btnShuffle.setOnClickListener { musicService?.toggleShuffle(); updateShuffleButton() }
        binding.btnRepeat.setOnClickListener { toggleRepeat() }
        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnProfiles.setOnClickListener {
            ProfilesFragment().show(
                supportFragmentManager,
                "profiles"
            )
        }
        binding.btnEdit.setOnClickListener { showEditDialog() }
        binding.btnDelete.setOnClickListener { showDeleteConfirmDialog() }
        binding.btnResetAllStates.setOnClickListener { resetAllStates() }

        binding.switchSwipeEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.viewPagerSections.isUserInputEnabled = isChecked
        }

        binding.seekPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = musicService?.getDuration() ?: 0
                    musicService?.seekTo((p / 100f * duration).toInt())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupViewPager() {
        binding.viewPagerSections.adapter = SectionsAdapter()
        binding.viewPagerSections.offscreenPageLimit = 8
    }

    private fun observeViewModel() {
        viewModel.songAnalysis.observe(this) { analysis ->
            if (analysis != null) {
                binding.tvBpm.text = getString(R.string.bpm_format, analysis.bpm.toInt())
                binding.tvKey.text = getString(R.string.key_format, analysis.key)
            }
        }
        viewModel.skipRegions.observe(this) { regions ->
            // Update UI for skip sections adapter if visible
        }
    }

    private fun populate(s: Song) {
        song = s
        binding.tvTitle.text = s.title
        binding.tvArtist.text = s.artist

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
        updateShuffleButton()
        updateRepeatButton()
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
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val current = svc.getPosition().toLong()
                val total = svc.getDuration().toLong()
                if (total > 0) {
                    binding.seekPlayback.progress = ((current.toFloat() / total) * 100).toInt()
                    binding.tvCurrentTime.text = formatDuration(current)
                    binding.tvTotalTime.text = formatDuration(total)
                }
                progressHandler.postDelayed(this, 1000)
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun toggleFavorite() {
        song?.let { s ->
            activityScope.launch {
                repository.toggleFavorite(s)
                repository.getSongById(s.id)?.let { populate(it) }
            }
        }
    }

    private fun toggleRepeat() {
        currentRepeatMode = when (currentRepeatMode) {
            MusicService.REPEAT_NONE -> MusicService.REPEAT_ALL
            MusicService.REPEAT_ALL -> MusicService.REPEAT_ONE
            else -> MusicService.REPEAT_NONE
        }
        musicService?.setRepeatMode(currentRepeatMode)
        updateRepeatButton()
    }

    private fun updateShuffleButton() {
        val on = musicService?.isShuffleEnabled() ?: false
        binding.btnShuffle.alpha = if (on) 1.0f else 0.5f
    }

    private fun updateRepeatButton() {
        val mode = musicService?.getRepeatMode() ?: MusicService.REPEAT_NONE
        binding.btnRepeat.alpha = if (mode != MusicService.REPEAT_NONE) 1.0f else 0.5f
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

    override fun onDestroy() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        unbindService(serviceConnection)
        super.onDestroy()
    }

    inner class SectionsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = position
        override fun getItemCount(): Int = 8
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> VolumeViewHolder(ItemNowPlayingVolumeBinding.inflate(inflater, parent, false))
                1 -> PitchViewHolder(ItemNowPlayingPitchBinding.inflate(inflater, parent, false))
                2 -> SpeedViewHolder(ItemNowPlayingSpeedBinding.inflate(inflater, parent, false))
                3 -> AbRepeatViewHolder(
                    ItemNowPlayingAbRepeatBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )

                4 -> TrimViewHolder(ItemNowPlayingTrimBinding.inflate(inflater, parent, false))
                5 -> ProfilesViewHolder(
                    ItemNowPlayingProfilesBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )

                6 -> SkipViewHolder(
                    ItemNowPlayingSkipSectionsBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )

                else -> StateViewHolder(
                    ItemNowPlayingStateToggleBinding.inflate(
                        inflater,
                        parent,
                        false
                    )
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = song ?: return
            when (holder) {
                is VolumeViewHolder -> {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    holder.binding.seekVolume.progress = (cur.toFloat() / max * 100).toInt()
                    holder.binding.tvVolumeValue.text = "${holder.binding.seekVolume.progress}%"
                    holder.binding.seekVolume.setOnSeekBarChangeListener(object :
                        SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            if (fromUser) {
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (p / 100f * max).toInt(),
                                    0
                                )
                                holder.binding.tvVolumeValue.text = "$p%"
                            }
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }

                is PitchViewHolder -> {
                    holder.binding.seekPitch.progress = ((s.pitchSemitones + 6) * 10).toInt()
                    holder.binding.tvPitchValue.text = "%.1f".format(s.pitchSemitones)
                    holder.binding.seekPitch.setOnSeekBarChangeListener(object :
                        SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, f: Boolean) {
                            val st = (p - 60) / 10f
                            holder.binding.tvPitchValue.text = "%.1f".format(st)
                            if (f) musicService?.applyPitchToCurrentSong(st)
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {
                            val st = (sb.progress - 60) / 10f
                            activityScope.launch {
                                repository.updatePitchAndSyncProfile(
                                    songId,
                                    st
                                )
                            }
                        }
                    })
                }

                is SpeedViewHolder -> {
                    holder.binding.seekSpeed.progress = ((s.playbackSpeed - 0.5f) / 0.05f).toInt()
                    holder.binding.tvSpeedValue.text = "%.2fx".format(s.playbackSpeed)
                    holder.binding.seekSpeed.setOnSeekBarChangeListener(object :
                        SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, f: Boolean) {
                            val sp = 0.5f + p * 0.05f
                            holder.binding.tvSpeedValue.text = "%.2fx".format(sp)
                            if (f) musicService?.applySpeedToCurrentSong(sp)
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {
                            val sp = 0.5f + sb.progress * 0.05f
                            activityScope.launch {
                                repository.updateSpeedAndSyncProfile(
                                    songId,
                                    sp
                                )
                            }
                        }
                    })
                }

                is AbRepeatViewHolder -> {
                    holder.binding.btnSetPointA.setOnClickListener {
                        val pos = musicService?.getPosition()?.toLong() ?: 0L
                        viewModel.activeProfile.value?.let {
                            viewModel.updateAbRepeat(
                                it.id,
                                pos,
                                it.abRepeatB,
                                it.abRepeatEnabled
                            )
                        }
                    }
                    holder.binding.btnSetPointB.setOnClickListener {
                        val pos = musicService?.getPosition()?.toLong() ?: 0L
                        viewModel.activeProfile.value?.let {
                            viewModel.updateAbRepeat(
                                it.id,
                                it.abRepeatA,
                                pos,
                                it.abRepeatEnabled
                            )
                        }
                    }
                    holder.binding.switchAbRepeat.setOnCheckedChangeListener { _, checked ->
                        viewModel.activeProfile.value?.let {
                            viewModel.updateAbRepeat(
                                it.id,
                                it.abRepeatA,
                                it.abRepeatB,
                                checked
                            )
                        }
                    }
                }

                is TrimViewHolder -> {
                    val total = musicService?.getDuration() ?: 0
                    holder.binding.seekTrimStart.max = total
                    holder.binding.seekTrimEnd.max = total
                    holder.binding.seekTrimStart.progress = s.trimStart.toInt()
                    holder.binding.seekTrimEnd.progress =
                        (if (s.trimEnd > 0) s.trimEnd else total.toLong()).toInt()
                    holder.binding.tvTrimStart.text = "Start: ${formatDuration(s.trimStart)}"
                    holder.binding.tvTrimEnd.text =
                        "End: ${formatDuration(if (s.trimEnd > 0) s.trimEnd else total.toLong())}"

                    holder.binding.seekTrimStart.setOnSeekBarChangeListener(object :
                        SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, f: Boolean) {
                            if (f) holder.binding.tvTrimStart.text =
                                "Start: ${formatDuration(p.toLong())}"
                        }

                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {
                            activityScope.launch {
                                repository.updateTrimAndSyncProfile(
                                    songId,
                                    sb.progress.toLong(),
                                    s.trimEnd
                                )
                            }
                        }
                    })
                }

                is ProfilesViewHolder -> {
                    holder.binding.btnProfilePrev.setOnClickListener { viewModel.switchToPreviousProfile() }
                    holder.binding.btnProfileNext.setOnClickListener { viewModel.switchToNextProfile() }
                    holder.binding.btnProfileDefault.setOnClickListener { viewModel.switchToDefaultProfile() }
                }

                is StateViewHolder -> {
                    holder.binding.switchPlaybackState.isChecked =
                        !(musicService?.isBypassingProfiles() ?: true)
                    holder.binding.switchPlaybackState.setOnCheckedChangeListener { _, checked ->
                        musicService?.setBypassProfiles(
                            !checked
                        )
                    }
                }
            }
        }
    }

    class VolumeViewHolder(val binding: ItemNowPlayingVolumeBinding) :
        RecyclerView.ViewHolder(binding.root)

    class PitchViewHolder(val binding: ItemNowPlayingPitchBinding) :
        RecyclerView.ViewHolder(binding.root)

    class SpeedViewHolder(val binding: ItemNowPlayingSpeedBinding) :
        RecyclerView.ViewHolder(binding.root)

    class AbRepeatViewHolder(val binding: ItemNowPlayingAbRepeatBinding) :
        RecyclerView.ViewHolder(binding.root)

    class TrimViewHolder(val binding: ItemNowPlayingTrimBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ProfilesViewHolder(val binding: ItemNowPlayingProfilesBinding) :
        RecyclerView.ViewHolder(binding.root)

    class SkipViewHolder(val binding: ItemNowPlayingSkipSectionsBinding) :
        RecyclerView.ViewHolder(binding.root)

    class StateViewHolder(val binding: ItemNowPlayingStateToggleBinding) :
        RecyclerView.ViewHolder(binding.root)
}
