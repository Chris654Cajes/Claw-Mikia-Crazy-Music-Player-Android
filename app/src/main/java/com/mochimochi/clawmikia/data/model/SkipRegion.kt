package com.mochimochi.clawmikia.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A region in a song that should be automatically skipped during playback.
 * Multiple skip regions per song are supported (e.g. skip ads, intros, outros).
 * Original file is NEVER modified.
 */
@Entity(
    tableName = "skip_regions",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("songId")]
)
data class SkipRegion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val label: String = "",                  // e.g. "Intro", "Ad break", "Outro"
    val startMs: Long,
    val endMs: Long,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
