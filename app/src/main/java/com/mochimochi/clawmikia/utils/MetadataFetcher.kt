package com.mochimochi.clawmikia.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mochimochi.clawmikia.data.db.MusicDatabase
import com.mochimochi.clawmikia.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MetadataFetcher {

    private const val MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2"
    private const val COVERART_BASE = "https://coverartarchive.org/release"
    private const val USER_AGENT = "MusicVaultApp/1.0 (android)"

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
    suspend fun fetchMissingMetadata(context: Context) = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext
        val dao = MusicDatabase.getDatabase(context).songDao()
        val songs = dao.getSongsWithoutMetadata()
        for (song in songs) {
            try {
                fetchForSong(song)?.let { meta ->
                    dao.updateOnlineMetadata(
                        song.id,
                        meta.title,
                        meta.artist,
                        meta.album,
                        meta.artUrl
                    )
                }
                // MusicBrainz rate limit: max 1 request/second
                delay(1100)
            } catch (_: Exception) {
                // Network error or no match — silently skip, metadataFetched stays false
                // so it will be retried next time the user is online
            }
        }
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

        val query = if (song.artist.isNotBlank() && song.artist != "Unknown Artist") {
            "recording:\"$cleanedTitle\" AND artist:\"${song.artist}\""
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
            if (conn.responseCode == 200)
                conn.inputStream.bufferedReader().readText()
            else null
        } finally {
            conn.disconnect()
        }
    }
}
