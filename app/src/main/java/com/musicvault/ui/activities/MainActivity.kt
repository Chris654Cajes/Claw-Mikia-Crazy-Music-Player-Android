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
        bindToService()
        observeViewModel()
        requestNotificationPermission()
        showFragment(LibraryFragment())
        MusicVaultApp.instance.prefs.getString(MusicVaultApp.KEY_FOLDER_URI, null)
            ?.let { /* no rescan */ }

        viewModel.isPlaying.observe(this) { isPlaying ->
            if (isPlaying) {
                binding.musicPanel.btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                binding.musicPanel.btnPlayPause.setImageResource(R.drawable.ic_play)
            }
        }
    }

    private fun setupSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
    }

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

    private fun setupMusicPanel() {
        binding.musicPanel.root.visibility = View.GONE
        binding.musicPanel.btnPlayPause.setOnClickListener { musicService?.togglePlayPause() }
        binding.musicPanel.btnNext.setOnClickListener { musicService?.skipNext() }
        binding.musicPanel.btnPrev.setOnClickListener { musicService?.skipPrev() }
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
        binding.musicPanel.root.setOnClickListener {
            viewModel.currentSong.value?.let { NowPlayingActivity.start(this, it.id) }
        }
    }

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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val p = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(
                    this,
                    p
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            )
                notificationPermissionLauncher.launch(p)
        }
    }

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

    private fun observeViewModel() {
        viewModel.scanStatus.observe(this) { status ->
            when (status) {
                is MainViewModel.ScanStatus.Scanning -> binding.scanProgress.visibility =
                    View.VISIBLE

                is MainViewModel.ScanStatus.Success -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("Found ${status.count} songs")
                }
                is MainViewModel.ScanStatus.Empty -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("No MP3 files found")
                }
                else -> binding.scanProgress.visibility = View.GONE
            }
        }
    }

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
    }

    fun updatePlayButton(playing: Boolean) {
        binding.musicPanel.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

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

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()
    }

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
