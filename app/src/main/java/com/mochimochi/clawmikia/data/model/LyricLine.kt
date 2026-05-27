package com.mochimochi.clawmikia.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single timestamped lyric line for a song.
 * Supports LRC-style synced lyrics and plain text.
 */
@Entity(
    tableName = "lyric_lines",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId")]
)
data class LyricLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val timeMs: Long,           // timestamp in ms; 0 for unsynced lines
    val text: String,
    val isSynced: Boolean = true,
    val lineIndex: Int = 0      // ordering for unsynced lyrics
)

/**
 * Metadata about a song's complete lyrics entry.
 * One record per song — the actual lines are in lyric_lines.
 */
@Entity(
    tableName = "lyrics_meta",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId", unique = true)]
)
data class LyricsMeta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val source: String = "local",    // "local", "lrclib", "genius", "manual"
    val isSynced: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis(),
    val rawLrc: String = ""          // original LRC text for re-parsing if needed
)
