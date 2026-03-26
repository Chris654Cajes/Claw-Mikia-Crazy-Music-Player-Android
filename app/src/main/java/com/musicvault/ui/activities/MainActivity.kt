package com.musicvault.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.musicvault.MusicVaultApp
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.databinding.ActivityMainBinding
import com.musicvault.service.MusicService
import com.musicvault.ui.fragments.FavoritesFragment
import com.musicvault.ui.fragments.FoldersFragment
import com.musicvault.ui.fragments.LibraryFragment
import com.musicvault.ui.viewmodels.MainViewModel
import com.musicvault.utils.formatDuration

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    private var musicService: MusicService? = null
    private var serviceBound = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    // Tracks whether the mini player body (seekbar + info rows) is visible.
    // The panel's root stays visible as long as a song is loaded; only the
    // inner content is collapsed when the user taps the toggle button.
    private var miniPlayerExpanded = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            serviceBound = true
            registerServiceCallbacks()
            // Restore mini player if service already has a song
            musicService?.getCurrentSong()?.let { song ->
                val playing = musicService?.isPlaying() ?: false
                viewModel.setCurrentSong(song)
                showMusicPanel(song)
                updatePlayButton(playing)
                if (playing) startProgressUpdates()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            MusicVaultApp.instance.prefs.edit()
                .putString(MusicVaultApp.KEY_FOLDER_URI, it.toString()).apply()
            viewModel.scanFolder(it)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        setupNavigation()
        setupMusicPanel()
        setupSearchBar()
        setupResetButton()
        bindToService()
        observeViewModel()
        requestNotificationPermission()
        showFragment(LibraryFragment())

        viewModel.isPlaying.observe(this) { isPlaying ->
            binding.musicPanel.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    // ─── System bars ────────────────────────────────────────────────────────────

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
    }

    // ─── Navigation ─────────────────────────────────────────────────────────────

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> showFragment(LibraryFragment())
                R.id.nav_folders -> showFragment(FoldersFragment())
                R.id.nav_favorites -> showFragment(FavoritesFragment())
            }
            true
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment).commit()
    }

    // ─── Mini player ─────────────────────────────────────────────────────────────

    private fun setupMusicPanel() {
        binding.musicPanel.root.visibility = View.GONE

        // Play controls
        binding.musicPanel.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        binding.musicPanel.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.musicPanel.btnPrev.setOnClickListener { musicService?.skipPrev() }

        // Seekbar scrubbing
        binding.musicPanel.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ms = (progress / 100f * (musicService?.getDuration() ?: 1)).toInt()
                    musicService?.seekTo(
                        ms + (viewModel.currentSong.value?.trimStart?.toInt() ?: 0)
                    )
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Tap the info area (not a button) → open Now Playing
        binding.musicPanel.root.setOnClickListener {
            viewModel.currentSong.value?.let { NowPlayingActivity.start(this, it.id) }
        }

        // ── Show / Hide mini player toggle ──────────────────────────────────────
        // The toggle button collapses/expands the seekbar row and the song-info
        // text, leaving only the playback buttons visible in the collapsed state.
        // We do NOT hide the entire root — that would break the click-to-open
        // gesture and the bottom-nav layout anchor.
        binding.musicPanel.btnToggleMiniPlayer.setOnClickListener {
            miniPlayerExpanded = !miniPlayerExpanded
            applyMiniPlayerExpansion()
        }
    }

    /**
     * Applies the current [miniPlayerExpanded] state to the mini player views.
     * Collapsed: seekbar + song-info text hidden, chevron points right (→ expand).
     * Expanded:  everything visible, chevron points down (↓ collapse).
     */
    private fun applyMiniPlayerExpansion() {
        val panel = binding.musicPanel
        val contentVis = if (miniPlayerExpanded) View.VISIBLE else View.GONE

        panel.seekBar.visibility = contentVis
        panel.ivPlayingIndicator.visibility = contentVis
        panel.tvTitle.visibility = contentVis
        panel.tvArtist.visibility = contentVis
        panel.tvProgress.visibility = contentVis

        panel.btnToggleMiniPlayer.setImageResource(
            if (miniPlayerExpanded) R.drawable.ic_chevron_down
            else R.drawable.ic_chevron_right
        )
        panel.btnToggleMiniPlayer.contentDescription =
            if (miniPlayerExpanded) "Collapse mini player" else "Expand mini player"
    }

    // ─── Reset library ───────────────────────────────────────────────────────────

    private fun setupResetButton() {
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Library")
                .setMessage(
                    "This will remove all songs from the library database.\n\n" +
                            "Your actual MP3 files will NOT be deleted from your device.\n\n" +
                            "You can re-scan your folder at any time to rebuild the library."
                )
                .setPositiveButton("Reset") { _, _ ->
                    // Pause playback before wiping the list
                    if (musicService?.isPlaying() == true) musicService?.togglePlayPause()
                    viewModel.resetLibrary()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─── Search ──────────────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                viewModel.setSearchQuery(q ?: ""); return true
            }
        })
        binding.btnScan.setOnClickListener { folderPickerLauncher.launch(null) }
    }

    // ─── Notifications ───────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val p = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notificationPermissionLauncher.launch(p)
        }
    }

    // ─── Service ─────────────────────────────────────────────────────────────────

    private fun bindToService() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    /** Always called on connect and on every onResume so callbacks are never stale. */
    private fun registerServiceCallbacks() {
        musicService?.onSongChanged = { song ->
            runOnUiThread {
                viewModel.setCurrentSong(song)
                viewModel.incrementPlayCount(song.id)
                showMusicPanel(song)
                updatePlayButton(true)
                startProgressUpdates()
            }
        }
        musicService?.onPlayStateChanged = { playing ->
            runOnUiThread {
                viewModel.setPlaying(playing)
                updatePlayButton(playing)
                if (playing) startProgressUpdates() else stopProgressUpdates()
            }
        }
    }

    // ─── ViewModel observations ──────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.scanStatus.observe(this) { status ->
            when (status) {
                is MainViewModel.ScanStatus.Scanning -> {
                    binding.scanProgress.visibility = View.VISIBLE
                }
                is MainViewModel.ScanStatus.Success -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("Found ${status.count} songs")
                }
                is MainViewModel.ScanStatus.Empty -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("No MP3 files found")
                }
                is MainViewModel.ScanStatus.Reset -> {
                    binding.scanProgress.visibility = View.GONE
                    // Hide the mini player — no song is loaded any more
                    binding.musicPanel.root.visibility = View.GONE
                    stopProgressUpdates()
                    showSnackbar("Library cleared. Tap the folder icon to re-scan.")
                }
                else -> binding.scanProgress.visibility = View.GONE
            }
        }
    }

    // ─── Public API for fragments ────────────────────────────────────────────────

    /**
     * Entry point called by every fragment when a song row is tapped.
     * Shows mini player immediately and sends the playlist to the service.
     */
    fun playSong(song: Song, playlist: List<Song>) {
        val list = playlist.ifEmpty { listOf(song) }
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        // Show mini player right away — no waiting for callbacks
        showMusicPanel(song)
        updatePlayButton(true)
        startProgressUpdates()
        // Tell the service to play — it will fire onSongChanged which re-confirms state
        musicService?.setPlaylist(list, idx)
        viewModel.setPlaying(true)
    }

    fun showMusicPanel(song: Song) {
        binding.musicPanel.root.visibility = View.VISIBLE
        binding.musicPanel.tvTitle.text = song.title
        binding.musicPanel.tvArtist.text = song.artist
        binding.musicPanel.seekBar.progress = 0
        // Restore expansion state (in case the user had collapsed it before)
        applyMiniPlayerExpansion()
    }

    fun updatePlayButton(playing: Boolean) {
        binding.musicPanel.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    // ─── Progress updates ────────────────────────────────────────────────────────

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                val svc = musicService ?: return
                val pos = svc.getPosition() - (viewModel.currentSong.value?.trimStart?.toInt() ?: 0)
                val dur = svc.getDuration()
                if (dur > 0) {
                    binding.musicPanel.seekBar.progress =
                        ((pos.coerceAtLeast(0).toFloat() / dur) * 100).toInt().coerceIn(0, 100)
                    binding.musicPanel.tvProgress.text =
                        "${formatDuration(pos.toLong())} / ${formatDuration(dur.toLong())}"
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

    // ─── Snackbar ────────────────────────────────────────────────────────────────

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        viewModel.fetchMetadataIfOnline()
        if (serviceBound) {
            // Re-register callbacks every resume — NowPlayingActivity nulls them on destroy
            registerServiceCallbacks()
            // Re-sync mini player state
            musicService?.getCurrentSong()?.let { song ->
                val playing = musicService?.isPlaying() ?: false
                viewModel.setCurrentSong(song)
                showMusicPanel(song)
                updatePlayButton(playing)
                if (playing) startProgressUpdates() else stopProgressUpdates()
            }
        }
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(serviceConnection)
        stopProgressUpdates()
        super.onDestroy()
    }
}