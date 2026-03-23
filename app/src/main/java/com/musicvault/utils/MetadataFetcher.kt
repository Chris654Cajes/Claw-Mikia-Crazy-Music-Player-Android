package com.musicvault.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object MetadataFetcher {

    private const val MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2"
    private const val COVERART_BASE    = "https://coverartarchive.org/release"
    private const val USER_AGENT       = "MusicVaultApp/1.0 (android)"

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
     * If a match is found, store album name + cover art URL in the DB.
     * Original files are never touched.
     * Rate-limited to 1 req/sec to respect MusicBrainz ToS.
     */
    suspend fun fetchMissingMetadata(context: Context) = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext
        val dao = MusicDatabase.getDatabase(context).songDao()
        val songs = dao.getSongsWithoutMetadata()
        for (song in songs) {
            try {
                fetchForSong(song)?.let { (album, artUrl) ->
                    dao.updateOnlineMetadata(song.id, album, artUrl)
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
     * Returns updated Song or null if offline / no match.
     */
    suspend fun fetchForSongNow(context: Context, song: Song): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!isOnline(context)) return@withContext null
            try { fetchForSong(song) } catch (_: Exception) { null }
        }

    // ── internals ────────────────────────────────────────────────────────────

    private fun fetchForSong(song: Song): Pair<String, String>? {
        // Build query: title + artist (if artist is not "Unknown Artist")
        val titleEnc = URLEncoder.encode(song.title, "UTF-8")
        val hasArtist = song.artist.isNotBlank() && song.artist != "Unknown Artist"
        val query = if (hasArtist) {
            val artistEnc = URLEncoder.encode(song.artist, "UTF-8")
            "recording:$titleEnc AND artist:$artistEnc"
        } else {
            "recording:$titleEnc"
        }

        val searchUrl = "$MUSICBRAINZ_BASE/recording/?query=$query&fmt=json&limit=5"
        val searchJson = httpGet(searchUrl) ?: return null
        val recordings = JSONObject(searchJson)
            .optJSONArray("recordings") ?: return null

        // Walk up to 5 results; pick the first one that has a release with a cover
        for (i in 0 until minOf(recordings.length(), 5)) {
            val rec = recordings.getJSONObject(i)
            val releases = rec.optJSONArray("releases") ?: continue
            for (j in 0 until minOf(releases.length(), 3)) {
                val release = releases.getJSONObject(j)
                val releaseId = release.optString("id").takeIf { it.isNotBlank() } ?: continue
                val albumTitle = release.optString("title", "")

                // Try Cover Art Archive for this release
                val artUrl = fetchCoverArtUrl(releaseId)
                if (artUrl != null) {
                    return Pair(albumTitle, artUrl)
                }
            }
            // Return album name even if no art found (for the first result)
            val firstRelease = releases.optJSONObject(0)
            val albumTitle = firstRelease?.optString("title", "") ?: ""
            if (albumTitle.isNotBlank()) {
                return Pair(albumTitle, "")
            }
        }
        return null
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
        } catch (_: Exception) { null }
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
