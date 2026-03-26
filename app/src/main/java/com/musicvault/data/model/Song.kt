package com.musicvault.data.model

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

    // User customizations (never touch original file)
    var pitchSemitones: Int = 0,       // -6 to +6
    var trimStart: Long = 0,           // ms
    var trimEnd: Long = -1,            // ms, -1 = use full duration
    var isFavorite: Boolean = false,
    var playCount: Int = 0,
    var lastPlayed: Long = 0,

    // Online metadata (fetched from MusicBrainz/Cover Art Archive, never overwrites file)
    var albumName: String = "",
    var albumArtUrl: String = "",
    var metadataFetched: Boolean = false
)