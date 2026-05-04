package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.musicvault.R
import com.musicvault.data.model.LyricLine
import com.musicvault.ui.adapters.LyricsAdapter
import com.musicvault.ui.viewmodel.NowPlayingViewModel

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
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                val center = (boxStart + boxEnd) / 2
                return center - (viewStart + viewEnd) / 2
            }
        }
        scroller.targetPosition = idx
        layoutManager.startSmoothScroll(scroller)
    }

    private fun showEditLyricsDialog() {
        val editText = EditText(requireContext()).apply {
            hint =
                "Paste LRC lyrics here (supports [mm:ss.xx] format)\nor plain text lyrics (one line per verse)"
            minLines = 8
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit Lyrics")
            .setView(editText)
            .setPositiveButton("Save LRC") { _, _ ->
                editText.text.toString()
                viewModel.currentSong.value?.let { song ->
                    // Delegate to ViewModel → LyricsManager
                    // The ViewModel exposes a saveLrc method
                }
            }
            .setNeutralButton("Save Plain") { _, _ ->
                editText.text.toString()
                viewModel.currentSong.value?.let { _ ->
                    // savePlainText
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFetchLyricsInfo() {
        AlertDialog.Builder(requireContext())
            .setTitle("Fetch Lyrics")
            .setMessage("Auto-fetching from lrclib.net coming soon.\n\nFor now, you can paste LRC or plain text lyrics manually via the edit button.")
            .setPositiveButton("OK", null)
            .show()
    }
}
