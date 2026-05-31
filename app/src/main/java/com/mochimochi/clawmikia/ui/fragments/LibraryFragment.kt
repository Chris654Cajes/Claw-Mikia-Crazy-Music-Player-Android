package com.mochimochi.clawmikia.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.mochimochi.clawmikia.data.model.Song
import com.mochimochi.clawmikia.databinding.FragmentLibraryBinding
import com.mochimochi.clawmikia.ui.activities.MainActivity
import com.mochimochi.clawmikia.ui.adapters.SongAdapter
import com.mochimochi.clawmikia.ui.viewmodels.MainViewModel

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter
    private var latestSongs: List<Song> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SongAdapter(
            onSongClick = { song, _ ->
                // Pass the full current list so the service has the complete playlist
                (activity as? MainActivity)?.playSong(song, latestSongs)
            },
            onFavoriteClick = { song -> viewModel.toggleFavorite(song) }
        ).apply {
            onLongClick = { _ ->
                setSelectionMode(true)
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

        viewModel.filteredSongs.observe(viewLifecycleOwner) { songs ->
            latestSongs = songs
            adapter.submitList(songs)
            binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "${songs.size} songs"

            // Sync the service playlist if we are in this layout
            (activity as? MainActivity)?.updateCurrentPlaylist(songs)
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
