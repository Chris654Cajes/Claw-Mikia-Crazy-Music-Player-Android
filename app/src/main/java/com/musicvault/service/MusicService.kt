package com.musicvault.service

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
import androidx.media.app.NotificationCompat.MediaStyle
import com.musicvault.R
import com.musicvault.audio.analysis.AnalysisEngine
import com.musicvault.audio.dsp.DSPProcessor
import com.musicvault.data.model.PlaybackProfile
import com.musicvault.data.model.SkipRegion
import com.musicvault.data.model.Song
import com.musicvault.data.repository.PlaylistRepository
import com.musicvault.data.repository.ProfileRepository
import com.musicvault.data.repository.SongRepository
import com.musicvault.lyrics.LyricsManager
import com.musicvault.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var activeProfile: PlaybackProfile? = null
    private var skipRegions: List<SkipRegion> = emptyList()
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var repeatMode = REPEAT_NONE
    private var shuffleEnabled = false
    private var shuffledIndices: List<Int> = emptyList()

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var repository: SongRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var lyricsManager: LyricsManager
    private lateinit var analysisEngine: AnalysisEngine
    private var dspProcessor: DSPProcessor? = null

    private val trimHandler = Handler(Looper.getMainLooper())
    private var trimWatchdog: Runnable? = null
    private var skipWatchdog: Runnable? = null
    private var loopWatchdog: Runnable? = null
    private var abRepeatWatchdog: Runnable? = null
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndMs: Long = -1L

    private var playJob: kotlinx.coroutines.Job? = null
    private val playStateCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val songChangedCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Song) -> Unit>()

    private var isPlayingRequested = false
    private var isPrepared = false
    private var lastSeekTime = 0L

    fun addPlayStateCallback(cb: (Boolean) -> Unit) {
        playStateCallbacks.add(cb)
        cb(isPlaying())
    }

    fun removePlayStateCallback(cb: (Boolean) -> Unit) {
        playStateCallbacks.remove(cb)
    }

    fun addSongChangedCallback(cb: (Song) -> Unit) {
        songChangedCallbacks.add(cb)
        currentSong?.let { cb(it) }
    }

    fun removeSongChangedCallback(cb: (Song) -> Unit) {
        songChangedCallbacks.remove(cb)
    }

    companion object {
        const val NOTIF_CHANNEL = "music_vault_channel"
        const val NOTIF_ID = 101
        const val REPEAT_NONE = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
        const val ACTION_PLAY = "com.musicvault.PLAY"
        const val ACTION_PAUSE = "com.musicvault.PAUSE"
        const val ACTION_NEXT = "com.musicvault.NEXT"
        const val ACTION_PREV = "com.musicvault.PREV"
        const val ACTION_STOP = "com.musicvault.STOP"
        private const val SEMITONE_RATIO = 1.0594630943592953
        private const val WATCHDOG_MS = 100L
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY -> resume()
                ACTION_PAUSE -> pause()
                ACTION_NEXT -> skipNext()
                ACTION_PREV -> skipPrev()
                ACTION_STOP -> {
                    stopSelf()
                    notifyPlayState(false)
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
        createNotificationChannel()
        initMediaSession()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (shuffleEnabled) rebuildShuffleIndices()
        playCurrent()
    }

    fun playSong(song: Song) {
        val idx = playlist.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            currentIndex = idx
            playCurrent()
        } else {
            playlist = listOf(song)
            currentIndex = 0
            playCurrent()
        }
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return
        val songSnapshot = playlist[currentIndex]
        playJob?.cancel()
        playJob = serviceScope.launch {
            val freshSong = withContext(Dispatchers.IO) {
                try {
                    repository.getSongById(songSnapshot.id)
                } catch (_: Exception) {
                    null
                }
            } ?: songSnapshot

            android.util.Log.d(
                "MusicService",
                "Loading song: ${freshSong.title}"
            )

            val profile = withContext(Dispatchers.IO) {
                profileRepository.getOrCreateActiveProfile(freshSong.id)
            }

            val regions = withContext(Dispatchers.IO) {
                profileRepository.getEnabledSkipRegions(freshSong.id)
            }
            playlist = playlist.toMutableList().also { it[currentIndex] = freshSong }
            activeProfile = profile
            skipRegions = regions
            repeatMode = freshSong.repeatMode
            playFreshSong(freshSong, profile)
            launch(Dispatchers.IO) {
                lyricsManager.loadForSong(freshSong.id, freshSong.filePath)
            }
            analysisEngine.analyzeIfNeeded(freshSong.id, freshSong.filePath, freshSong.duration)
        }
    }

    private fun playFreshSong(song: Song, profile: PlaybackProfile) {
        currentSong = song
        notifySongChanged(song)
        cancelAllWatchdogs()
        isPrepared = false
        isPlayingRequested = true // We intent to play this song
        notifyPlayState(true) // Show "Pause" icon immediately

        try {
            mediaPlayer?.reset()
            if (mediaPlayer == null) {
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
                    dspProcessor?.release()
                    dspProcessor = DSPProcessor(mp.audioSessionId).also { it.applyProfile(profile) }
                    applyPlaybackParams(profile.pitchSemitones.toInt(), profile.playbackSpeed)

                    if (profile.replayGainEnabled && profile.replayGainDb != 0f) {
                        val gain =
                            Math.pow(10.0, profile.replayGainDb / 20.0).toFloat().coerceIn(0f, 1f)
                        val finalVolume = (gain * profile.volume).coerceIn(0f, 1f)
                        mp.setVolume(finalVolume, finalVolume)
                    } else {
                        mp.setVolume(profile.volume, profile.volume)
                    }

                    val startPos = profile.trimStart.toInt()
                    if (startPos > 0) {
                        lastSeekTime = System.currentTimeMillis()
                        mp.seekTo(startPos)
                    }

                    // Only start if we still want to play
                    if (isPlayingRequested) {
                        mp.start()
                        notifyPlayState(true)
                        startWatchdogs(profile)
                    } else {
                        notifyPlayState(false)
                    }
                }
                setOnCompletionListener {
                    isPrepared = false
                    cancelAllWatchdogs()
                    serviceScope.launch {
                        playlistRepository.recordPlay(song.id, song.duration, true)
                    }
                    when (repeatMode) {
                        REPEAT_ONE -> playCurrent()
                        REPEAT_ALL -> {
                            advanceIndex()
                            playCurrent()
                        }
                        else -> if (currentIndex < playlist.size - 1) {
                            advanceIndex()
                            playCurrent()
                        } else {
                            pause()
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    isPrepared = false
                    notifyPlayState(false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            isPrepared = false
            notifyPlayState(false)
        }
    }

    private fun startWatchdogs(profile: PlaybackProfile) {
        cancelAllWatchdogs()
        val currentProfile = activeProfile ?: profile

        if (currentProfile.trimEnd > 0) {
            val watchdog = object : Runnable {
                override fun run() {
                    if (trimWatchdog != this) return
                    val mp = mediaPlayer ?: return
                    val prof = activeProfile ?: return

                    if (mp.isPlaying && prof.trimEnd > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastSeekTime < 1000) {
                            trimHandler.postDelayed(this, WATCHDOG_MS)
                            return
                        }

                        val currentPos = mp.currentPosition
                        val trimEnd = prof.trimEnd.toInt()
                        if (currentPos >= trimEnd) {
                            if (repeatMode == REPEAT_ONE) {
                                seekTo(prof.trimStart.toInt())
                            } else {
                                advanceOrStop()
                            }
                            return
                        }
                    }
                    trimHandler.postDelayed(this, WATCHDOG_MS)
                }
            }
            trimWatchdog = watchdog
            trimHandler.postDelayed(watchdog, WATCHDOG_MS)
        }

        skipWatchdog = object : Runnable {
            override fun run() {
                if (skipWatchdog != this) return
                val mp = mediaPlayer ?: return
                if (mp.isPlaying && skipRegions.isNotEmpty()) {
                    val pos = mp.currentPosition.toLong()
                    skipRegions.firstOrNull { pos >= it.startMs && pos < it.endMs }
                        ?.let {
                            mp.seekTo(it.endMs.toInt())
                        }
                }
                trimHandler.postDelayed(this, WATCHDOG_MS)
            }
        }
        trimHandler.postDelayed(skipWatchdog!!, WATCHDOG_MS)

        if (currentProfile.loopEnabled) {
            loopWatchdog = object : Runnable {
                override fun run() {
                    if (loopWatchdog != this) return
                    val mp = mediaPlayer ?: return
                    val prof = activeProfile ?: return
                    if (mp.isPlaying && prof.loopEnabled && prof.loopEnd > prof.loopStart) {
                        if (mp.currentPosition >= prof.loopEnd.toInt()) {
                            mp.seekTo(prof.loopStart.toInt())
                        }
                    }
                    trimHandler.postDelayed(this, WATCHDOG_MS)
                }
            }
            trimHandler.postDelayed(loopWatchdog!!, WATCHDOG_MS)
        }

        if (currentProfile.abRepeatEnabled) {
            abRepeatWatchdog = object : Runnable {
                override fun run() {
                    if (abRepeatWatchdog != this) return
                    val mp = mediaPlayer ?: return
                    val prof = activeProfile ?: return
                    if (mp.isPlaying && prof.abRepeatEnabled && prof.abRepeatB > prof.abRepeatA) {
                        if (mp.currentPosition >= prof.abRepeatB.toInt()) {
                            mp.seekTo(prof.abRepeatA.toInt())
                        }
                    }
                    trimHandler.postDelayed(this, WATCHDOG_MS)
                }
            }
            trimHandler.postDelayed(abRepeatWatchdog!!, WATCHDOG_MS)
        }
    }

    private fun cancelAllWatchdogs() {
        trimWatchdog?.let { trimHandler.removeCallbacks(it) }
        skipWatchdog?.let { trimHandler.removeCallbacks(it) }
        loopWatchdog?.let { trimHandler.removeCallbacks(it) }
        abRepeatWatchdog?.let { trimHandler.removeCallbacks(it) }
        trimWatchdog = null
        skipWatchdog = null
        loopWatchdog = null
        abRepeatWatchdog = null
    }

    private fun advanceOrStop() {
        if (currentIndex < playlist.size - 1) {
            advanceIndex()
            playCurrent()
        } else {
            pause()
        }
    }

    fun setSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        sleepTimerEndMs = System.currentTimeMillis() + durationMs
        sleepTimerRunnable = Runnable {
            pause()
            sleepTimerEndMs = -1L
        }
        sleepTimerHandler.postDelayed(sleepTimerRunnable!!, durationMs)
    }

    fun cancelSleepTimer() {
        sleepTimerRunnable?.let { sleepTimerHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerEndMs = -1L
    }

    fun getSleepTimerRemainingMs(): Long {
        if (sleepTimerEndMs < 0) return -1L
        return (sleepTimerEndMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun applyProfile(profile: PlaybackProfile) {
        activeProfile = profile
        dspProcessor?.applyProfile(profile)
        applyPlaybackParams(profile.pitchSemitones.toInt(), profile.playbackSpeed)
    }

    fun reloadActiveProfile() {
        val song = currentSong ?: return
        serviceScope.launch {
            val profile =
                withContext(Dispatchers.IO) { profileRepository.getOrCreateActiveProfile(song.id) }
            val regions =
                withContext(Dispatchers.IO) { profileRepository.getEnabledSkipRegions(song.id) }
            activeProfile = profile
            skipRegions = regions
            applyPlaybackParams(profile.pitchSemitones.toInt(), profile.playbackSpeed)
            dspProcessor?.applyProfile(profile)
            cancelAllWatchdogs()
            startWatchdogs(profile)
        }
    }

    private fun applyPlaybackParams(pitchSemitones: Int, speed: Float) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        try {
            val wasPlaying = mp.isPlaying
            val pitchVal = SEMITONE_RATIO.pow(pitchSemitones.toDouble()).toFloat()

            val params = try {
                mp.playbackParams
            } catch (e: Exception) {
                PlaybackParams()
            }
            params.pitch = pitchVal.coerceIn(0.1f, 8f)
            params.speed = speed.coerceIn(0.1f, 4f)

            mp.playbackParams = params
            if (wasPlaying && !mp.isPlaying) mp.start()
        } catch (_: Exception) {
        }
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        if (shuffleEnabled) rebuildShuffleIndices()
    }

    fun isShuffleEnabled(): Boolean = shuffleEnabled

    private fun rebuildShuffleIndices() {
        val indices = (0 until playlist.size).toMutableList().also { it.shuffle() }
        val curr = indices.indexOf(currentIndex)
        if (curr > 0) {
            indices.removeAt(curr)
            indices.add(0, currentIndex)
        }
        shuffledIndices = indices
    }

    private fun advanceIndex() {
        if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
            val pos = shuffledIndices.indexOf(currentIndex)
            currentIndex = shuffledIndices[(pos + 1) % shuffledIndices.size]
        } else {
            currentIndex = (currentIndex + 1) % playlist.size
        }
    }

    fun skipNext() {
        cancelAllWatchdogs()
        advanceIndex()
        playCurrent()
    }

    fun skipPrev() {
        cancelAllWatchdogs()
        if (mediaPlayer != null && (mediaPlayer?.currentPosition ?: 0) > 3000) {
            seekTo(activeProfile?.trimStart?.toInt() ?: 0)
        } else {
            if (shuffleEnabled && shuffledIndices.isNotEmpty()) {
                val pos = shuffledIndices.indexOf(currentIndex)
                currentIndex =
                    shuffledIndices[((pos - 1) + shuffledIndices.size) % shuffledIndices.size]
            } else {
                currentIndex = ((currentIndex - 1) + playlist.size) % playlist.size
            }
            playCurrent()
        }
    }

    fun togglePlayPause() {
        if (isPlaying()) pause() else resume()
    }

    fun resume() {
        android.util.Log.d("MusicService", "resume() called. isPrepared=$isPrepared")
        isPlayingRequested = true

        val mp = mediaPlayer
        if (mp == null || !isPrepared) {
            if (playlist.isNotEmpty() && !isPrepared) {
                if (mp == null) playCurrent()
            }
            notifyPlayState(true)
            return
        }

        try {
            if (!mp.isPlaying) {
                val prof = activeProfile
                if (prof != null && prof.trimEnd > 0 && mp.currentPosition >= prof.trimEnd - 500) {
                    seekTo(prof.trimStart.toInt())
                }

                if (requestAudioFocus()) {
                    mp.start()
                    notifyPlayState(true)
                } else {
                    notifyPlayState(false)
                }
            } else {
                notifyPlayState(true)
            }
        } catch (e: Exception) {
            notifyPlayState(false)
        }
    }

    fun pause() {
        android.util.Log.d("MusicService", "pause() called.")
        isPlayingRequested = false
        val mp = mediaPlayer
        if (mp == null) {
            notifyPlayState(false)
            return
        }

        try {
            if (isPrepared && mp.isPlaying) {
                mp.pause()
            }
            notifyPlayState(false)
        } catch (e: Exception) {
            notifyPlayState(false)
        }
    }

    fun isPlaying(): Boolean = isPlayingRequested

    fun seekTo(ms: Int) {
        val mp = mediaPlayer
        if (mp == null || !isPrepared) {
            return
        }

        try {
            val duration = mp.duration
            if (duration > 0) {
                val clampedMs = ms.coerceIn(0, duration)
                lastSeekTime = System.currentTimeMillis()
                mp.seekTo(clampedMs)
            }
        } catch (e: Exception) {
        }
    }

    fun getCurrentPosition(): Int = if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
    fun getPosition(): Int = getCurrentPosition()
    fun getDuration(): Int =
        if (isPrepared) mediaPlayer?.duration ?: (currentSong?.duration?.toInt()
            ?: 0) else (currentSong?.duration?.toInt() ?: 0)
    fun getCurrentSong(): Song? = currentSong
    fun getPlaylist(): List<Song> = playlist
    fun getCurrentIndex(): Int = currentIndex
    fun getRepeatMode(): Int = repeatMode
    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        currentSong?.let { song ->
            serviceScope.launch {
                repository.updateRepeatModeAndSyncProfile(song.id, mode)
            }
        }
    }
    fun getActiveProfile(): PlaybackProfile? = activeProfile
    fun getLyricsManager(): LyricsManager = lyricsManager
    fun getAnalysisEngine(): AnalysisEngine = analysisEngine

    fun applyPitchToCurrentSong(semitones: Int) {
        val newPitch = semitones.toFloat()
        activeProfile = activeProfile?.copy(pitchSemitones = newPitch)
        applyPlaybackParams(semitones, activeProfile?.playbackSpeed ?: 1.0f)

        currentSong?.let { song ->
            serviceScope.launch {
                repository.updatePitchAndSyncProfile(song.id, semitones)
            }
        }
    }

    fun applySpeedToCurrentSong(speed: Float) {
        activeProfile = activeProfile?.copy(playbackSpeed = speed)
        applyPlaybackParams(activeProfile?.pitchSemitones?.toInt() ?: 0, speed)

        currentSong?.let { song ->
            serviceScope.launch {
                repository.updateSpeedAndSyncProfile(song.id, speed)
            }
        }
    }

    fun applyTrimToCurrentSong(startMs: Long, endMs: Long) {
        val oldProfile = activeProfile
        activeProfile = activeProfile?.copy(trimStart = startMs, trimEnd = endMs)

        mediaPlayer?.let { mp ->
            if (isPrepared && mp.isPlaying) {
                val currentPos = mp.currentPosition.toLong()
                if (currentPos < startMs) {
                    seekTo(startMs.toInt())
                } else if (endMs > 0 && currentPos > endMs) {
                    seekTo(startMs.toInt())
                }
            }
        }

        if (oldProfile?.trimEnd != endMs) {
            activeProfile?.let { startWatchdogs(it) }
        }

        currentSong?.let { song ->
            serviceScope.launch {
                repository.updateTrimAndSyncProfile(song.id, startMs, endMs)
            }
        }
    }

    fun applyVolumeToCurrentSong(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        activeProfile = activeProfile?.copy(volume = clampedVolume)

        mediaPlayer?.let { mp ->
            if (isPrepared) {
                val currentVolume =
                    if (activeProfile?.replayGainEnabled == true && activeProfile?.replayGainDb != 0f) {
                        val gain =
                            Math.pow(10.0, (activeProfile?.replayGainDb ?: 0f) / 20.0).toFloat()
                                .coerceIn(0f, 1f)
                    (gain * clampedVolume).coerceIn(0f, 1f)
                } else {
                    clampedVolume
                }
                mp.setVolume(currentVolume, currentVolume)
            }
        }

        currentSong?.let { song ->
            serviceScope.launch {
                repository.updateVolumeAndSyncProfile(song.id, clampedVolume)
            }
        }
    }

    fun applyRepeatModeToCurrentSong(repeatMode: Int) {
        this.repeatMode = repeatMode
        currentSong?.let { song ->
            serviceScope.launch {
                repository.updateRepeatModeAndSyncProfile(song.id, repeatMode)
            }
        }
    }

    private var originalVolume = 1.0f

    private fun requestAudioFocus(): Boolean {
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            mediaPlayer?.let { mp ->
                                if (isPrepared) {
                                    originalVolume = activeProfile?.volume ?: 1.0f
                                    val duckVolume = originalVolume * 0.2f
                                    mp.setVolume(duckVolume, duckVolume)
                                }
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.let { mp ->
                                if (isPrepared) {
                                    mp.setVolume(originalVolume, originalVolume)
                                }
                            }
                            if (isPlayingRequested) {
                                resume()
                            }
                        }
                    }
                }.build()
            audioFocusRequest = request
            val result = audioManager.requestAudioFocus(request)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            return false
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(NOTIF_CHANNEL, "Music Playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildAndStartForeground() {
        val song = currentSong ?: return
        serviceScope.launch {
            val art = withContext(Dispatchers.IO) {
                try {
                    if (song.albumArtUrl.isNotBlank()) BitmapFactory.decodeStream(URL(song.albumArtUrl).openStream()) else null
                } catch (_: Exception) {
                    null
                }
            }
            startForeground(NOTIF_ID, buildNotification(song, art, isPlayingRequested))
        }
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
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(art)
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
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initMediaSession() {
        val session = MediaSessionCompat(this, "MusicVaultSession")
        session.setCallback(object : MediaSessionCompat.Callback() {
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
                seekTo(pos.toInt())
            }
        })
        session.isActive = true
        mediaSession = session
    }

    private fun updateMediaSessionMetadata(song: Song, art: Bitmap?) {
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .apply {
                    if (art != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                }
                .build()
        )
    }

    private fun updateMediaSessionState(playing: Boolean) {
        val state =
            if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, getCurrentPosition().toLong(), activeProfile?.playbackSpeed ?: 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                .build()
        )
    }

    private fun notifySongChanged(song: Song) {
        songChangedCallbacks.forEach { it(song) }
    }

    private fun notifyPlayState(playing: Boolean) {
        playStateCallbacks.forEach { it(playing) }
        updateMediaSessionState(playing)
        buildAndStartForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (currentSong == null) {
            startForeground(
                NOTIF_ID,
                NotificationCompat.Builder(this, NOTIF_CHANNEL)
                    .setSmallIcon(R.drawable.ic_music_note)
                    .setContentTitle("Claw Mikia").setContentText("Ready")
                    .setPriority(NotificationCompat.PRIORITY_LOW).build()
            )
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllWatchdogs()
        cancelSleepTimer()
        runCatching { unregisterReceiver(notifReceiver) }
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession?.release()
        dspProcessor?.release()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        serviceScope.cancel()
    }
}
