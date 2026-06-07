package com.mochimochi.clawmikia.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.mochimochi.clawmikia.databinding.FragmentLibraryBinding
import com.mochimochi.clawmikia.ui.activities.MainActivity
import com.mochimochi.clawmikia.ui.adapters.SongAdapter
import com.mochimochi.clawmikia.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikia.utils.SwipeToDeleteCallback

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
        val adapter = SongAdapter(
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
                val song = adapter.currentList[viewHolder.bindingAdapterPosition]
                (activity as? MainActivity)?.showDeleteConfirmDialog(song)
                adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(binding.recyclerView)

        viewModel.favorites.observe(viewLifecycleOwner) { songs ->
            adapter.submitList(songs)
            binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "${songs.size} favorites"

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
