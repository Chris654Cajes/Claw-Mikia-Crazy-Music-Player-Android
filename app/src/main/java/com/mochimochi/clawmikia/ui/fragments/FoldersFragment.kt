package com.mochimochi.clawmikiacrazy.ui.fragments

import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.mochimochi.clawmikiacrazy.databinding.FragmentFoldersBinding
import com.mochimochi.clawmikiacrazy.ui.adapters.FolderAdapter
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.ui.activities.MainActivity

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
        val adapter = FolderAdapter(
            onClick = { folder ->
                // Navigate into folder
                val fragment = FolderSongsFragment.newInstance(folder.folderPath, folder.folderName)
                parentFragmentManager.beginTransaction()
                    .replace(com.mochimochi.clawmikiacrazy.R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onLongClick = { folder ->
                showRenameDialog(folder)
            }
        )

        binding.btnBack.setOnClickListener {
            (activity as? MainActivity)?.selectBottomNavItem(com.mochimochi.clawmikiacrazy.R.id.nav_library)
        }

        binding.btnFolders.setOnClickListener {
            (activity as? MainActivity)?.openFolderPicker()
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }

        var allFolders: List<com.mochimochi.clawmikiacrazy.data.db.FolderInfo> = emptyList()
        var searchQuery: String = ""

        fun refresh() {
            val filtered = allFolders.filter { folder ->
                searchQuery.isBlank() ||
                        folder.folderName.contains(searchQuery, ignoreCase = true) ||
                        folder.folderPath.contains(searchQuery, ignoreCase = true)
            }
            adapter.submitList(filtered)
            binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
            binding.tvFolderCount.text = "${filtered.size} folders"
        }

        viewModel.folders.observe(viewLifecycleOwner) { folders ->
            allFolders = folders
            refresh()
        }

        viewModel.searchQuery.observe(viewLifecycleOwner) { query ->
            searchQuery = query
            refresh()
        }
    }

    private fun showRenameDialog(folder: com.mochimochi.clawmikiacrazy.data.db.FolderInfo) {
        val editText = EditText(requireContext()).apply {
            setText(folder.folderName)
            setSelection(folder.folderName.length)
        }
        val container = FrameLayout(requireContext())
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 50
            rightMargin = 50
            topMargin = 20
        }
        container.addView(editText, params)

        AlertDialog.Builder(requireContext())
            .setTitle("Rename Folder")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotBlank()) {
                    viewModel.renameFolder(folder.folderPath, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
