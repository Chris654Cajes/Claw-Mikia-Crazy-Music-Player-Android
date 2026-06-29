package com.mochimochi.clawmikiacrazy.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.mochimochi.clawmikiacrazy.databinding.FragmentLibraryBinding
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.ui.activities.MainActivity
import com.mochimochi.clawmikiacrazy.ui.adapters.SongAdapter
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.utils.SwipeToDeleteCallback

class FolderSongsFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    companion object {
        private const val ARG_PATH = "folder_path"
        private const val ARG_NAME = "folder_name"
        fun newInstance(path: String, name: String) = FolderSongsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PATH, path)
                putString(ARG_NAME, name)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val folderPath = arguments?.getString(ARG_PATH) ?: return
        val folderName = arguments?.getString(ARG_NAME) ?: "Folder"
        val settingsRepo = SettingsRepository(requireContext())

        binding.tvSongCount.text = folderName

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

        viewModel.getSongsByFolder(folderPath).observe(viewLifecycleOwner) { songs ->
            adapter.submitList(songs)
            binding.tvEmpty.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvSongCount.text = "$folderName — ${songs.size} songs"

            // Sync the service playlist if we are in this layout
            (activity as? MainActivity)?.updateCurrentPlaylist(songs)
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id)
        }

        // Observe favorite icon type setting and refresh adapter
        settingsRepo.favoriteIconLive.observe(viewLifecycleOwner) { iconType ->
            adapter.favoriteIconType = iconType
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
