package com.mochimochi.clawmikiacrazy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.databinding.FragmentLibraryBinding
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.ui.activities.MainActivity
import com.mochimochi.clawmikiacrazy.ui.adapters.LibraryAccordionAdapter
import com.mochimochi.clawmikiacrazy.ui.adapters.LibraryAdapter
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.utils.SwipeToDeleteCallback

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: LibraryAdapter
    private lateinit var accordionAdapter: LibraryAccordionAdapter
    private var latestSongs: List<Song> = emptyList()
    private var isGridMode = false
    private var currentGroupIndex = 0
    private var accordionActive = false
    private var currentAccordionSections: List<BrowseSection>? = null
    private var currentBrowseDialog: BrowseAccordionDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settingsRepo = SettingsRepository(requireContext())

        adapter = LibraryAdapter(
            onSongClick = { song, _ ->
                (activity as? MainActivity)?.playSong(song, latestSongs)
            },
            onFavoriteClick = { song -> viewModel.toggleFavorite(song) }
        ).apply {
            onLongClick = { song ->
                (activity as? MainActivity)?.showSongOptionsDialog(song)
            }
            onSelectionChanged = { mode, count ->
                binding.btnSelectAll.visibility = if (mode) View.VISIBLE else View.GONE
                binding.btnAddToPlaylist.visibility =
                    if (mode && count > 0) View.VISIBLE else View.GONE
                binding.tvSongCount.text =
                    if (mode) "$count selected" else "${latestSongs.size} songs"
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        accordionAdapter = LibraryAccordionAdapter(
            onSongClick = { song, queue ->
                (activity as? MainActivity)?.playSong(song, queue)
            },
            onFavoriteClick = { song -> viewModel.toggleFavorite(song) }
        ).apply {
            onLongClick = { song ->
                (activity as? MainActivity)?.showSongOptionsDialog(song)
            }
        }

        val swipeHandler = object : SwipeToDeleteCallback(requireContext()) {
            override fun onSwiped(
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) {
                val song = adapter.getSongAtPosition(viewHolder.bindingAdapterPosition)
                if (song != null) {
                    (activity as? MainActivity)?.showDeleteConfirmDialog(song)
                }
                adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.recyclerView)

        // ── Folder browser ──────────────────────────────────────────
        binding.btnFolders.setOnClickListener {
            (activity as? MainActivity)?.showFragment(FoldersFragment(), -1)
        }

        // ── Accordion browse (folders / playlists / albums) ─────────
        binding.btnBrowse.setOnClickListener {
            currentBrowseDialog?.let { old ->
                if (old.isAdded) old.dismiss()
            }
            currentBrowseDialog = BrowseAccordionDialog(
                onApplySections = { sections ->
                    applyAccordion(sections)
                },
                resetToOriginal = {
                    resetToSongs()
                }
            ).also { it.show(childFragmentManager, "browse_accordion") }
        }
        // Long-press resets back to the normal song list
        binding.btnBrowse.setOnLongClickListener {
            resetToSongs()
            true
        }

        // ── View toggle (list / grid) ──────────────────────────────
        fun updateViewButtons(isGrid: Boolean) {
            binding.btnViewList.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isGrid) R.color.text_hint else R.color.neon_cyan
                )
            )
            binding.btnViewGrid.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isGrid) R.color.neon_cyan else R.color.text_hint
                )
            )
        }

        binding.btnViewList.setOnClickListener {
            if (adapter.isGridMode) {
                isGridMode = false
                adapter.isGridMode = false
                setupRecyclerLayout()
                updateViewButtons(false)
            }
        }

        binding.btnViewGrid.setOnClickListener {
            if (!adapter.isGridMode) {
                isGridMode = true
                adapter.isGridMode = true
                setupRecyclerLayout()
                updateViewButtons(true)
            }
        }

        // Restore state if available
        if (savedInstanceState != null) {
            isGridMode = savedInstanceState.getBoolean("is_grid_mode", false)
            currentGroupIndex = savedInstanceState.getInt("group_index", 0)
        }

        adapter.isGridMode = isGridMode
        setupRecyclerLayout()
        updateViewButtons(isGridMode)

        // ── Group by album toggle ──────────────────────────────────
        val groupModes = listOf("None", "Album")

        fun updateGroupButton() {
            val mode = groupModes[currentGroupIndex]
            binding.btnGroupBy.text = mode
            val isActive = mode != "None"
            adapter.isGroupByAlbum = mode == "Album"
            binding.btnGroupBy.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (isActive) R.color.neon_cyan else R.color.text_secondary
                )
            )
            binding.btnGroupBy.setBackgroundResource(
                if (isActive) R.drawable.bg_button_outline_cyan
                else R.drawable.bg_button_outline_neutral
            )
        }

        binding.btnGroupBy.setOnClickListener {
            currentGroupIndex = (currentGroupIndex + 1) % groupModes.size
            adapter.isGroupByAlbum = groupModes[currentGroupIndex] == "Album"
            updateGroupButton()
        }

        updateGroupButton()

        // ── Select All / Add to playlist ───────────────────────────
        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        binding.btnAddToPlaylist.setOnClickListener {
            val selectedIds = adapter.getSelectedSongIds()
            if (selectedIds.isNotEmpty()) {
                (activity as? MainActivity)?.showAddToPlaylistDialogMultiple(selectedIds)
                adapter.setSelectionMode(false)
            }
        }

        fun scrollToPlayingSong() {
            val playingId = viewModel.currentSong.value?.id ?: return
            binding.recyclerView.post {
                val targetPos = adapter.getPositionForSong(playingId)
                if (targetPos != -1) binding.recyclerView.scrollToPosition(targetPos)
            }
        }

        // ── Observe songs ──────────────────────────────────────────
        viewModel.filteredSongs.observe(viewLifecycleOwner) { songs ->
            latestSongs = songs
            if (!accordionActive) {
                adapter.submitSongs(songs)
                binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
                binding.tvSongCount.text = "${songs.size} songs"
                scrollToPlayingSong()
            }
        }

        // ── Observe search query: re-filter the accordion in place ──
        viewModel.searchQuery.observe(viewLifecycleOwner) { query ->
            if (accordionActive) {
                binding.recyclerView.post {
                    if (accordionActive) {
                        currentAccordionSections?.let { sections ->
                            accordionAdapter.refreshSections(filterSectionsForQuery(sections, query))
                        }
                    }
                }
            }
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            val playingId = song?.id
            adapter.setCurrentSong(playingId)
            accordionAdapter.setCurrentSong(playingId)
            if (song != null && !accordionActive) scrollToPlayingSong()
        }

        settingsRepo.favoriteIconLive.observe(viewLifecycleOwner) { iconType ->
            adapter.favoriteIconType = iconType
            accordionAdapter.favoriteIconType = iconType
        }
    }

    // ── Accordion apply / reset ────────────────────────────────────
    private fun applyAccordion(sections: List<BrowseSection>) {
        accordionActive = true
        currentAccordionSections = sections
        binding.recyclerView.adapter = accordionAdapter
        accordionAdapter.gridMode = isGridMode
        accordionAdapter.submitSections(filterSectionsForQuery(sections))
        binding.recyclerView.itemAnimator =
            androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 260L
                removeDuration = 260L
                moveDuration = 260L
                supportsChangeAnimations = false
            }
        setupRecyclerLayout()
        binding.tvEmpty.visibility = View.GONE
        binding.tvSongCount.text =
            "${sections.size} section${if (sections.size != 1) "s" else ""} \u00b7 tap headers to expand"
    }

    private fun resetToSongs() {
        if (!accordionActive) return
        accordionActive = false
        currentAccordionSections = null
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator =
            androidx.recyclerview.widget.DefaultItemAnimator()
        setupRecyclerLayout()
        adapter.submitSongs(latestSongs)
        binding.tvSongCount.text = "${latestSongs.size} songs"
        binding.btnViewList.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                requireContext(),
                if (isGridMode) R.color.text_hint else R.color.neon_cyan
            )
        )
        binding.btnViewGrid.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                requireContext(),
                if (isGridMode) R.color.neon_cyan else R.color.text_hint
            )
        )
    }

    /**
     * Applies the correct RecyclerView layout manager. While an accordion is shown in
     * grid mode, its headers span the full row width (so they never collide
     * horizontally) and song rows fill 3 grid cells per row, matching the default
     * library grid (each song shown as a whole, complete item).
     */
    private fun setupRecyclerLayout() {
        val grid = isGridMode
        accordionAdapter.gridMode = grid
        if (accordionActive) {
            if (grid) {
                val spanCount = 3
                val lm = GridLayoutManager(context, spanCount)
                lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (accordionAdapter.getItemViewType(position) ==
                            LibraryAccordionAdapter.TYPE_HEADER
                        ) spanCount else 1
                }
                binding.recyclerView.layoutManager = lm
            } else {
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
            }
        } else {
            binding.recyclerView.layoutManager =
                if (grid) GridLayoutManager(context, 3) else LinearLayoutManager(context)
        }
    }

    /** Filters each section's songs by the current search query, hiding any section that has no matching songs. */
    private fun filterSectionsForQuery(
        sections: List<BrowseSection>,
        query: String = viewModel.searchQuery.value.orEmpty()
    ): List<BrowseSection> {
        val q = query.trim()
        if (q.isEmpty()) return sections
        return sections.mapNotNull { section ->
            val filtered = section.songs.filter { viewModel.songsMatchQuery(it, q) }
            if (filtered.isEmpty()) null else section.copy(songs = filtered)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("is_grid_mode", isGridMode)
        outState.putInt("group_index", currentGroupIndex)
    }
}
