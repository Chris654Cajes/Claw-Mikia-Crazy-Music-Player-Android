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
import com.mochimochi.clawmikiacrazy.ui.adapters.LibraryAdapter
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.utils.SwipeToDeleteCallback

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: LibraryAdapter
    private var latestSongs: List<Song> = emptyList()
    private var isGridMode = false
    private var currentGroupIndex = 0

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
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
                updateViewButtons(false)
            }
        }

        binding.btnViewGrid.setOnClickListener {
            if (!adapter.isGridMode) {
                isGridMode = true
                adapter.isGridMode = true
                binding.recyclerView.layoutManager = GridLayoutManager(context, 3)
                updateViewButtons(true)
            }
        }

        // Restore state if available
        if (savedInstanceState != null) {
            isGridMode = savedInstanceState.getBoolean("is_grid_mode", false)
            currentGroupIndex = savedInstanceState.getInt("group_index", 0)
        }

        adapter.isGridMode = isGridMode
        binding.recyclerView.layoutManager = if (isGridMode) GridLayoutManager(context, 3) else LinearLayoutManager(context)
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
            adapter.submitSongs(songs)
            binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "${songs.size} songs"
            scrollToPlayingSong()
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id)
            if (song != null) scrollToPlayingSong()
        }

        settingsRepo.favoriteIconLive.observe(viewLifecycleOwner) { iconType ->
            adapter.favoriteIconType = iconType
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
