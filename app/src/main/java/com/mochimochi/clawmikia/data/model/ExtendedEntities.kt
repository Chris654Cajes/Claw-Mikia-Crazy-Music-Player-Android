package com.mochimochi.clawmikia.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Waveform Cache ────────────────────────────────────────────────────────────
/** Cached waveform amplitude data so we don't re-analyze every time. */
@Entity(
    tableName = "waveform_cache",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId", unique = true)]
)
data class WaveformCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    /** Comma-separated normalized amplitude floats (0.0–1.0), ~1000 samples. */
    val amplitudes: String,
    val sampleCount: Int,
    val generatedAt: Long = System.currentTimeMillis()
) {
    fun amplitudeList(): FloatArray =
        amplitudes.split(",").mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
}

// ─── Playback History ─────────────────────────────────────────────────────────
/** One record per play session — enables history UI and smart recommendations. */
@Entity(
    tableName = "playback_history",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId"), Index("playedAt")]
)
data class PlaybackHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long = System.currentTimeMillis(),
    val durationListened: Long = 0L,   // ms actually listened (excluding skipped regions)
    val completedFully: Boolean = false
)

// ─── Song Analysis ─────────────────────────────────────────────────────────────
/** BPM, key, chorus detection, silence regions — computed once, cached forever. */
@Entity(
    tableName = "song_analysis",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId", unique = true)]
)
data class SongAnalysis(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,

    // BPM detection
    val bpm: Float = 0f,
    val bpmConfidence: Float = 0f,      // 0.0–1.0

    // Key detection (e.g. "C Major", "A Minor")
    val key: String = "",
    val keyConfidence: Float = 0f,

    // Chorus markers (comma-separated ms timestamps)
    val chorusTimestamps: String = "",

    // Silence regions (JSON array of {start, end} objects as string)
    val silenceRegions: String = "",

    // Loop suggestions (best loop start/end based on waveform similarity)
    val suggestedLoopStart: Long = -1L,
    val suggestedLoopEnd: Long = -1L,

    val analyzedAt: Long = System.currentTimeMillis(),
    val analysisVersion: Int = 1        // bump to re-analyze on algorithm upgrade
) {
    fun chorusList(): List<Long> =
        chorusTimestamps.split(",").mapNotNull { it.trim().toLongOrNull() }

    fun silenceList(): List<Pair<Long, Long>> {
        if (silenceRegions.isBlank()) return emptyList()
        return try {
            // Simple parsing of "start:end,start:end" format
            silenceRegions.split(",").mapNotNull { part ->
                val p = part.split(":")
                if (p.size == 2) Pair(p[0].toLong(), p[1].toLong()) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

// ─── Playlist ─────────────────────────────────────────────────────────────────
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val artworkSongId: Long = -1L,      // song whose art to use as playlist cover
    val isSmartPlaylist: Boolean = false,
    val smartQuery: String = "",         // SQL WHERE clause for smart playlists
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("songId")],
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSong(
    val playlistId: Long,
    val songId: Long,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
