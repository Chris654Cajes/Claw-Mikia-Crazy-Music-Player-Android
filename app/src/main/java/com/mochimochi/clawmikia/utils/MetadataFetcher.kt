package com.mochimochi.clawmikiacrazy.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MetadataFetcher {

    private const val TAG = "MetadataFetcher"
    private const val MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2"
    private const val COVERART_BASE = "https://coverartarchive.org/release"

    // MusicBrainz actively throttles/blocks (HTTP 503) requests whose User-Agent doesn't
    // identify the application + version + a way to contact the maintainer. A generic UA
    // like "MusicVaultApp/1.0 (android)" gets rate-limited to the "anonymous" tier, which
    // in practice means almost every lookup silently fails. Replace the contact URL below
    // with your own repo/website/email if you have one.
    private const val USER_AGENT = "ClawMikia/1.1 ( https://github.com/clawmikia/clawmikia )"

    /** Simple summary of a batch metadata-update run, surfaced back to the UI. */
    data class UpdateResult(
        val considered: Int,
        val updated: Int
    )

    data class OnlineMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val artUrl: String
    )

    /** Returns true if the device has an active internet connection. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * For every song that hasn't had metadata fetched yet, query MusicBrainz.
     * If a match is found, update title, artist, album name + cover art URL in the DB.
     * Original files are never touched.
     * Rate-limited to 1 req/sec to respect MusicBrainz ToS.
     */
    suspend fun fetchMissingMetadata(
        context: Context,
        overwriteManual: Boolean = false
    ): UpdateResult =
        withContext(Dispatchers.IO) {
            if (!isOnline(context)) return@withContext UpdateResult(considered = 0, updated = 0)
        val dao = MusicDatabase.getDatabase(context).songDao()
            val songs = if (overwriteManual) {
                dao.getAllSongsSync()
            } else {
                dao.getSongsWithoutMetadata()
            }
            val updated = processSongs(dao, songs)
            UpdateResult(considered = songs.size, updated = updated)
    }

    /** Returns the number of songs actually updated. */
    private suspend fun processSongs(
        dao: com.mochimochi.clawmikiacrazy.data.db.SongDao,
        songs: List<Song>
    ): Int {
        var updatedCount = 0
        for (song in songs) {
            try {
                val meta = fetchForSong(song)
                if (meta != null) {
                    dao.updateOnlineMetadata(
                        song.id,
                        meta.title,
                        meta.artist,
                        meta.album,
                        meta.artUrl
                    )
                    updatedCount++
                } else {
                    Log.d(TAG, "No online match for \"${song.title}\" by \"${song.artist}\"")
                }
                // MusicBrainz rate limit: max 1 request/second
                delay(1100)
            } catch (e: Exception) {
                // Network error or no match — log so failures are diagnosable, but keep going
                Log.e(TAG, "Failed to update metadata for \"${song.title}\": ${e.message}", e)
            }
        }
        return updatedCount
    }

    /**
     * Fetch metadata for a single song immediately (e.g. when user taps "refresh").
     * Returns updated OnlineMetadata or null if offline / no match.
     */
    suspend fun fetchForSongNow(context: Context, song: Song): OnlineMetadata? =
        withContext(Dispatchers.IO) {
            if (!isOnline(context)) return@withContext null
            try {
                fetchForSong(song)
            } catch (_: Exception) {
                null
            }
        }

    // ── internals ────────────────────────────────────────────────────────────

    private fun fetchForSong(song: Song): OnlineMetadata? {
        // Build query: title + artist (if artist is not "Unknown Artist")
        // Cleaning title: remove [Official Video], (Lyrics) etc for better matching
        val cleanedTitle = song.title
            .replace(Regex("(?i)\\[.*?\\]"), "")
            .replace(Regex("(?i)\\(.*?\\)"), "")
            .trim()
            .let(::escapeLucene)
        val escapedArtist = escapeLucene(song.artist)

        val query = if (song.artist.isNotBlank() && song.artist != "Unknown Artist") {
            "recording:\"$cleanedTitle\" AND artist:\"$escapedArtist\""
        } else {
            "recording:\"$cleanedTitle\""
        }
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val searchUrl = "$MUSICBRAINZ_BASE/recording/?query=$encodedQuery&fmt=json&limit=5"
        val searchJson = httpGet(searchUrl) ?: return null
        val recordings = JSONObject(searchJson)
            .optJSONArray("recordings") ?: return null

        // Walk up to 5 results; pick the first one that looks like a good match
        for (i in 0 until minOf(recordings.length(), 5)) {
            val rec = recordings.getJSONObject(i)

            val metaTitle = rec.optString("title", song.title)
            val artistCredit = rec.optJSONArray("artist-credit")
            val metaArtist = parseArtistCredit(artistCredit).ifBlank { song.artist }

            val releases = rec.optJSONArray("releases") ?: continue
            for (j in 0 until minOf(releases.length(), 5)) {
                val release = releases.getJSONObject(j)
                val releaseId = release.optString("id").takeIf { it.isNotBlank() } ?: continue
                val albumTitle = release.optString("title", "")

                // Try Cover Art Archive for this release
                val artUrl = fetchCoverArtUrl(releaseId)
                if (artUrl != null) {
                    return OnlineMetadata(metaTitle, metaArtist, albumTitle, artUrl)
                }
            }

            // If no cover art found after checking releases, return the first valid album name
            val firstRelease = releases.optJSONObject(0)
            val albumTitle = firstRelease?.optString("title", "") ?: ""
            if (albumTitle.isNotBlank()) {
                return OnlineMetadata(metaTitle, metaArtist, albumTitle, "")
            }
        }
        return null
    }

    /**
     * Escapes characters that are reserved in MusicBrainz's Lucene-based search syntax.
     * A raw quote or backslash in a title/artist (e.g. Rock "n" Roll) would otherwise break
     * the quoted phrase and make the whole query invalid — MusicBrainz then returns an
     * error, which was previously being swallowed as a silent "no match".
     */
    private fun escapeLucene(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseArtistCredit(array: JSONArray?): String {
        if (array == null || array.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            sb.append(obj.optString("name"))
            if (obj.has("joinphrase")) {
                sb.append(obj.optString("joinphrase"))
            }
        }
        return sb.toString().trim()
    }

    private fun fetchCoverArtUrl(releaseId: String): String? {
        // Cover Art Archive: HEAD check first to avoid 404 penalty
        val url = "$COVERART_BASE/$releaseId/front-250"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) url else null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "MusicBrainz request failed (HTTP $code): $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
