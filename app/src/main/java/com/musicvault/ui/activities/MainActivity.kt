package com.musicvault.ui.activities

import android.content.*
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.musicvault.MusicVaultApp
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.databinding.ActivityMainBinding
import com.musicvault.service.MusicService
import com.musicvault.ui.fragments.FoldersFragment
import com.musicvault.ui.fragments.LibraryFragment
import com.musicvault.ui.fragments.FavoritesFragment
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
            setupServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            MusicVaultApp.instance.prefs.edit()
                .putString(MusicVaultApp.KEY_FOLDER_URI, it.toString())
                .apply()
            viewModel.scanFolder(it)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — notification will show if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSystemBars()
        setupNavigation()
        setupMusicPanel()
        setupSearchBar()
        startAndBindService()
        observeViewModel()
        requestNotificationPermission()

        // Show library by default
        showFragment(LibraryFragment())

        // Auto-scan if we have a saved folder
        val savedUri = MusicVaultApp.instance.prefs.getString(MusicVaultApp.KEY_FOLDER_URI, null)
        if (savedUri != null) {
            checkAndRescan(savedUri)
        }
    }

    private fun setupSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun setupMusicPanel() {
        binding.musicPanel.root.visibility = View.GONE

        binding.musicPanel.btnPlayPause.setOnClickListener {
            musicService?.togglePlayPause()
        }
        binding.musicPanel.btnNext.setOnClickListener {
            musicService?.skipNext()
        }
        binding.musicPanel.btnPrev.setOnClickListener {
            musicService?.skipPrev()
        }
        binding.musicPanel.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ms = (progress / 100f * (musicService?.getDuration() ?: 1)).toInt()
                    musicService?.seekTo((ms + (viewModel.currentSong.value?.trimStart?.toInt() ?: 0)))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.musicPanel.root.setOnClickListener {
            val song = viewModel.currentSong.value ?: return@setOnClickListener
            NowPlayingActivity.start(this, song.id)
        }
    }

    private fun setupSearchBar() {
        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                viewModel.setSearchQuery(q ?: "")
                return true
            }
        })

        binding.btnScan.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupServiceCallbacks() {
        musicService?.onSongChanged = { song ->
            runOnUiThread {
                viewModel.setCurrentSong(song)
                viewModel.incrementPlayCount(song.id)
                showMusicPanel(song)
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
                is MainViewModel.ScanStatus.Scanning ->
                    binding.scanProgress.visibility = View.VISIBLE
                is MainViewModel.ScanStatus.Success -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("Found ${status.count} songs")
                }
                is MainViewModel.ScanStatus.Empty -> {
                    binding.scanProgress.visibility = View.GONE
                    showSnackbar("No MP3 files found in selected folder")
                }
                else -> binding.scanProgress.visibility = View.GONE
            }
        }
    }

    fun playSong(song: Song, playlist: List<Song> = emptyList()) {
        val list = if (playlist.isEmpty()) listOf(song) else playlist
        val idx = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        musicService?.setPlaylist(list, idx)
    }

    private fun showMusicPanel(song: Song) {
        binding.musicPanel.root.visibility = View.VISIBLE
        binding.musicPanel.tvTitle.text = song.title
        binding.musicPanel.tvArtist.text = song.artist
        binding.musicPanel.seekBar.progress = 0
    }

    private fun updatePlayButton(playing: Boolean) {
        binding.musicPanel.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun startProgressUpdates() {
        progressRunnable = object : Runnable {
            override fun run() {
                val service = musicService ?: return
                val pos = service.getPosition() - (viewModel.currentSong.value?.trimStart?.toInt() ?: 0)
                val dur = service.getDuration()
                if (dur > 0) {
                    val prog = ((pos.coerceAtLeast(0).toFloat() / dur) * 100).toInt()
                    binding.musicPanel.seekBar.progress = prog.coerceIn(0, 100)
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
    }

    private fun checkAndRescan(uriStr: String) {
        // Already scanned, no need to rescan on every launch
    }

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.fetchMetadataIfOnline()
    }

    override fun onDestroy() {
        if (serviceBound) unbindService(serviceConnection)
        stopProgressUpdates()
        super.onDestroy()
    }
}