package com.musicvault.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.musicvault.R
import com.musicvault.data.model.Song
import com.musicvault.databinding.ItemSongBinding
import com.musicvault.utils.formatDuration

class SongAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onFavoriteClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    private var currentSongId: Long? = null

    fun setCurrentSong(id: Long?) {
        val old = currentSongId
        currentSongId = id
        currentList.forEachIndexed { index, song ->
            if (song.id == old || song.id == id) notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), currentList)
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, list: List<Song>) {
            binding.tvTitle.text = song.title

            // Show "Artist • Album" when album name is available from online metadata
            binding.tvArtist.text = if (song.albumName.isNotBlank()) {
                "${song.artist} • ${song.albumName}"
            } else {
                song.artist
            }

            binding.tvDuration.text = formatDuration(song.duration)
            binding.tvFolder.text = song.folderName

            // Album art: load via Glide when URL available, show note icon as fallback
            if (song.albumArtUrl.isNotBlank()) {
                binding.ivAlbumArt.visibility = View.VISIBLE
                binding.ivNoteIcon.visibility = View.GONE
                Glide.with(binding.root.context)
                    .load(song.albumArtUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivAlbumArt)
            } else {
                binding.ivAlbumArt.visibility = View.GONE
                binding.ivNoteIcon.visibility = View.VISIBLE
                Glide.with(binding.root.context).clear(binding.ivAlbumArt)
            }

            // Pitch badge
            if (song.pitchSemitones != 0) {
                val label = if (song.pitchSemitones > 0) "+${song.pitchSemitones}" else "${song.pitchSemitones}"
                binding.tvPitchBadge.text = label
                binding.tvPitchBadge.visibility = View.VISIBLE
            } else {
                binding.tvPitchBadge.visibility = View.GONE
            }

            // Trim badge
            val hasTrim = song.trimStart > 0 || (song.trimEnd > 0 && song.trimEnd < song.duration)
            binding.ivTrimBadge.visibility = if (hasTrim) View.VISIBLE else View.GONE

            // Favorite
            binding.btnFavorite.setImageResource(
                if (song.isFavorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            )

            // Playing indicator
            val isPlaying = song.id == currentSongId
            binding.ivPlayingIndicator.visibility =
                if (isPlaying) View.VISIBLE else View.INVISIBLE
            binding.root.isActivated = isPlaying

            binding.root.setOnClickListener { onSongClick(song, list) }
            binding.btnFavorite.setOnClickListener { onFavoriteClick(song) }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(old: Song, new: Song) = old.id == new.id
        override fun areContentsTheSame(old: Song, new: Song) = old == new
    }
}
