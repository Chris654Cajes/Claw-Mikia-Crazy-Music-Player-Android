package com.mochimochi.clawmikia.ui.fragments

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
import com.mochimochi.clawmikia.databinding.FragmentFoldersBinding
import com.mochimochi.clawmikia.ui.adapters.FolderAdapter
import com.mochimochi.clawmikia.ui.viewmodels.MainViewModel

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
                    .replace(com.mochimochi.clawmikia.R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onLongClick = { folder ->
                showRenameDialog(folder)
            }
        )

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

    private fun showRenameDialog(folder: com.mochimochi.clawmikia.data.db.FolderInfo) {
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
