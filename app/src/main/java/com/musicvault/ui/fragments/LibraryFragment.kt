package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicvault.data.model.Song
import com.musicvault.databinding.FragmentLibraryBinding
import com.musicvault.ui.activities.MainActivity
import com.musicvault.ui.adapters.SongAdapter
import com.musicvault.ui.viewmodels.MainViewModel

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
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        viewModel.filteredSongs.observe(viewLifecycleOwner) { songs ->
            latestSongs = songs
            adapter.submitList(songs)
            binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "${songs.size} songs"
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
