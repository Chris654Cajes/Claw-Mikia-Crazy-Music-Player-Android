package com.mochimochi.clawmikiacrazy.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.databinding.ItemSongBinding
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration

class SongAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onFavoriteClick: (Song) -> Unit,
    private val onRemoveClick: ((Song) -> Unit)? = null,
    var onSelectionChanged: ((Boolean, Int) -> Unit)? = null,
    var onLongClick: ((Song) -> Unit)? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    private var currentSongId: Long? = null
    private val selectedSongs = mutableSetOf<Long>()
    private var selectionMode = false

    /** The current favorite icon type (e.g. "heart", "star", etc.) */
    var favoriteIconType: String = "heart"
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun setCurrentSong(id: Long?) {
        val old = currentSongId
        currentSongId = id
        currentList.forEachIndexed { index, song ->
            if (song.id == old || song.id == id) notifyItemChanged(index)
        }
    }

    fun isSelectionMode() = selectionMode

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedSongs.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectionMode, selectedSongs.size)
    }

    fun toggleSelection(songId: Long) {
        if (selectedSongs.contains(songId)) selectedSongs.remove(songId)
        else selectedSongs.add(songId)
        currentList.forEachIndexed { index, song ->
            if (song.id == songId) notifyItemChanged(index)
        }
        onSelectionChanged?.invoke(selectionMode, selectedSongs.size)
    }

    fun getSelectedSongIds(): List<Long> = selectedSongs.toList()

    fun selectAll() {
        if (!selectionMode) setSelectionMode(true)
        currentList.forEach { selectedSongs.add(it.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectionMode, selectedSongs.size)
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

            // Album art
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

            // Pitch/Trim/Speed badges
            binding.tvPitchBadge.visibility =
                if (song.pitchSemitones != 0f) View.VISIBLE else View.GONE
            if (song.pitchSemitones != 0f) {
                binding.tvPitchBadge.text =
                    if (song.pitchSemitones > 0) "+%.1f".format(song.pitchSemitones) else "%.1f".format(
                        song.pitchSemitones
                    )
            }
            binding.ivTrimBadge.visibility =
                if (song.trimStart > 0 || (song.trimEnd > 0 && song.trimEnd < song.duration)) View.VISIBLE else View.GONE
            binding.ivSpeedBadge.visibility =
                if (song.playbackSpeed != 1.0f) View.VISIBLE else View.GONE
            binding.ivManualBadge.visibility =
                if (song.isManuallyEdited) View.VISIBLE else View.GONE

            // Favorite vs Remove
            if (onRemoveClick != null && !selectionMode) {
                binding.btnFavorite.setImageResource(android.R.drawable.ic_menu_delete)
                binding.btnFavorite.setColorFilter(android.graphics.Color.parseColor("#FF0000"))
                binding.btnFavorite.setOnClickListener { onRemoveClick.invoke(song) }
            } else {
                binding.btnFavorite.setImageResource(
                    if (song.isFavorite) FavoriteIconHelper.filledRes(favoriteIconType) else FavoriteIconHelper.outlineRes(
                        favoriteIconType
                    )
                )
                binding.btnFavorite.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(
                        binding.root.context,
                        FavoriteIconHelper.colorRes(favoriteIconType)
                    )
                )
                binding.btnFavorite.setOnClickListener { onFavoriteClick(song) }
            }

            // Selection overlay/indicator
            if (selectionMode) {
                val isSelected = selectedSongs.contains(song.id)
                binding.root.setBackgroundColor(
                    if (isSelected) android.graphics.Color.parseColor("#3300E5FF")
                    else android.graphics.Color.TRANSPARENT
                )
                binding.ivPlayingIndicator.visibility =
                    if (isSelected) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                binding.root.setBackgroundResource(R.drawable.selector_song_item)
                val isPlaying = song.id == currentSongId
                binding.ivPlayingIndicator.visibility =
                    if (isPlaying) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundResource(R.drawable.bg_playing_bar)
                binding.root.isActivated = isPlaying
            }

            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(song.id)
                else onSongClick(song, list)
            }

            binding.root.setOnLongClickListener {
                if (selectionMode) {
                    toggleSelection(song.id)
                    true
                } else if (onLongClick != null) {
                    onLongClick?.invoke(song)
                    true
                } else if (onRemoveClick != null) {
                    setSelectionMode(true)
                    toggleSelection(song.id)
                    true
                } else false
            }
        }
    }

    class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(old: Song, new: Song) = old.id == new.id
        override fun areContentsTheSame(old: Song, new: Song) = old == new
    }
}