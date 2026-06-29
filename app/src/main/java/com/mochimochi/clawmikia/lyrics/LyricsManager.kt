package com.mochimochi.clawmikiacrazy.lyrics

import android.content.Context
import android.util.Log
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.model.LyricLine
import com.mochimochi.clawmikiacrazy.data.model.LyricsMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages lyrics loading, LRC parsing, and real-time line synchronization.
 * Supports LRC format (synced) and plain text (unsynced).
 * Searches for .lrc sidecar files alongside audio files automatically.
 */
class LyricsManager(context: Context) {

    private val tag = "LyricsManager"
    private val db = MusicDatabase.getDatabase(context)

    private var currentSongId: Long = -1L
    private var currentLines: List<LyricLine> = emptyList()

    private val _currentLine = MutableStateFlow<LyricLine?>(null)
    private val _allLines = MutableStateFlow<List<LyricLine>>(emptyList())
    private val _hasLyrics = MutableStateFlow(false)

    // ─── Loading ──────────────────────────────────────────────────────────────

    suspend fun loadForSong(songId: Long, filePath: String? = null) = withContext(Dispatchers.IO) {
        currentSongId = songId
        val meta = db.lyricsDao().getMeta(songId)
        if (meta != null) {
            val lines = db.lyricsDao().getLinesSync(songId)
            currentLines = lines
            _allLines.value = lines
            _hasLyrics.value = lines.isNotEmpty()
        } else if (filePath != null) {
            // Try to find sidecar .lrc file
            val lrcLines = tryLoadSidecarLrc(filePath, songId)
            if (lrcLines != null) {
                currentLines = lrcLines
                _allLines.value = lrcLines
                _hasLyrics.value = lrcLines.isNotEmpty()
            } else {
                _hasLyrics.value = false
                currentLines = emptyList()
                _allLines.value = emptyList()
            }
        } else {
            _hasLyrics.value = false
        }
    }

    private suspend fun tryLoadSidecarLrc(audioPath: String, songId: Long): List<LyricLine>? {
        try {
            // For content URIs, we can't get a File path directly
            if (audioPath.startsWith("content://")) return null

            val audioFile = File(audioPath)
            val lrcFile = File(audioFile.parent, audioFile.nameWithoutExtension + ".lrc")
            if (!lrcFile.exists()) return null

            val rawLrc = lrcFile.readText()
            val lines = parseLrc(rawLrc, songId)

            if (lines.isNotEmpty()) {
                val meta = LyricsMeta(
                    songId = songId,
                    source = "sidecar",
                    isSynced = true,
                    rawLrc = rawLrc
                )
                db.lyricsDao().replaceLyrics(meta, lines)
            }
            return lines
        } catch (e: Exception) {
            Log.w(tag, "Sidecar LRC load failed: ${e.message}")
            return null
        }
    }

    // ─── LRC Parser ──────────────────────────────────────────────────────────

    /**
     * Parses an LRC string into LyricLine objects.
     * Supports standard LRC: [mm:ss.xx] or [mm:ss:xx] timestamps.
     */
    suspend fun parseLrc(lrc: String, songId: Long): List<LyricLine> =
        withContext(Dispatchers.Default) {
            val lines = mutableListOf<LyricLine>()
            val timePattern = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})](.*)""")
            val metaPattern = Regex("""\[([a-zA-Z]+):.*\]""")

            var lineIndex = 0
            lrc.lines().forEach { rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isBlank() || metaPattern.matches(trimmed)) return@forEach

                val match = timePattern.find(trimmed)
                if (match != null) {
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val millis = match.groupValues[3].let {
                        if (it.length == 2) it.toLong() * 10 else it.toLong()
                    }
                    val text = match.groupValues[4].trim()
                    if (text.isNotBlank()) {
                        val timeMs = minutes * 60_000L + (seconds * 1000L) + millis
                        lines.add(
                            LyricLine(
                                songId = songId,
                                timeMs = timeMs,
                                text = text,
                                isSynced = true,
                                lineIndex = lineIndex++
                            )
                        )
                    }
                } else if (trimmed.isNotBlank()) {
                    lines.add(
                        LyricLine(
                            songId = songId,
                            timeMs = 0L,
                            text = trimmed,
                            isSynced = false,
                            lineIndex = lineIndex++
                        )
                    )
                }
            }
            lines.sortBy { it.timeMs }
            lines
        }

    fun clearSong() {
        currentSongId = -1L
        currentLines = emptyList()
        _currentLine.value = null
        _allLines.value = emptyList()
        _hasLyrics.value = false
    }
}
