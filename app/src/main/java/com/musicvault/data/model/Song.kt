package com.musicvault.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String,
    val filePath: String,
    val folderPath: String,
    val folderName: String,
    val duration: Long,          // ms
    val fileSize: Long,
    val dateAdded: Long = System.currentTimeMillis(),

    // User customizations (never touch original file)
    val pitchSemitones: Int = 0,       // -6 to +6
    val trimStart: Long = 0,           // ms
    val trimEnd: Long = -1,            // ms, -1 = use full duration
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0,

    // Online metadata (fetched from MusicBrainz/Cover Art Archive, never overwrites file)
    val albumName: String = "",
    val albumArtUrl: String = "",
    val metadataFetched: Boolean = false
)