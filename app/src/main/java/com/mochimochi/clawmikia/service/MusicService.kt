package com.mochimochi.clawmikia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.asFlow
import androidx.media.app.NotificationCompat.MediaStyle
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.audio.analysis.AnalysisEngine
import com.mochimochi.clawmikia.audio.dsp.DSPProcessor
import com.mochimochi.clawmikia.data.model.PlaybackProfile
import com.mochimochi.clawmikia.data.model.SkipRegion
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.data.repository.PlaylistRepository
import com.mochimochi.clawmikia.data.repository.ProfileRepository
import com.mochimochi.clawmikia.data.repository.SongRepository
import com.mochimochi.clawmikia.lyrics.LyricsManager
import com.mochimochi.clawmikia.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.pow

class MusicService : Service() {

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var currentArt: Bitmap? = null
    private var activeProfile: PlaybackProfile? = null
    private var skipRegions: List<SkipRegion> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var repeatMode = REPEAT_NONE
    private var shuffleEnabled = false
    private var shuffledIndices: List<Int> = emptyList()
    private var bypassProfiles = false

    private var mediaSession: MediaSessionCompat? = null
    private var currentSongJob: Job? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loadJob: Job? = null

    private lateinit var repository: SongRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var lyricsManager: LyricsManager
    private lateinit var analysisEngine: AnalysisEngine
    private var dspProcessor: DSPProcessor? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var trimWatchdog: Runnable? = null
    private var skipWatchdog: Runnable? = null
    private var loopWatchdog: Runnable? = null
    private var abRepeatWatchdog: Runnable? = null

    private val playStateCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val songChangedCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Song) -> Unit>()

    private var isPlayingRequested = false
    private var isPrepared = false
    private var lastSeekTime = 0L
    private var lastSkipPrevTime = 0L

    companion object {
        const val NOTIF_CHANNEL = "music_vault_channel"
        const val NOTIF_ID = 101
        const val REPEAT_NONE = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
        const val REPEAT_AUTO = 3
        const val ACTION_PLAY = "com.mochimochi.clawmikia.PLAY"
        const val ACTION_PAUSE = "com.mochimochi.clawmikia.PAUSE"
        const val ACTION_NEXT = "com.mochimochi.clawmikia.NEXT"
        const val ACTION_PREV = "com.mochimochi.clawmikia.PREV"
        const val ACTION_STOP = "com.mochimochi.clawmikia.STOP"
        private const val SEMITONE_RATIO = 1.0594630943592953
        private const val WATCHDOG_MS = 250L
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY -> resume()
                ACTION_PAUSE -> pause()
                ACTION_NEXT -> skipNext()
                ACTION_PREV -> skipPrev()
                ACTION_STOP -> {
                    stopSelf(); notifyPlayState(false)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        repository = SongRepository(applicationContext)
        profileRepository = ProfileRepository(applicationContext)
        playlistRepository = PlaylistRepository(applicationContext)
        lyricsManager = LyricsManager(applicationContext)
        analysisEngine = AnalysisEngine(applicationContext)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val prefs = com.mochimochi.clawmikia.MusicVaultApp.instance.prefs
        repeatMode =
            prefs.getInt(com.mochimochi.clawmikia.MusicVaultApp.KEY_REPEAT_MODE, REPEAT_NONE)
        shuffleEnabled =
            prefs.getBoolean(com.mochimochi.clawmikia.MusicVaultApp.KEY_SHUFFLE_ON, false)

        createNotificationChannel()
        initMediaSession()

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY); addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT); addAction(ACTION_PREV); addAction(ACTION_STOP)
        }
        registerReceiver(
            notifReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        )
    }

    fun addPlayStateCallback(cb: (Boolean) -> Unit) {
        playStateCallbacks.add(cb); cb(isPlayingRequested)
    }

    fun removePlayStateCallback(cb: (Boolean) -> Unit) {
        playStateCallbacks.remove(cb)
    }

    fun addSongChangedCallback(cb: (Song) -> Unit) {
        songChangedCallbacks.add(cb); currentSong?.let { cb(it) }
    }

    fun removeSongChangedCallback(cb: (Song) -> Unit) {
        songChangedCallbacks.remove(cb)
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (shuffleEnabled) rebuildShuffleIndices()
        playCurrent(forceReload = true)
    }

    private fun playCurrent(forceReload: Boolean = false) {
        lastSkipPrevTime = 0L // Reset double-tap timer on any manual or automatic song change
        if (playlist.isEmpty()) return
        val targetSong = playlist[currentIndex]

        if (!forceReload && currentSong?.id == targetSong.id && isPrepared) {
            resume(); return
        }

        loadJob?.cancel()
        loadJob = serviceScope.launch {
            // 1. Get minimal data needed to start player immediately
            val freshSong = withContext(Dispatchers.IO) {
                runCatching { repository.getSongById(targetSong.id) }.getOrNull()
            } ?: targetSong

            val profile = withContext(Dispatchers.IO) {
                profileRepository.getOrCreateActiveProfile(freshSong.id)
            }
            val regions = withContext(Dispatchers.IO) {
                profileRepository.getEnabledSkipRegions(freshSong.id)
            }

            // 2. Start Playback NOW
            playlist = playlist.toMutableList().also { it[currentIndex] = freshSong }
            activeProfile = profile
            skipRegions = regions
            currentArt = null // Clear old art while loading new
            playFreshSong(freshSong, profile)

            // 3. Load metadata and assets in background (won't block playback)
            launch {
                if (freshSong.albumArtUrl.isNotBlank()) {
                    val art = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeStream(URL(freshSong.albumArtUrl).openStream()) }.getOrNull()
                    }
                    if (art != null && currentSong?.id == freshSong.id) {
                        currentArt = art
                        updateNotification()
                    }
                }
            }

            launch(Dispatchers.IO) { lyricsManager.loadForSong(freshSong.id, freshSong.filePath) }
            analysisEngine.analyzeIfNeeded(freshSong.id, freshSong.filePath, freshSong.duration)
        }
    }

    private fun playFreshSong(song: Song, profile: PlaybackProfile) {
        currentSong = song; notifySongChanged(song)

        // Observe this song in DB for any metadata updates (like automatic online renaming)
        currentSongJob?.cancel()
        currentSongJob = serviceScope.launch {
            repository.getSongByIdLiveData(song.id).asFlow().collect { fresh ->
                if (fresh != null && (fresh.title != currentSong?.title || fresh.artist != currentSong?.artist || fresh.albumName != currentSong?.albumName || fresh.albumArtUrl != currentSong?.albumArtUrl)) {
                    currentSong = fresh
                    notifySongChanged(fresh)
                    updateNotification()
                    // If album art changed, reload it
                    if (fresh.albumArtUrl.isNotBlank() && fresh.albumArtUrl != song.albumArtUrl) {
                        launch {
                            val art = withContext(Dispatchers.IO) {
                                runCatching { BitmapFactory.decodeStream(URL(fresh.albumArtUrl).openStream()) }.getOrNull()
                            }
                            if (art != null && currentSong?.id == fresh.id) {
                                currentArt = art
                                updateNotification()
                            }
                        }
                    }
                }
            }
        }

        cancelWatchdogs(); isPrepared = false; isPlayingRequested = true; notifyPlayState(true)

        try {
            mediaPlayer?.let { runCatching { it.stop() }; it.reset() } ?: run {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build()
                    )
                    setWakeMode(this@MusicService, PowerManager.PARTIAL_WAKE_LOCK)
                }
            }

            mediaPlayer?.apply {
                setDataSource(this@MusicService, Uri.parse(song.filePath))
                setOnPreparedListener { mp ->
                    isPrepared = true
                    lastSeekTime = System.currentTimeMillis()

                    dspProcessor?.release()
                    dspProcessor =
                        runCatching {
                            DSPProcessor(mp.audioSessionId).also {
                                if (!bypassProfiles) it.applyProfile(profile)
                                else it.disableAll()
                            }
                        }.getOrNull()

                    if (!bypassProfiles) {
                        applyPlaybackParams(profile.pitchSemitones, profile.playbackSpeed)
                        applyVolumeInternal(profile)
                    } else {
                        applyPlaybackParams(0f, 1f)
                        runCatching { mp.setVolume(1f, 1f) }
                    }

                    val startPos = if (bypassProfiles) 0 else profile.trimStart.toInt()
                    if (startPos > 0) mp.seekTo(startPos)

                    if (isPlayingRequested) {
                        if (requestAudioFocus()) {
                            mp.start()
                            notifyPlayState(true)
                            if (!bypassProfiles) startWatchdogs(profile)
                        } else {
                            isPlayingRequested = false; notifyPlayState(false)
                        }
                    } else {
                        notifyPlayState(false)
                    }
                }
                setOnCompletionListener {
                    isPrepared = false; cancelWatchdogs()
                    serviceScope.launch {
                        playlistRepository.recordPlay(
                            song.id,
                            song.duration,
                            true
                        )
                    }
                    advanceOrStop()
                }
                setOnErrorListener { _, _, _ ->
                    isPrepared = false; isPlayingRequested = false; notifyPlayState(false)
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            isPrepared = false; isPlayingRequested = false; notifyPlayState(false)
        }
    }

    fun resume() {
        isPlayingRequested = true
        val mp = mediaPlayer
        if (mp == null || !isPrepared) {
            if (playlist.isNotEmpty()) playCurrent(forceReload = true)
            notifyPlayState(true); return
        }

        try {
            if (!mp.isPlaying) {
                val prof = activeProfile
                val duration = mp.duration.toLong()
                val end = if (prof != null && prof.trimEnd > 0) prof.trimEnd else duration

                if (end > 0 && mp.currentPosition >= end - 500) {
                    seekTo(prof?.trimStart?.toInt() ?: 0)
                }

                if (requestAudioFocus()) {
                    mp.start()
                    lastSeekTime = System.currentTimeMillis()
                    notifyPlayState(true)
                    prof?.let { startWatchdogs(it) }
                } else {
                    isPlayingRequested = false; notifyPlayState(false)
                }
            } else {
                notifyPlayState(true)
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "Resume Failed", e)
            notifyPlayState(false)
        }
    }

    fun pause() {
        isPlayingRequested = false
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            runCatching { if (mp.isPlaying) mp.pause() }
        }
        notifyPlayState(false)
    }

    fun togglePlayPause() {
        if (isPlayingRequested) pause() else resume()
    }

    fun isPlaying(): Boolean = isPlayingRequested

    fun seekTo(ms: Int) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val dur = mp.duration
        if (dur > 0) {
            lastSeekTime = System.currentTimeMillis()
            runCatching { mp.seekTo(ms.coerceIn(0, dur)) }
            // Force MediaSession update so lockscreen seekbar jumps to new position
            notifyPlayState(isPlayingRequested)
        }
    }

    fun skipNext() {
        advanceIndex(); playCurrent(forceReload = true)
    }

    fun skipPrev() {
        val now = System.currentTimeMillis()
        // If pressed twice within 2 seconds, skip to previous song.
        // Otherwise, just replay the current song from the start.
        if (now - lastSkipPrevTime < 2000) {
            retreatIndex()
            playCurrent(forceReload = true)
        } else {
            val startPos = activeProfile?.trimStart?.toInt() ?: 0
            seekTo(startPos)
            lastSkipPrevTime = now
        }
    }

    private fun advanceIndex() {
        if (playlist.isEmpty()) return
        if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            val pos = shuffledIndices.indexOf(currentIndex)
            currentIndex = shuffledIndices[(pos + 1) % shuffledIndices.size]
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
    }

    private fun retreatIndex() {
        if (playlist.isEmpty()) return
        if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            val pos = shuffledIndices.indexOf(currentIndex)
            currentIndex = shuffledIndices[(pos - 1 + shuffledIndices.size) % shuffledIndices.size]
        } else {
            currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        }
    }

    private fun startWatchdogs(profile: PlaybackProfile) {
        cancelWatchdogs()
        if (profile.trimEnd > 0) {
            trimWatchdog = Runnable {
                val mp = mediaPlayer ?: return@Runnable
                if (isPrepared && mp.isPlaying) {
                    if (System.currentTimeMillis() - lastSeekTime >= 1000) {
                        val cur = mp.currentPosition
                        val end = profile.trimEnd.toInt()
                        if (cur >= end && end > 0) {
                            if (repeatMode == REPEAT_ONE) {
                                seekTo(profile.trimStart.toInt())
                            } else {
                                advanceOrStop()
                                return@Runnable
                            }
                        }
                    }
                }
                mainHandler.postDelayed(trimWatchdog!!, WATCHDOG_MS)
            }
            mainHandler.postDelayed(trimWatchdog!!, WATCHDOG_MS)
        }
        if (skipRegions.isNotEmpty()) {
            skipWatchdog = Runnable {
                val mp = mediaPlayer ?: return@Runnable
                if (isPrepared && mp.isPlaying) {
                    val pos = mp.currentPosition.toLong()
                    skipRegions.firstOrNull { pos >= it.startMs && pos < it.endMs }?.let {
                        seekTo(it.endMs.toInt())
                    }
                }
                mainHandler.postDelayed(skipWatchdog!!, WATCHDOG_MS)
            }
            mainHandler.postDelayed(skipWatchdog!!, WATCHDOG_MS)
        }
        if (profile.loopEnabled && profile.loopStart >= 0 && profile.loopEnd > profile.loopStart) {
            loopWatchdog = Runnable {
                val mp = mediaPlayer ?: return@Runnable
                if (isPrepared && mp.isPlaying) {
                    val cur = mp.currentPosition.toLong()
                    if (cur >= profile.loopEnd) {
                        seekTo(profile.loopStart.toInt())
                    }
                }
                mainHandler.postDelayed(loopWatchdog!!, WATCHDOG_MS)
            }
            mainHandler.postDelayed(loopWatchdog!!, WATCHDOG_MS)
        }
        if (profile.abRepeatEnabled && profile.abRepeatA >= 0 && profile.abRepeatB > profile.abRepeatA) {
            abRepeatWatchdog = Runnable {
                val mp = mediaPlayer ?: return@Runnable
                if (isPrepared && mp.isPlaying) {
                    val cur = mp.currentPosition.toLong()
                    if (cur >= profile.abRepeatB) {
                        seekTo(profile.abRepeatA.toInt())
                    }
                }
                mainHandler.postDelayed(abRepeatWatchdog!!, WATCHDOG_MS)
            }
            mainHandler.postDelayed(abRepeatWatchdog!!, WATCHDOG_MS)
        }
    }

    private fun cancelWatchdogs() {
        trimWatchdog?.let { mainHandler.removeCallbacks(it) }; trimWatchdog = null
        skipWatchdog?.let { mainHandler.removeCallbacks(it) }; skipWatchdog = null
        loopWatchdog?.let { mainHandler.removeCallbacks(it) }; loopWatchdog = null
        abRepeatWatchdog?.let { mainHandler.removeCallbacks(it) }; abRepeatWatchdog = null
    }

    private fun isAtEndOfPlaylist(): Boolean {
        if (playlist.isEmpty()) return true
        return if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            shuffledIndices.indexOf(currentIndex) == shuffledIndices.size - 1
        } else {
            currentIndex >= playlist.size - 1
        }
    }

    private fun advanceOrStop() {
        when (repeatMode) {
            REPEAT_ONE -> {
                seekTo(activeProfile?.trimStart?.toInt() ?: 0)
                resume()
            }
            REPEAT_ALL -> {
                advanceIndex()
                playCurrent(forceReload = true)
            }

            REPEAT_AUTO -> {
                if (playlist.isNotEmpty()) {
                    // Pick a random song from the current context, avoiding the current song if possible
                    val randomSong = if (playlist.size > 1) {
                        playlist.filter { it.id != currentSong?.id }.random()
                    } else {
                        playlist.random()
                    }

                    val newPlaylist = playlist.toMutableList()
                    newPlaylist.add((currentIndex + 1).coerceAtMost(newPlaylist.size), randomSong)
                    playlist = newPlaylist
                    currentIndex++
                    playCurrent(forceReload = true)
                } else {
                    advanceOrStopFallback()
                }
            }

            else -> { // REPEAT_NONE: Stop completely after the current song
                pause()
            }
        }
    }

    private fun advanceOrStopFallback() {
        if (isAtEndOfPlaylist()) {
            pause()
        } else {
            advanceIndex()
            playCurrent(forceReload = true)
        }
    }

    fun updatePlaylistOnly(newSongs: List<Song>) {
        if (newSongs.isEmpty()) return
        val current = currentSong ?: return
        val newIdx = newSongs.indexOfFirst { it.id == current.id }
        if (newIdx != -1) {
            playlist = newSongs
            currentIndex = newIdx
            if (shuffleEnabled) rebuildShuffleIndices()
        }
    }

    private fun applyVolumeInternal(profile: PlaybackProfile) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val baseVol = profile.volume
        val finalVol = if (profile.replayGainEnabled) {
            (10.0.pow(profile.replayGainDb.toDouble() / 20.0).toFloat()
                .coerceIn(0f, 1f) * baseVol).coerceIn(0f, 1f)
        } else {
            baseVol
        }
        runCatching { mp.setVolume(finalVol, finalVol) }
    }

    private fun applyPlaybackParams(pitchSemitones: Float, speed: Float) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        runCatching {
            val params = try {
                mp.playbackParams
            } catch (_: Exception) {
                PlaybackParams()
            }
            params.pitch =
                SEMITONE_RATIO.pow(pitchSemitones.toDouble()).toFloat().coerceIn(0.1f, 8f)
            params.speed = speed.coerceIn(0.1f, 4f)
            mp.playbackParams = params
        }
    }

    private fun notifySongChanged(song: Song) {
        songChangedCallbacks.forEach { it(song) }
    }

    private fun notifyPlayState(playing: Boolean) {
        playStateCallbacks.forEach { it(playing) }
        // Update Metadata BEFORE State to ensure duration is known when position is set
        updateNotification()
        updateMediaSessionState(playing)
    }

    private fun updateNotification() {
        val song = currentSong ?: return
        startForeground(NOTIF_ID, buildNotification(song, currentArt, isPlayingRequested))
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusRequest != null) return true // Optimization: Request once per session if possible
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        mediaPlayer?.let { mp ->
                            if (isPrepared) runCatching { mp.setVolume(0.2f, 0.2f) }
                        }
                    }

                    AudioManager.AUDIOFOCUS_GAIN -> {
                        mediaPlayer?.let {
                            if (isPrepared) applyVolumeInternal(
                                activeProfile ?: return@let
                            )
                        }
                        if (isPlayingRequested) resume()
                    }
                }
            }.build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(NOTIF_CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(song: Song, art: Bitmap?, playing: Boolean): Notification {
        fun pi(action: String) = PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            Intent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        updateMediaSessionMetadata(song, art)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note).setContentTitle(song.title)
            .setContentText(song.artist).setLargeIcon(art)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(
                        this,
                        MainActivity::class.java
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOnlyAlertOnce(true).setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Prev", pi(ACTION_PREV))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                pi(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT))
            .setStyle(
                MediaStyle().setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicVault").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resume()
                }

                override fun onPause() {
                    pause()
                }

                override fun onSkipToNext() {
                    skipNext()
                }

                override fun onSkipToPrevious() {
                    skipPrev()
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    val tStart = activeProfile?.trimStart ?: 0L
                    seekTo((pos + tStart).toInt())
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionMetadata(song: Song, art: Bitmap?) {
        val tStart = activeProfile?.trimStart ?: 0L
        val tEnd = activeProfile?.trimEnd ?: 0L
        val fullDur =
            if (isPrepared) runCatching { mediaPlayer?.duration?.toLong() }.getOrDefault(0L)
                ?: 0L else song.duration
        val effectiveDur =
            if (tEnd > 0L) (tEnd - tStart).coerceAtLeast(0L) else (fullDur - tStart).coerceAtLeast(
                0L
            )

        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, effectiveDur)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art).build()
        )
    }

    private fun updateMediaSessionState(playing: Boolean) {
        val state =
            if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        val tStart = activeProfile?.trimStart ?: 0L
        val absolutePos = getPosition().toLong()
        val relativePos = (absolutePos - tStart).coerceAtLeast(0L)

        // Speed must be 0 if not playing, otherwise Android seekbar won't behave correctly
        val speed = if (playing) (activeProfile?.playbackSpeed ?: 1f) else 0f

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, relativePos, speed)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent); stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        cancelWatchdogs()
        runCatching { unregisterReceiver(notifReceiver) }
        mediaPlayer?.release()
        mediaSession?.release()
        serviceScope.cancel()
    }

    // --- API ---
    fun getPosition(): Int =
        if (isPrepared) runCatching { mediaPlayer?.currentPosition }.getOrDefault(0) ?: 0 else 0

    fun getDuration(): Int = if (isPrepared) runCatching { mediaPlayer?.duration }.getOrDefault(0)
        ?: 0 else (currentSong?.duration?.toInt() ?: 0)

    fun getCurrentSong(): Song? = currentSong
    fun getRepeatMode(): Int = repeatMode
    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        com.mochimochi.clawmikia.MusicVaultApp.instance.prefs.edit()
            .putInt(com.mochimochi.clawmikia.MusicVaultApp.KEY_REPEAT_MODE, mode).apply()
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled; if (shuffleEnabled) rebuildShuffleIndices()
        com.mochimochi.clawmikia.MusicVaultApp.instance.prefs.edit()
            .putBoolean(com.mochimochi.clawmikia.MusicVaultApp.KEY_SHUFFLE_ON, shuffleEnabled)
            .apply()
    }

    fun isShuffleEnabled(): Boolean = shuffleEnabled
    private fun rebuildShuffleIndices() {
        if (playlist.isNotEmpty()) shuffledIndices = (playlist.indices).shuffled()
    }

    fun applyProfile(profile: PlaybackProfile) {
        activeProfile = profile
        dspProcessor?.applyProfile(profile)
        applyPlaybackParams(profile.pitchSemitones, profile.playbackSpeed)
        applyVolumeInternal(profile)
        if (isPlayingRequested) startWatchdogs(profile)
    }

    fun applyPitchToCurrentSong(s: Float) {
        activeProfile = activeProfile?.copy(pitchSemitones = s); applyPlaybackParams(
            s,
            activeProfile?.playbackSpeed ?: 1f
        )
    }

    fun applySpeedToCurrentSong(s: Float) {
        activeProfile = activeProfile?.copy(playbackSpeed = s); applyPlaybackParams(
            activeProfile?.pitchSemitones ?: 0f, s
        )
    }

    fun applyTrimToCurrentSong(start: Long, end: Long) {
        activeProfile = activeProfile?.copy(
            trimStart = start,
            trimEnd = end
        )
        if (isPlayingRequested) activeProfile?.let { startWatchdogs(it) }

        // Refresh MediaSession metadata and state so the lockscreen seekbar updates immediately
        updateNotification()
        notifyPlayState(isPlayingRequested)
    }

    fun applyLoopToCurrentSong(start: Long, end: Long, enabled: Boolean) {
        activeProfile = activeProfile?.copy(loopStart = start, loopEnd = end, loopEnabled = enabled)
        if (isPlayingRequested) activeProfile?.let { startWatchdogs(it) }
    }

    fun applyAbRepeatToCurrentSong(a: Long, b: Long, enabled: Boolean) {
        activeProfile = activeProfile?.copy(abRepeatA = a, abRepeatB = b, abRepeatEnabled = enabled)
        if (isPlayingRequested) activeProfile?.let { startWatchdogs(it) }
    }

    fun applySkipRegions(regions: List<SkipRegion>) {
        skipRegions = regions
        if (isPlayingRequested) activeProfile?.let { startWatchdogs(it) }
    }

    fun setBypassProfiles(bypass: Boolean) {
        bypassProfiles = bypass
        currentSong ?: return
        val profile = activeProfile ?: return

        if (bypass) {
            dspProcessor?.disableAll()
            applyPlaybackParams(0f, 1f)
            runCatching { mediaPlayer?.setVolume(1f, 1f) }
            cancelWatchdogs()
        } else {
            dspProcessor?.applyProfile(profile)
            applyPlaybackParams(profile.pitchSemitones, profile.playbackSpeed)
            applyVolumeInternal(profile)
            if (isPlayingRequested) startWatchdogs(profile)
        }

        updateNotification()
        notifyPlayState(isPlayingRequested)
    }

    fun isBypassingProfiles(): Boolean = bypassProfiles
}
