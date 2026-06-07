package com.mochimochi.clawmikia.ui.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mochimochi.clawmikia.MusicVaultApp
import com.mochimochi.clawmikia.R
import androidx.lifecycle.Observer
import com.mochimochi.clawmikia.data.model.Playlist
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.db.FolderInfo
import com.mochimochi.clawmikia.databinding.ActivityMainBinding
import com.mochimochi.clawmikia.service.MusicService
import com.mochimochi.clawmikia.ui.fragments.FavoritesFragment
import com.mochimochi.clawmikia.ui.fragments.FoldersFragment
import com.mochimochi.clawmikia.ui.fragments.LibraryFragment
import com.mochimochi.clawmikia.ui.fragments.PlaylistsFragment
import com.mochimochi.clawmikia.ui.fragments.SettingsFragment
import com.mochimochi.clawmikia.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikia.data.repository.SettingsRepository
import com.mochimochi.clawmikia.utils.FavoriteIconHelper
import com.mochimochi.clawmikia.utils.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            runOnUiThread {
                android.util.Log.d("MainActivity", "Network online")
                // Auto-fetch removed as per user request
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                musicService = (service as MusicService.MusicBinder).getService()
                serviceBound = true
                android.util.Log.d("MainActivity", "Service connected successfully")
                registerServiceCallbacks()
                // Restore mini player if service already has a song
                musicService?.getCurrentSong()?.let { song ->
                    val playing = musicService?.isPlaying() ?: false
                    viewModel.setCurrentSong(song)
                    showMusicPanel(song)
                    updatePlayButton(playing)
                    if (playing) startProgressUpdates()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error in onServiceConnected: ${e.message}")
                serviceBound = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.d("MainActivity", "Service disconnected")
            serviceBound = false
            musicService = null
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            MusicVaultApp.instance.prefs.edit {
                putString(MusicVaultApp.KEY_FOLDER_URI, it.toString())
            }
            viewModel.scanFolder(it)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Fallback for non-persistentable URIs if needed
                }
            }
            viewModel.scanFiles(uris)
        }
    }

    private var exportOnlyUpdated = false
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                val songs = withContext(Dispatchers.IO) {
                    com.mochimochi.clawmikia.data.db.MusicDatabase.getDatabase(this@MainActivity)
                        .songDao().getAllSongsSync()
                }

                if (songs.isEmpty()) {
                    showAestheticStatusDialog(
                        success = false,
                        title = "EMPTY LIBRARY",
                        message = "No songs found in your library to export."
                    )
                    return@launch
                }

                binding.scanProgress.visibility = View.VISIBLE
                val tempFile = File(cacheDir, "export_temp.zip")
                val exportCount = com.mochimochi.clawmikia.utils.Exporter.exportToZip(
                    this@MainActivity,
                    songs,
                    tempFile,
                    exportOnlyUpdated
                )

                if (exportCount > 0 && tempFile.exists()) {
                    try {
                        contentResolver.openOutputStream(it)?.use { output ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        showAestheticStatusDialog(
                            success = true,
                            title = "EXPORT SUCCESS",
                            message = "Successfully exported $exportCount songs to ZIP."
                        )
                    } catch (e: Exception) {
                        showAestheticStatusDialog(
                            success = false,
                            title = "SAVE FAILED",
                            message = "Could not write to the selected location."
                        )
                    }
                } else if (exportCount == 0) {
                    showAestheticStatusDialog(
                        success = false,
                        title = "NO MATCHES",
                        message = if (exportOnlyUpdated)
                            "No updated songs found (excluding those currently playing)."
                        else "No songs found in library to export."
                    )
                } else {
                    showAestheticStatusDialog(
                        success = false,
                        title = "EXPORT FAILED",
                        message = "An error occurred while creating the archive."
                    )
                }
                tempFile.delete()
                binding.scanProgress.visibility = View.GONE
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle bottom system nav bar insets on bottomArea so the
        // BottomNavigationView doesn't add its own automatic bottom padding.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomArea) { view, insets ->
            val navBars =
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBars.bottom)
            // Consume navigation bar insets so children don't re-apply them
            androidx.core.view.WindowInsetsCompat.Builder(insets)
                .setInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
                    androidx.core.graphics.Insets.of(0, 0, 0, 0)
                )
                .build()
        }

        setupNavigation()
        setupMusicPanel()
        setupSearchBar()
        setupResetButton()
        setupUpdateOnlineButton()
        bindToService()
        observeViewModel()
        requestNotificationPermission()
        setupNetworkListener()
        showFragment(LibraryFragment())
        observeFavoriteIconSetting()

        onBackPressedDispatcher.addCallback(this) {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (currentFragment !is LibraryFragment) {
                // Navigate back to Library tab
                binding.bottomNav.selectedItemId = R.id.nav_library
                showFragment(LibraryFragment())
            } else {
                // Default behavior (exit app)
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        viewModel.isPlaying.observe(this) { isPlaying ->
            binding.musicPanel.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
        }
    }

    // ─── Navigation ─────────────────────────────────────────────────────────────

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_library -> showFragment(LibraryFragment())
                R.id.nav_folders -> showFragment(FoldersFragment())
                R.id.nav_favorites -> showFragment(FavoritesFragment())
                R.id.nav_playlists -> showFragment(PlaylistsFragment())
                R.id.nav_settings -> showFragment(SettingsFragment())
            }
            true
        }
    }

    private fun showFragment(fragment: Fragment) {
        val appBar = binding.appBarLayout
        val contentArea = binding.contentArea
        val layoutParams =
            contentArea.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams

        if (fragment is PlaylistsFragment || fragment is SettingsFragment) {
            // 1. Hide the activity header and remove scrolling behavior
            appBar.visibility = View.GONE
            layoutParams?.behavior = null
            contentArea.layoutParams = layoutParams

            // 2. Safe padding injection for top status bar (bottom nav handled by bottomArea)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(contentArea) { view, insets ->
                val systemBars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, systemBars.top, 0, 0)
                insets
            }
        } else {
            // 1. Restore the activity header and scrolling constraints
            appBar.visibility = View.VISIBLE
            layoutParams?.behavior =
                com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
            contentArea.layoutParams = layoutParams

            // 2. Remove extra padding — layout handles spacing naturally
            contentArea.setPadding(0, 0, 0, 0)
        }

        // Execute the fragment transaction
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /** Observe the favorite icon type setting and update the bottom nav icon. */
    private fun observeFavoriteIconSetting() {
        val settingsRepo = SettingsRepository(this)
        settingsRepo.favoriteIconLive.observe(this) { iconType ->
            val iconRes = FavoriteIconHelper.outlineRes(iconType)
            val iconColor = ContextCompat.getColor(this, FavoriteIconHelper.colorRes(iconType))
            val menuItem = binding.bottomNav.menu.findItem(R.id.nav_favorites)
            menuItem?.setIcon(iconRes)
            menuItem?.iconTintList = android.content.res.ColorStateList.valueOf(iconColor)
        }
    }

    // ─── Mini player ─────────────────────────────────────────────────────────────

    private fun setupMusicPanel() {
        binding.musicPanel.root.visibility = View.GONE

        // Play controls
        binding.musicPanel.btnPlayPause.setOnClickListener {
            android.util.Log.d("MainActivity", "Play/pause button clicked")
            if (serviceBound && (musicService != null)) {
                musicService?.togglePlayPause()
            } else {
                android.util.Log.w("MainActivity", "Service not bound when play/pause clicked")
                bindToService()
            }
        }
        binding.musicPanel.btnNext.setOnClickListener {
            if (serviceBound) musicService?.skipNext()
        }
        binding.musicPanel.btnPrev.setOnClickListener {
            if (serviceBound) {
                // Immediate UI feedback for replay/skip
                binding.musicPanel.seekBar.progress = 0
                binding.musicPanel.tvProgress.text = getString(R.string.zero_progress)
                musicService?.skipPrev()
            }
        }

        // Seekbar scrubbing
        binding.musicPanel.seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val svc = musicService ?: return
                        val s = viewModel.currentSong.value ?: return
                        val fullDur = svc.getDuration().toLong()
                        val tStart = s.trimStart
                        val tEnd = if (s.trimEnd > 0L) s.trimEnd else fullDur
                        val effectiveDur = (tEnd - tStart).coerceAtLeast(0L)

                        val targetRelativePos = (progress / 100f * effectiveDur).toLong()
                        svc.seekTo((targetRelativePos + tStart).toInt())
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            },
        )

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
        val params = panel.root.layoutParams

        if (miniPlayerExpanded) {
            // Expanded → show everything
            panel.seekBar.visibility = View.VISIBLE
            panel.layoutControls.visibility = View.VISIBLE

            // Explicitly show these in case they were hidden individually elsewhere
            panel.ivPlayingIndicator.visibility = View.VISIBLE
            panel.tvTitle.visibility = View.VISIBLE
            panel.tvArtist.visibility = View.VISIBLE
            panel.tvProgress.visibility = View.VISIBLE
            panel.btnPrev.visibility = View.VISIBLE
            panel.btnPlayPause.visibility = View.VISIBLE
            panel.btnNext.visibility = View.VISIBLE

            // Reset cropping
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panel.root.layoutParams = params

            // Restore toggle button to natural size (matching layout_mini_player.xml)
            val btnParams = panel.btnToggleMiniPlayer.layoutParams as? RelativeLayout.LayoutParams
            btnParams?.let {
                it.width = (56 * resources.displayMetrics.density).toInt()
                it.height = (64 * resources.displayMetrics.density).toInt()
                panel.btnToggleMiniPlayer.layoutParams = it
            }

            // Expanded background = bg_mini_player (matches layout_mini_player.xml)
            panel.root.setBackgroundResource(R.drawable.bg_mini_player)
        } else {
            // Collapsed → hide seekbar and main controls
            panel.seekBar.visibility = View.GONE
            panel.layoutControls.visibility = View.GONE

            // Shrink the root to just the toggle button width
            val density = resources.displayMetrics.density
            val collapsedWidthPx = (56 * density).toInt()
            params.width = collapsedWidthPx
            params.height = (64 * density).toInt() // Match toggle button height
            panel.root.layoutParams = params

            // Toggle button fills the collapsed strip
            val btnParams = panel.btnToggleMiniPlayer.layoutParams as? RelativeLayout.LayoutParams
            btnParams?.let {
                it.width = RelativeLayout.LayoutParams.MATCH_PARENT
                it.height = RelativeLayout.LayoutParams.MATCH_PARENT
                panel.btnToggleMiniPlayer.layoutParams = it
            }

            // Collapsed background = accent border
            panel.root.setBackgroundResource(R.drawable.mini_player_border_bg)
        }

        panel.root.requestLayout()

        // Always re-assert the text — song changes can wipe it via binding reuse
        panel.btnToggleMiniPlayer.text = "瞼"

        // Accessibility description
        panel.btnToggleMiniPlayer.contentDescription =
            if (miniPlayerExpanded) "Collapse mini player" else "Expand mini player"

        // Tint color change
        val neonPink = ContextCompat.getColor(this, R.color.neon_pink)
        val defaultColor = ContextCompat.getColor(this, R.color.text_hint)
        panel.btnToggleMiniPlayer.setTextColor(
            if (miniPlayerExpanded) defaultColor else neonPink
        )
    }

    // ─── Reset library ───────────────────────────────────────────────────────────

    private fun setupResetButton() {
        binding.btnReset.setOnClickListener {
            showAestheticConfirmDialog(
                title = "Reset Library",
                message = "This will remove all songs from the library database.\n\n" +
                        "Your actual MP3 files will NOT be deleted from your device.\n\n" +
                        "You can re-scan your folder at any time to rebuild the library.",
            ) {
                // Pause playback before wiping the list
                if (musicService?.isPlaying() == true) musicService?.togglePlayPause()
                viewModel.resetLibrary()
            }
        }
    }

    // ─── Search ──────────────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        binding.searchView.setOnQueryTextListener(
            object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    viewModel.setSearchQuery(q ?: ""); return true
                }
            },
        )
        binding.btnScan.setOnClickListener { folderPickerLauncher.launch(null) }
        binding.btnScanFiles.setOnClickListener { filePickerLauncher.launch(arrayOf("audio/mpeg")) }
        binding.btnExport.setOnClickListener { showExportDialog() }
    }

    private fun setupUpdateOnlineButton() {
        binding.btnUpdateOnline.setOnClickListener {
            if (!com.mochimochi.clawmikia.utils.MetadataFetcher.isOnline(this)) {
                showAestheticStatusDialog(
                    success = false,
                    title = "OFFLINE",
                    message = "Please connect to the internet to update metadata from MusicBrainz."
                )
                return@setOnClickListener
            }

            val msg = "Do you want to fetch missing metadata for your library?\n\n" +
                    "This will look for album art and song details online.\n\n" +
                    "NOTE: Manually edited songs will be skipped to preserve your changes."

            AlertDialog.Builder(this)
                .setTitle("ONLINE METADATA UPDATE")
                .setMessage(msg)
                .setPositiveButton("PROCEED") { _, _ ->
                    viewModel.fetchMetadataManual(overwriteManual = false)
                }
                .setNeutralButton("OVERWRITE ALL") { _, _ ->
                    // Optional: allow user to overwrite even manual edits if they really want to
                    viewModel.fetchMetadataManual(overwriteManual = true)
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun showExportDialog() {
        val options = arrayOf("All Songs", "Only Updated Songs")
        AlertDialog.Builder(this)
            .setTitle("Export Library to ZIP")
            .setItems(options) { _, which ->
                exportOnlyUpdated = (which == 1)
                exportLauncher.launch("MusicVault_Export.zip")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Notifications ───────────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val p = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, p) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(p)
            }
        }
    }

    private fun setupNetworkListener() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    // ─── Service ─────────────────────────────────────────────────────────────────

    private fun bindToService() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private val songChangedCallback: (Song) -> Unit = { song ->
        runOnUiThread {
            viewModel.setCurrentSong(song)
            viewModel.incrementPlayCount(song.id)
            showMusicPanel(song)
            updatePlayButton(true)
            startProgressUpdates()
        }
    }

    private val playStateCallback: (Boolean) -> Unit = { playing ->
        runOnUiThread {
            viewModel.setPlaying(playing)
            updatePlayButton(playing)
            if (playing) startProgressUpdates() else stopProgressUpdates()
        }
    }

    /** Always called on connect and on every onResume so callbacks are never stale. */
    private fun registerServiceCallbacks() {
        musicService?.let { service ->
            service.removeSongChangedCallback(songChangedCallback)
            service.removePlayStateCallback(playStateCallback)
            service.addSongChangedCallback(songChangedCallback)
            service.addPlayStateCallback(playStateCallback)
        }
    }

    // ─── ViewModel observations ──────────────────────────────────────────────────

    private fun observeViewModel() {
        // Keep these "warm" so .value is available for dialogs
        viewModel.allPlaylists.observe(this) { }
        viewModel.folders.observe(this) { }

        viewModel.manuallyEditedCount.observe(this) { count ->
            binding.tvManualCount.text = count.toString()
            binding.tvManualCount.visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        viewModel.scanStatus.observe(this) { status ->
            when (status) {
                is MainViewModel.ScanStatus.Scanning -> {
                    binding.scanProgress.visibility = View.VISIBLE
                }

                is MainViewModel.ScanStatus.FetchingMetadata -> {
                    binding.scanProgress.visibility = View.VISIBLE
                    binding.scanProgress.indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.neon_cyan)
                        )
                }

                is MainViewModel.ScanStatus.Success -> {
                    binding.scanProgress.visibility = View.GONE
                    binding.scanProgress.indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.neon_pink)
                        )
                    showAestheticStatusDialog(
                        success = true,
                        title = "SCAN COMPLETE",
                        message = "Found ${status.count} songs and added them to your library."
                    )
                }

                is MainViewModel.ScanStatus.Empty -> {
                    binding.scanProgress.visibility = View.GONE
                    showAestheticStatusDialog(
                        success = false,
                        title = "NO SONGS FOUND",
                        message = "No MP3 files were found in the selected location."
                    )
                }

                is MainViewModel.ScanStatus.Reset -> {
                    binding.scanProgress.visibility = View.GONE
                    // Hide the mini player — no song is loaded any more
                    binding.musicPanel.root.visibility = View.GONE
                    stopProgressUpdates()
                    showAestheticStatusDialog(
                        success = true,
                        title = "LIBRARY RESET",
                        message = "Library cleared. Tap the folder icon to re-scan."
                    )
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

        android.util.Log.d(
            "MainActivity",
            "playSong called: ${song.title}, pitch=${song.pitchSemitones}, speed=${song.playbackSpeed}, volume=${song.volume}, trimStart=${song.trimStart}, trimEnd=${song.trimEnd}, repeat=${song.repeatMode}"
        )

        // Show mini player right away — no waiting for callbacks
        showMusicPanel(song)
        updatePlayButton(playing = true)
        startProgressUpdates()

        // Ensure service is bound before calling it
        if (serviceBound && musicService != null) {
            android.util.Log.d("MainActivity", "Service bound, calling setPlaylist")
            musicService?.setPlaylist(list, idx)
            viewModel.setPlaying(true)
        } else {
            android.util.Log.d("MainActivity", "Service not bound, binding first")
            // Service not bound yet, bind and then play
            bindToService()
            // Postpone the play call until service is connected
            musicService?.let { service ->
                android.util.Log.d(
                    "MainActivity",
                    "Service available after binding, calling setPlaylist"
                )
                service.setPlaylist(list, idx)
                viewModel.setPlaying(true)
            } ?: run {
                android.util.Log.d("MainActivity", "Service still null, retrying after delay")
                // If service is still null, try again after a delay

                progressHandler.postDelayed({
                    musicService?.setPlaylist(list, idx)
                    viewModel.setPlaying(true)
                }, 500)
            }
        }
    }

    /**
     * Updates the current playlist in the service without starting playback.
     * Used to keep the "Now Playing" context in sync with the current layout.
     */
    fun updateCurrentPlaylist(playlist: List<Song>) {
        if (serviceBound) {
            musicService?.updatePlaylistOnly(playlist)
        }
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
                val s = viewModel.currentSong.value ?: return

                val fullDur = svc.getDuration().toLong()
                val trimStart = s.trimStart
                val trimEnd = if (s.trimEnd > 0L) s.trimEnd else fullDur
                val effectiveDur = (trimEnd - trimStart).coerceAtLeast(0L)

                val absolutePos = svc.getPosition().toLong()
                val relativePos = (absolutePos - trimStart).coerceAtLeast(0L)

                if (fullDur > 0) {
                    if (effectiveDur > 0) {
                        binding.musicPanel.seekBar.progress =
                            ((relativePos.toFloat() / effectiveDur) * 100).toInt().coerceIn(0, 100)
                        binding.musicPanel.tvProgress.text = getString(
                            R.string.progress_format,
                            formatDuration(relativePos),
                            formatDuration(effectiveDur),
                        )
                    } else {
                        binding.musicPanel.seekBar.progress = 0
                        binding.musicPanel.tvProgress.text = getString(
                            R.string.progress_format,
                            "0:00",
                            formatDuration(fullDur),
                        )
                    }
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
        // viewModel.fetchMetadataIfOnline() // Removed as per request
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

    override fun onStop() {
        super.onStop()
        // Allow music to continue playing when app goes to background
        // Music will only stop when app is actually destroyed or swiped away
    }

    override fun onDestroy() {
        if (::connectivityManager.isInitialized) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
        if (serviceBound) {
            musicService?.let { service ->
                service.removeSongChangedCallback(songChangedCallback)
                service.removePlayStateCallback(playStateCallback)
            }
            unbindService(serviceConnection)
        }
        stopProgressUpdates()
        super.onDestroy()
    }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticConfirmDialog(
        title: String,
        message: String,
        onPositive: () -> Unit,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.tvTitle).text = title
        dialogView.findViewById<TextView>(R.id.tvMessage).text = message

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnConfirm).setOnClickListener {
            onPositive()
            dialog.dismiss()
        }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.80).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun showAddToPlaylistDialog(song: Song) {
        val liveData = viewModel.allPlaylists
        val observer = object : Observer<List<Playlist>> {
            override fun onChanged(value: List<Playlist>) {
                liveData.removeObserver(this)
                if (value.isEmpty()) {
                    showSnackbar("No playlists found. Create one first.")
                } else {
                    showAestheticSelectionDialog(
                        title = "ADD TO PLAYLIST",
                        items = value,
                        itemLabel = { it.name },
                        itemIcon = { R.drawable.ic_playlist },
                        onItemSelected = { playlist ->
                            viewModel.addSongToPlaylist(playlist.id, song.id)
                            showAestheticStatusDialog(
                                success = true,
                                title = "SONG ADDED",
                                message = "Added \"${song.title}\" to ${playlist.name}"
                            )
                        }
                    )
                }
            }
        }
        liveData.observe(this, observer)
    }

    fun showAddToPlaylistDialogMultiple(songIds: List<Long>) {
        val liveData = viewModel.allPlaylists
        val observer = object : Observer<List<Playlist>> {
            override fun onChanged(value: List<Playlist>) {
                liveData.removeObserver(this)
                if (value.isEmpty()) {
                    showAestheticStatusDialog(
                        success = false,
                        title = "NO PLAYLISTS",
                        message = "Create a playlist first before adding songs."
                    )
                } else {
                    showAestheticSelectionDialog(
                        title = "ADD SONGS TO PLAYLIST",
                        items = value,
                        itemLabel = { it.name },
                        itemIcon = { R.drawable.ic_playlist },
                        onItemSelected = { playlist ->
                            viewModel.addSongsToPlaylist(playlist.id, songIds)
                            showAestheticStatusDialog(
                                success = true,
                                title = "SONGS ADDED",
                                message = "Successfully added ${songIds.size} songs to ${playlist.name}"
                            )
                        }
                    )
                }
            }
        }
        liveData.observe(this, observer)
    }

    fun showSongOptionsDialog(song: Song) {
        val options = listOf(
            "Add to Playlist" to R.drawable.ic_playlist,
            "Move to Folder" to R.drawable.ic_folder,
            "Delete Song" to R.drawable.ic_delete
        )
        showAestheticSelectionDialog(
            title = song.title.uppercase(),
            items = options,
            itemLabel = { it.first },
            itemIcon = { it.second },
            onItemSelected = { option ->
                when (option.first) {
                    "Add to Playlist" -> showAddToPlaylistDialog(song)
                    "Move to Folder" -> showMoveSongDialog(song)
                    "Delete Song" -> showDeleteConfirmDialog(song)
                }
            }
        )
    }

    private fun showMoveSongDialog(song: Song) {
        val liveData = viewModel.folders
        val observer = object : Observer<List<FolderInfo>> {
            override fun onChanged(value: List<FolderInfo>) {
                liveData.removeObserver(this)
                if (value.isEmpty()) {
                    showSnackbar("No folders found.")
                } else {
                    showAestheticSelectionDialog(
                        title = "MOVE TO FOLDER",
                        items = value,
                        itemLabel = { it.folderName },
                        itemIcon = { R.drawable.ic_folder },
                        onItemSelected = { target ->
                            viewModel.moveSong(
                                song.id,
                                target.folderPath,
                                target.folderName,
                                song.filePath
                            )
                            showAestheticStatusDialog(
                                success = true,
                                title = "SONG MOVED",
                                message = "Moved \"${song.title}\" to ${target.folderName}"
                            )
                        }
                    )
                }
            }
        }
        liveData.observe(this, observer)
    }

    fun showDeleteConfirmDialog(song: Song) {
        showAestheticConfirmDialog(
            title = "DELETE SONG",
            message = "Are you sure you want to delete \"${song.title}\" from your library?\n\nThis only removes it from the app database."
        ) {
            viewModel.deleteSong(song)
            showSnackbar("Song deleted")
        }
    }

    private fun <T> showAestheticSelectionDialog(
        title: String,
        items: List<T>,
        itemLabel: (T) -> String,
        itemIcon: (T) -> Int,
        onItemSelected: (T) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_selection, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvSelection)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        tvTitle.text = title

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int
            ): RecyclerView.ViewHolder {
                val v = layoutInflater.inflate(R.layout.item_selection_dialog, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = items[position]
                holder.itemView.findViewById<TextView>(R.id.tvItemName).text = itemLabel(item)
                holder.itemView.findViewById<ImageView>(R.id.ivItemIcon)
                    .setImageResource(itemIcon(item))
                holder.itemView.setOnClickListener {
                    onItemSelected(item)
                    dialog.dismiss()
                }
            }

            override fun getItemCount() = items.size
        }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.80).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun showAestheticStatusDialog(
        success: Boolean,
        title: String,
        message: String
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_status, null)
        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivStatusIcon)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvMessage)
        val btnOk = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnOk)

        tvTitle.text = title
        tvMessage.text = message

        if (success) {
            ivIcon.setImageResource(R.drawable.ic_check)
            ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neon_green))
            btnOk.text = "GREAT"
            btnOk.setTextColor(ContextCompat.getColor(this, R.color.neon_green))
            btnOk.setBackgroundResource(R.drawable.bg_button_outline_green)
        } else {
            ivIcon.setImageResource(R.drawable.ic_close)
            ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neon_red))
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.neon_red))
            btnOk.text = "BUMMER"
            btnOk.setTextColor(ContextCompat.getColor(this, R.color.neon_red))
            btnOk.setBackgroundResource(R.drawable.bg_button_outline_red)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.80).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
