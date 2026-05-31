package com.mochimochi.clawmikia.utils

import android.content.Context
import android.net.Uri
import com.mochimochi.clawmikia.data.db.MusicDatabase
import com.mochimochi.clawmikia.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {

    suspend fun exportToZip(
        context: Context,
        songs: List<Song>,
        outputFile: File,
        onlyUpdated: Boolean
    ): Int = withContext(Dispatchers.IO) {
        var songsAdded = 0
        try {
            val usedNames = mutableSetOf<String>()
            val invalidChars = Regex("[\\\\/:*?\"<>|]")

            if (songs.isEmpty()) return@withContext 0

            FileOutputStream(outputFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { out ->
                    out.setMethod(ZipOutputStream.DEFLATED)

                    for (song in songs) {
                        try {
                            if (onlyUpdated) {
                                val isUpdated = song.pitchSemitones != 0f ||
                                        song.playbackSpeed != 1.0f ||
                                        song.trimStart > 0L ||
                                        song.trimEnd != -1L ||
                                        song.volume != 1.0f ||
                                        song.isFavorite

                                // "Not recently played" threshold reduced to 5 seconds 
                                // to allow exporting immediately after editing.
                                val recentThresholdMs = 5000L
                                val isNotRecentlyPlayed = song.lastPlayed == 0L ||
                                        (System.currentTimeMillis() - song.lastPlayed > recentThresholdMs)

                                if (!isUpdated || !isNotRecentlyPlayed) continue
                            }

                            val uri = Uri.parse(song.filePath)
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                val safeArtist = song.artist.replace(invalidChars, "_")
                                    .ifBlank { "Unknown Artist" }
                                val safeTitle = song.title.replace(invalidChars, "_")
                                    .ifBlank { "Unknown Title" }
                                var fileName = "$safeArtist - $safeTitle.mp3"

                                var counter = 1
                                while (usedNames.contains(fileName)) {
                                    fileName = "$safeArtist - $safeTitle (${song.id}_$counter).mp3"
                                    counter++
                                }
                                usedNames.add(fileName)

                                val entry = ZipEntry(fileName)
                                out.putNextEntry(entry)
                                inputStream.copyTo(out)
                                out.closeEntry()
                                songsAdded++
                            }
                        } catch (e: Exception) {
                            android.util.Log.e(
                                "Exporter",
                                "Failed to add song ${song.title}: ${e.message}"
                            )
                        }
                    }
                    out.finish()
                    out.flush()
                }
            }
            songsAdded
        } catch (e: Exception) {
            android.util.Log.e("Exporter", "Export failed: ${e.message}")
            -1 // Error code
        }
    }
}
