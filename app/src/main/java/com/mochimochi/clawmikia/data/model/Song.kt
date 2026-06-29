package com.mochimochi.clawmikiacrazy.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var title: String,
    var artist: String,
    var filePath: String,
    var folderPath: String,
    var folderName: String,
    var duration: Long,          // ms
    var fileSize: Long,
    var dateAdded: Long = System.currentTimeMillis(),
    var dateModified: Long = System.currentTimeMillis(),

    // User customizations (never touch original file)
    var pitchSemitones: Float = 0f,       // -6 to +6 (float for fine control)
    var trimStart: Long = 0,           // ms
    var trimEnd: Long = -1,            // ms, -1 = use full duration
    var volume: Float = 1.0f,          // 0.0f to 1.0f
    var isFavorite: Boolean = false,
    var playCount: Int = 0,
    var lastPlayed: Long = 0,
    var playbackSpeed: Float = 1.0f,   // 0.5x to 2.0x
    var repeatMode: Int = 0,           // 0=none, 1=one, 2=all

    // Online metadata (fetched from MusicBrainz/Cover Art Archive, never overwrites file)
    var albumName: String = "",
    var albumArtUrl: String = "",
    var metadataFetched: Boolean = false,

    // Manual edits indicator
    var isManuallyEdited: Boolean = false
)