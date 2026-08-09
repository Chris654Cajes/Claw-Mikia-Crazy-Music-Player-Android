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

class FavoritesFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settingsRepo = SettingsRepository(requireContext())
        val adapter = LibraryAdapter(
            onSongClick = { song, list ->
                (activity as? MainActivity)?.playSong(song, list)
            },
            onFavoriteClick = { song -> viewModel.toggleFavorite(song) }
        ).apply {
            onLongClick = { song ->
                (activity as? MainActivity)?.showSongOptionsDialog(song)
            }
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
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
                adapter.isGridMode = false
                binding.recyclerView.layoutManager = LinearLayoutManager(context)
                updateViewButtons(false)
            }
        }

        binding.btnViewGrid.setOnClickListener {
            if (!adapter.isGridMode) {
                adapter.isGridMode = true
                binding.recyclerView.layoutManager = GridLayoutManager(context, 3)
                updateViewButtons(true)
            }
        }

        updateViewButtons(false)

        // ── Group by album toggle ──────────────────────────────────
        val groupModes = listOf("None", "Album")
        var currentGroupIndex = 0

        fun updateGroupButton() {
            val mode = groupModes[currentGroupIndex]
            binding.btnGroupBy.text = mode
            val isActive = mode != "None"
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

        var allFavorites: List<Song> = emptyList()
        var searchQuery: String = ""

        fun refresh() {
            val filtered = allFavorites.filter { viewModel.songsMatchQuery(it, searchQuery) }
            adapter.submitSongs(filtered)
            binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "${filtered.size} favorites"
        }

        viewModel.favorites.observe(viewLifecycleOwner) { songs ->
            allFavorites = songs
            refresh()
        }

        viewModel.searchQuery.observe(viewLifecycleOwner) { query ->
            searchQuery = query
            refresh()
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id)
        }

        settingsRepo.favoriteIconLive.observe(viewLifecycleOwner) { iconType ->
            adapter.favoriteIconType = iconType
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
