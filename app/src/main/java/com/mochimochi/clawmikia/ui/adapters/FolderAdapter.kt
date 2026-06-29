package com.mochimochi.clawmikiacrazy.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mochimochi.clawmikiacrazy.data.db.FolderInfo
import com.mochimochi.clawmikiacrazy.databinding.ItemFolderBinding

class FolderAdapter(
    private val onClick: (FolderInfo) -> Unit,
    private val onLongClick: (FolderInfo) -> Unit
) : ListAdapter<FolderInfo, FolderAdapter.FolderViewHolder>(FolderDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FolderViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(info: FolderInfo) {
            binding.tvFolderName.text = info.folderName
            binding.tvFolderPath.text = info.folderPath
            binding.root.setOnClickListener { onClick(info) }
            binding.root.setOnLongClickListener {
                onLongClick(info)
                true
            }
        }
    }

    class FolderDiff : DiffUtil.ItemCallback<FolderInfo>() {
        override fun areItemsTheSame(a: FolderInfo, b: FolderInfo) = a.folderPath == b.folderPath
        override fun areContentsTheSame(a: FolderInfo, b: FolderInfo) = a == b
    }
}
