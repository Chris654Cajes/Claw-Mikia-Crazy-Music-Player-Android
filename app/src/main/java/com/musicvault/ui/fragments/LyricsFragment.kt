package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.musicvault.R
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.model.LyricLine
import com.musicvault.data.model.LyricsMeta
import com.musicvault.ui.adapters.LyricsAdapter
import com.musicvault.ui.viewmodel.NowPlayingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsFragment : BottomSheetDialogFragment() {

    private val viewModel: NowPlayingViewModel by activityViewModels()
    private val fragmentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
            showFetchLyricsInfo()
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
        showAestheticLyricsDialog(
            onSaveLRC = { lrcText ->
                val songId = viewModel.currentSong.value?.id ?: return@showAestheticLyricsDialog
                fragmentScope.launch { saveLyrics(songId, lrcText, synced = true) }
            },
            onSavePlain = { plainText ->
                val songId = viewModel.currentSong.value?.id ?: return@showAestheticLyricsDialog
                fragmentScope.launch { saveLyrics(songId, plainText, synced = false) }
            }
        )
    }

    /**
     * Persists lyrics to the database then reloads them into the ViewModel so
     * the RecyclerView updates instantly without requiring the user to close and
     * reopen the sheet.
     *
     * LRC format (synced = true)  → timestamps parsed, each line stored with timeMs.
     * Plain text (synced = false) → each non-blank line stored in order with timeMs = 0.
     */
    private suspend fun saveLyrics(songId: Long, text: String, synced: Boolean) {
        if (text.isBlank()) return
        val db = MusicDatabase.getDatabase(requireContext())

        withContext(Dispatchers.IO) {
            // Remove previous lyrics for this song
            db.lyricsDao().deleteMetaForSong(songId)
            db.lyricsDao().deleteLinesForSong(songId)

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

            val meta = LyricsMeta(
                songId = songId,
                source = "manual",
                isSynced = synced,
                rawLrc = text
            )
            db.lyricsDao().insertMeta(meta)
            if (lines.isNotEmpty()) db.lyricsDao().insertLines(lines)
        }

        // Refresh lyrics in ViewModel (runs on IO then posts to Main via LiveData)
        withContext(Dispatchers.IO) { db.lyricsDao().getLinesSync(songId) }
        viewModel.currentSong.value?.let { song ->
            viewModel.setSong(song, viewModel.isPlaying.value ?: false)
        }
    }

    /**
     * Minimal LRC parser supporting [mm:ss.xx] and [mm:ss.xxx] timestamps.
     */
    private fun parseLrc(songId: Long, lrc: String): List<LyricLine> {
        val tsPattern = Regex("""^\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.*)""")
        val result = mutableListOf<LyricLine>()
        var index = 0
        lrc.lines().forEach { raw ->
            val line = raw.trim()
            val match = tsPattern.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val frac = match.groupValues[3].toLongOrNull() ?: 0L
                // Normalise 2-digit centiseconds → ms, 3-digit ms → ms
                val fracMs = if (match.groupValues[3].length == 2) frac * 10L else frac
                val timeMs = min * 60_000L + sec * 1_000L + fracMs
                val content = match.groupValues[4].trim()
                if (content.isNotBlank()) {
                    result.add(
                        LyricLine(
                            songId = songId, timeMs = timeMs,
                            text = content, isSynced = true, lineIndex = index++
                        )
                    )
                }
            } else if (line.isNotBlank() && !line.startsWith("[")) {
                // Plain text line embedded in an LRC file
                result.add(
                    LyricLine(
                        songId = songId, timeMs = 0L,
                        text = line, isSynced = false, lineIndex = index++
                    )
                )
            }
        }
        return result
    }

    private fun showFetchLyricsInfo() {
        showAestheticConfirmDialog(
            title = "Fetch Lyrics",
            message = "Auto-fetching from lrclib.net coming soon.\n\nFor now, paste LRC or plain text lyrics manually via the edit button.",
            positiveText = "OK"
        ) { }
    }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticConfirmDialog(
        title: String,
        message: String,
        positiveText: String = "Confirm",
        onPositive: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(R.id.tvTitle).text = title
        dialogView.findViewById<android.widget.TextView>(R.id.tvMessage).text = message
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView).create()
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCancel)
            .setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<android.widget.ImageButton>(R.id.btnConfirm)
            .setOnClickListener { onPositive(); dialog.dismiss() }
        dialog.show()
    }

    private fun showAestheticLyricsDialog(
        onSaveLRC: (String) -> Unit,
        onSavePlain: (String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_lyrics, null)
        val etLyrics = dialogView
            .findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etLyrics)
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
