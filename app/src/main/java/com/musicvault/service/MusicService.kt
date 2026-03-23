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
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.musicvault.R
import com.musicvault.data.model.Song
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
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = 0
    private var repeatMode = REPEAT_NONE
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Use a list of listeners so multiple screens can observe simultaneously
    private val songChangedListeners = mutableListOf<(Song) -> Unit>()
    private val playStateListeners = mutableListOf<(Boolean) -> Unit>()

    // Keep legacy single-callback properties for compatibility but route through lists
    var onSongChanged: ((Song) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    companion object {
        const val NOTIF_CHANNEL = "music_vault_channel"
        const val NOTIF_ID      = 101
        const val REPEAT_NONE   = 0
        const val REPEAT_ONE    = 1
        const val REPEAT_ALL    = 2
        const val ACTION_PLAY   = "com.musicvault.PLAY"
        const val ACTION_PAUSE  = "com.musicvault.PAUSE"
        const val ACTION_NEXT   = "com.musicvault.NEXT"
        const val ACTION_PREV   = "com.musicvault.PREV"
        const val ACTION_STOP   = "com.musicvault.STOP"
        private const val SEMITONE_RATIO = 1.0594630943592953
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY, ACTION_PAUSE -> togglePlayPause()
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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY); addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT); addAction(ACTION_PREV); addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }
    }

    private fun notifySongChanged(song: Song) {
        onSongChanged?.invoke(song)
    }

    private fun notifyPlayState(playing: Boolean) {
        onPlayStateChanged?.invoke(playing)
    }

    private fun initMediaSession() {
        val session = MediaSessionCompat(this, "MusicVaultSession")
        val callback = object : MediaSessionCompat.Callback() {
            override fun onPlay()            { if (!isPlaying()) togglePlayPause() }
            override fun onPause()           { if (isPlaying())  togglePlayPause() }
            override fun onSkipToNext()      { skipNext() }
            override fun onSkipToPrevious()  { skipPrev() }
            override fun onStop()            { stopSelf() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
        }
        session.setCallback(callback)
        session.isActive = true
        mediaSession = session
    }

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlist = songs
        currentIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        playCurrent()
    }

    fun playSong(song: Song) {
        val idx = playlist.indexOfFirst { it.id == song.id }
        if (idx >= 0) { currentIndex = idx; playCurrent() }
        else { playlist = listOf(song); currentIndex = 0; playCurrent() }
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return
        val song = playlist[currentIndex]
        currentSong = song
        notifySongChanged(song)

        try {
            mediaPlayer?.reset() // Reset is safer than release/new here
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setWakeMode(this@MusicService, PowerManager.PARTIAL_WAKE_LOCK)
                }
            }

            mediaPlayer?.apply {
                setDataSource(this@MusicService, Uri.parse(song.filePath))

                // 1. Set the Prepared Listener
                setOnPreparedListener { mp ->
                    applyPitchToCurrentSong(song.pitchSemitones) // ✅ ALWAYS APPLY

                    if (song.trimStart > 0) mp.seekTo(song.trimStart.toInt())
                    mp.start()

                    notifyPlayState(true)
                    updateSessionPlaybackState(true)
                    updateNotification(song, true)
                }   

                setOnCompletionListener { handleCompletion() }

                // 3. Use prepareAsync() to avoid UI stutters
                prepareAsync()
            }

            requestAudioFocus()
            updateSessionMetadata(song, null)
            startForeground(NOTIF_ID, buildNotification(song, true, null))

            // Load art in background
            serviceScope.launch {
                val bitmap = loadAlbumArt(song)
                updateSessionMetadata(song, bitmap)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(song, true, bitmap))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleCompletion() {
        when (repeatMode) {
            REPEAT_ONE -> playCurrent()
            REPEAT_ALL -> { currentIndex = (currentIndex + 1) % playlist.size; playCurrent() }
            else -> {
                if (currentIndex < playlist.size - 1) {
                    currentIndex++
                    playCurrent()
                } else {
                    // End of playlist — replay current song from start
                    playCurrent()
                }
            }
        }
    }

    fun togglePlayPause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                abandonAudioFocus()
                updateSessionPlaybackState(false)
                notifyPlayState(false)
                currentSong?.let { s -> updateNotification(s, false) }
            } else {
                requestAudioFocus()
                it.start()
                updateSessionPlaybackState(true)
                notifyPlayState(true)
                currentSong?.let { s -> updateNotification(s, true) }
            }
        }
    }

    fun skipNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    fun skipPrev() {
        if (playlist.isEmpty()) return
        if (getPosition() > 3000) seekTo(currentSong?.trimStart?.toInt() ?: 0)
        else { currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1; playCurrent() }
    }

    fun seekTo(ms: Int)          { mediaPlayer?.seekTo(ms) }
    fun getPosition(): Int       = mediaPlayer?.currentPosition ?: 0
    fun isPlaying(): Boolean     = mediaPlayer?.isPlaying ?: false
    fun getCurrentSong(): Song?  = currentSong
    fun setRepeatMode(mode: Int) { repeatMode = mode }
    fun getRepeatMode(): Int = repeatMode

    fun getDuration(): Int {
        val song = currentSong ?: return 0
        val end = if (song.trimEnd > 0) song.trimEnd.toInt() else (mediaPlayer?.duration ?: 0)
        return end - song.trimStart.toInt()
    }

    fun applyPitchToCurrentSong(semitones: Int) {
        mediaPlayer?.playbackParams = PlaybackParams().apply {
            pitch = if (semitones == 0) 1.0f
            else SEMITONE_RATIO.pow(semitones.toDouble()).toFloat()
            speed = 1.0f
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS && isPlaying()) togglePlayPause()
                }
            }.build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    private fun updateSessionMetadata(song: Song, art: Bitmap?) {
        val session = mediaSession ?: return
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
                song.albumName.ifBlank { song.folderName })
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
        art?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())
    }

    private fun updateSessionPlaybackState(playing: Boolean) {
        val session = mediaSession ?: return
        val state =
            if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, pos, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                ).build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "MusicVault Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun pendingBroadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this, action.hashCode(),
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(song: Song, playing: Boolean, art: Bitmap?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fallbackArt = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher)
        val token = mediaSession?.sessionToken
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setSubText(song.albumName.ifBlank { song.folderName })
            .setLargeIcon(art ?: fallbackArt)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_skip_prev, "Previous", pendingBroadcast(ACTION_PREV))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                pendingBroadcast(if (playing) ACTION_PAUSE else ACTION_PLAY)
            )
            .addAction(R.drawable.ic_skip_next, "Next", pendingBroadcast(ACTION_NEXT))
            .addAction(R.drawable.ic_close, "Stop", pendingBroadcast(ACTION_STOP))
        if (token != null) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(pendingBroadcast(ACTION_STOP))
            )
        }
        return builder.build()
    }

    private fun updateNotification(song: Song, playing: Boolean) {
        serviceScope.launch {
            val art = loadAlbumArt(song)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(song, playing, art))
        }
    }

    private suspend fun loadAlbumArt(song: Song): Bitmap? = withContext(Dispatchers.IO) {
        if (song.albumArtUrl.isBlank()) return@withContext null
        try {
            val conn = URL(song.albumArtUrl).openConnection().apply {
                connectTimeout = 5000; readTimeout = 5000
            }
            BitmapFactory.decodeStream(conn.getInputStream())
        } catch (_: Exception) { null }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        abandonAudioFocus()
        mediaSession?.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaPlayer?.release(); mediaPlayer = null
        abandonAudioFocus()
        mediaSession?.release(); mediaSession = null
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
