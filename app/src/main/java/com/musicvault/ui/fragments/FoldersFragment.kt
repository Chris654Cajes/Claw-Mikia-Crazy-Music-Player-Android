package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.musicvault.databinding.FragmentFoldersBinding
import com.musicvault.ui.adapters.FolderAdapter
import com.musicvault.ui.viewmodels.MainViewModel

class FoldersFragment : Fragment() {

    private var _binding: FragmentFoldersBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = FolderAdapter { folder ->
            // Navigate into folder
            val fragment = FolderSongsFragment.newInstance(folder.folderPath, folder.folderName)
            parentFragmentManager.beginTransaction()
                .replace(com.musicvault.R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commit()
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }

        viewModel.folders.observe(viewLifecycleOwner) { folders ->
            adapter.submitList(folders)
            binding.tvEmpty.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
            binding.tvFolderCount.text = "${folders.size} folders"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
