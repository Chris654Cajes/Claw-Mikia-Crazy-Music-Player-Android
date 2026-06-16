package com.mochimochi.clawmikia.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import android.net.Uri
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.data.db.MusicDatabase
import com.mochimochi.clawmikia.data.model.LyricLine
import com.mochimochi.clawmikia.data.model.LyricsMeta
import com.mochimochi.clawmikia.ui.adapters.LyricsAdapter
import com.mochimochi.clawmikia.ui.viewmodels.NowPlayingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class LyricsFragment : BottomSheetDialogFragment() {

    private val viewModel: NowPlayingViewModel by activityViewModels()
    private lateinit var adapter: LyricsAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private var isUserScrolling = false

    companion object {
        fun newInstance() = LyricsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_lyrics, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.rvLyrics)
        layoutManager = LinearLayoutManager(requireContext())
        adapter = LyricsAdapter()

        recycler.layoutManager = layoutManager
        recycler.adapter = adapter

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                isUserScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
            }
        })

        view.findViewById<SwitchMaterial>(R.id.switchKaraoke)
            ?.setOnCheckedChangeListener { _, checked ->
                adapter.karaokeMode = checked
            }

        view.findViewById<android.widget.ImageButton>(R.id.btnEditLyrics)?.setOnClickListener {
            showEditLyricsDialog()
        }

        view.findViewById<android.widget.ImageButton>(R.id.btnFetchLyrics)?.setOnClickListener {
            fetchLyricsFromLrcLib()
        }

        view.findViewById<android.widget.Button>(R.id.btnAddLyrics)?.setOnClickListener {
            showEditLyricsDialog()
        }

        observeViewModel(view)
    }

    private fun observeViewModel(view: View) {
        viewModel.lyrics.observe(viewLifecycleOwner) { lines ->
            adapter.submitList(lines)
            val noLyricsLayout = view.findViewById<LinearLayout>(R.id.layoutNoLyrics)
            noLyricsLayout?.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.currentLyricLine.observe(viewLifecycleOwner) { activeLine ->
            adapter.activeLine = activeLine
            autoScrollToActiveLine(activeLine)
        }
    }

    private fun autoScrollToActiveLine(line: LyricLine?) {
        if (line == null || isUserScrolling) return
        val idx = adapter.currentList.indexOfFirst { it.id == line.id }
        if (idx < 0) return

        val scroller = object : LinearSmoothScroller(requireContext()) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int,
                boxStart: Int, boxEnd: Int,
                snapPreference: Int
            ): Int {
                val center = (boxStart + boxEnd) / 2
                return center - (viewStart + viewEnd) / 2
            }
        }
        scroller.targetPosition = idx
        layoutManager.startSmoothScroll(scroller)
    }

    // ─── Edit / Save Lyrics ──────────────────────────────────────────────────────

    private fun showEditLyricsDialog() {
        val initialText = viewModel.lyricsMeta.value?.rawLrc ?: ""
        showAestheticLyricsDialog(
            initialText = initialText,
            onSaveLRC = { lrcText ->
                val songId = viewModel.currentSong.value?.id ?: return@showAestheticLyricsDialog
                lifecycleScope.launch { saveLyrics(songId, lrcText, synced = true) }
            },
            onSavePlain = { plainText ->
                val songId = viewModel.currentSong.value?.id ?: return@showAestheticLyricsDialog
                lifecycleScope.launch { saveLyrics(songId, plainText, synced = false) }
            }
        )
    }

    /**
     * Persists lyrics to the database using a transaction then reloads them into the ViewModel.
     * Removes restrictions: any text will be saved, falling back to unsynced lines if no LRC tags found.
     */
    private suspend fun saveLyrics(songId: Long, text: String, synced: Boolean) {
        if (text.isBlank()) return
        val db = MusicDatabase.getDatabase(requireContext())

        withContext(Dispatchers.IO) {
            val lines: List<LyricLine> = if (synced) {
                parseLrc(songId, text)
            } else {
                text.lines()
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, rawLine ->
                        LyricLine(
                            songId = songId,
                            timeMs = 0L,
                            text = rawLine.trim(),
                            isSynced = false,
                            lineIndex = i
                        )
                    }
            }

            // Fallback: if "synced" mode resulted in nothing, save as plain text
            val finalLines = if (synced && lines.isEmpty()) {
                text.lines()
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, rawLine ->
                        LyricLine(
                            songId = songId,
                            timeMs = 0L,
                            text = rawLine.trim(),
                            isSynced = false,
                            lineIndex = i
                        )
                    }
            } else lines

            val meta = LyricsMeta(
                songId = songId,
                source = "manual",
                isSynced = finalLines.any { it.isSynced },
                rawLrc = text
            )

            db.lyricsDao().replaceLyrics(meta, finalLines)
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(requireContext(), "Lyrics updated", Toast.LENGTH_SHORT).show()
        }

        // Refresh lyrics in ViewModel
        viewModel.currentSong.value?.let { song ->
            viewModel.setSong(song, viewModel.isPlaying.value ?: false)
        }
    }

    /**
     * Robust LRC parser with NO restrictions.
     * Matches standard and non-standard timestamps, treats everything else as plain lines.
     */
    private fun parseLrc(songId: Long, lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        var index = 0
        // Matches [mm:ss.xx], [mm:ss:xx], [mm:ss.xxx], [mm:ss]
        val tsRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")

        lrc.lines().forEach { line ->
            val matchResults = tsRegex.findAll(line).toList()
            if (matchResults.isNotEmpty()) {
                val content = line.replace(tsRegex, "").trim()
                matchResults.forEach { match ->
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = if (match.groupValues.size > 3) match.groupValues[3] else ""
                    val frac = fracStr.toLongOrNull() ?: 0L
                    val fracMs = when (fracStr.length) {
                        2 -> frac * 10L
                        1 -> frac * 100L
                        else -> frac
                    }
                    val timeMs = min * 60_000L + sec * 1_000L + fracMs
                    result.add(
                        LyricLine(
                            songId = songId, timeMs = timeMs,
                            text = if (content.isEmpty()) "..." else content,
                            isSynced = true, lineIndex = index++
                        )
                    )
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    // Any text is allowed
                    result.add(
                        LyricLine(
                            songId = songId, timeMs = 0L,
                            text = trimmed, isSynced = false, lineIndex = index++
                        )
                    )
                }
            }
        }
        return result.sortedWith(compareBy({ it.timeMs }, { it.lineIndex }))
    }

    private fun fetchLyricsFromLrcLib() {
        val song = viewModel.currentSong.value ?: return
        val artist = song.artist
        val title = song.title
        val duration = song.duration / 1000

        val url = "https://lrclib.net/api/get?artist_name=${Uri.encode(artist)}&track_name=${
            Uri.encode(title)
        }&duration=$duration"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "MusicVault (https://github.com/MusicVault)")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    "Lyrics not found online",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@use
                        }

                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val syncedLrc = json.optString("syncedLyrics")
                        val plainLyrics = json.optString("plainLyrics")

                        withContext(Dispatchers.Main) {
                            if (!syncedLrc.isNullOrBlank()) {
                                saveLyrics(song.id, syncedLrc, true)
                            } else if (!plainLyrics.isNullOrBlank()) {
                                saveLyrics(song.id, plainLyrics, false)
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    "Empty lyrics response",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Fetch error: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticLyricsDialog(
        initialText: String = "",
        onSaveLRC: (String) -> Unit,
        onSavePlain: (String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_lyrics, null)
        val etLyrics = dialogView
            .findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etLyrics)

        etLyrics.setText(initialText)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView).create()
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCancel)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnSavePlain)
            .setOnClickListener { onSavePlain(etLyrics.text.toString()); dialog.dismiss() }
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnSaveLRC)
            .setOnClickListener { onSaveLRC(etLyrics.text.toString()); dialog.dismiss() }
        dialog.show()
    }
}
