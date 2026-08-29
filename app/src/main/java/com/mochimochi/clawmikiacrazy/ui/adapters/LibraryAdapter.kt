package com.mochimochi.clawmikiacrazy.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.databinding.ItemAlbumHeaderBinding
import com.mochimochi.clawmikiacrazy.databinding.ItemSongGridBinding
import com.mochimochi.clawmikiacrazy.databinding.ItemSongBinding
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration

class LibraryAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onFavoriteClick: (Song) -> Unit,
    var onLongClick: ((Song) -> Unit)? = null,
    var onSelectionChanged: ((Boolean, Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_ALBUM_HEADER = 0
        const val TYPE_SONG_LIST = 1
        const val TYPE_SONG_GRID = 2
    }

    var isGridMode = false
        set(value) {
            if (field != value) {
                field = value; notifyDataSetChanged()
            }
        }

    var isGroupByAlbum = false
        set(value) {
            if (field != value) {
                field = value; rebuildFlatList()
            }
        }

    var favoriteIconType: String = "heart"
        set(value) {
            if (field != value) {
                field = value; notifyDataSetChanged()
            }
        }

    private var currentSongId: Long? = null
    private val selectedSongs = mutableSetOf<Long>()
    private var selectionMode = false
    private var rawSongs: List<Song> = emptyList()
    private val expandedAlbums = mutableSetOf<String>()
    private var flatList: List<DisplayItem> = emptyList()

    internal sealed class DisplayItem {
        data class AlbumHeader(
            val albumName: String,
            val albumArtUrl: String,
            val songCount: Int,
            val isExpanded: Boolean
        ) : DisplayItem()

        data class SongItem(val song: Song) : DisplayItem()
    }

    fun submitSongs(songs: List<Song>) {
        rawSongs = songs; rebuildFlatList()
    }

    fun setCurrentSong(id: Long?) {
        val old = currentSongId; currentSongId = id
        flatList.forEachIndexed { index, item ->
            if (item is DisplayItem.SongItem && (item.song.id == old || item.song.id == id)) notifyItemChanged(
                index
            )
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
        if (selectedSongs.contains(songId)) selectedSongs.remove(songId) else selectedSongs.add(
            songId
        )
        flatList.forEachIndexed { index, item ->
            if (item is DisplayItem.SongItem && item.song.id == songId) notifyItemChanged(index)
        }
        onSelectionChanged?.invoke(selectionMode, selectedSongs.size)
    }

    fun getSelectedSongIds(): List<Long> = selectedSongs.toList()

    fun selectAll() {
        if (!selectionMode) setSelectionMode(true)
        flatList.forEach { if (it is DisplayItem.SongItem) selectedSongs.add(it.song.id) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectionMode, selectedSongs.size)
    }

    fun getCurrentSongList(): List<Song> = rawSongs

    fun getSongAtPosition(position: Int): Song? =
        (flatList.getOrNull(position) as? DisplayItem.SongItem)?.song

    fun getPositionForSong(songId: Long): Int {
        return flatList.indexOfFirst { it is DisplayItem.SongItem && it.song.id == songId }
    }

    private fun rebuildFlatList() {
        if (!isGroupByAlbum) {
            flatList = rawSongs.map { DisplayItem.SongItem(it) }
            notifyDataSetChanged()
            return
        }
        val grouped = rawSongs.groupBy { it.albumName.ifBlank { "Unknown Album" } }
            .toSortedMap(compareByDescending<String> { it }.thenBy { it })

        val newList = mutableListOf<DisplayItem>()
        for ((album, songs) in grouped) {
            val artUrl = songs.firstOrNull { it.albumArtUrl.isNotBlank() }?.albumArtUrl ?: ""
            val expanded = expandedAlbums.contains(album)
            newList.add(DisplayItem.AlbumHeader(album, artUrl, songs.size, expanded))
            if (expanded) songs.forEach { newList.add(DisplayItem.SongItem(it)) }
        }
        flatList = newList
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (flatList[position]) {
        is DisplayItem.AlbumHeader -> TYPE_ALBUM_HEADER
        is DisplayItem.SongItem -> if (isGridMode) TYPE_SONG_GRID else TYPE_SONG_LIST
    }

    override fun getItemCount(): Int = flatList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ALBUM_HEADER -> AlbumHeaderViewHolder(
                ItemAlbumHeaderBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            TYPE_SONG_GRID -> SongGridViewHolder(
                ItemSongGridBinding.inflate(
                    inflater,
                    parent,
                    false
                )
            )

            else -> SongListViewHolder(ItemSongBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = flatList[position]) {
            is DisplayItem.AlbumHeader -> (holder as AlbumHeaderViewHolder).bind(item)
            is DisplayItem.SongItem -> when (holder) {
                is SongListViewHolder -> holder.bind(item.song)
                is SongGridViewHolder -> holder.bind(item.song)
            }
        }
    }

    inner class AlbumHeaderViewHolder(private val binding: ItemAlbumHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        internal fun bind(header: DisplayItem.AlbumHeader) {
            binding.tvAlbumName.text = header.albumName
            binding.tvSongCount.text = "${header.songCount} songs"

            if (header.albumArtUrl.isNotBlank()) {
                binding.ivAlbumArt.visibility = View.VISIBLE
                binding.ivNoteIcon.visibility = View.GONE
                Glide.with(binding.root.context)
                    .load(header.albumArtUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivAlbumArt)
            } else {
                binding.ivAlbumArt.visibility = View.GONE
                binding.ivNoteIcon.visibility = View.VISIBLE
            }

            val targetRotation = if (header.isExpanded) 90f else 0f
            binding.ivChevron.animate()
                .rotation(targetRotation)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            binding.root.setOnClickListener {
                val album = header.albumName
                if (expandedAlbums.contains(album)) expandedAlbums.remove(album) else expandedAlbums.add(
                    album
                )
                rebuildFlatList()
            }
        }
    }

    inner class SongListViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text =
                if (song.albumName.isNotBlank()) "${song.artist} \u2022 ${song.albumName}" else song.artist
            binding.tvDuration.text = formatDuration(song.duration)
            binding.tvFolder.text = song.folderName

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

            binding.btnFavorite.setImageResource(
                if (song.isFavorite) FavoriteIconHelper.filledRes(favoriteIconType)
                else FavoriteIconHelper.outlineRes(favoriteIconType)
            )
            binding.btnFavorite.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    binding.root.context,
                    FavoriteIconHelper.colorRes(favoriteIconType)
                )
            )
            binding.btnFavorite.setOnClickListener { onFavoriteClick(song) }

            if (selectionMode) {
                val isSelected = selectedSongs.contains(song.id)
                binding.root.setBackgroundColor(if (isSelected) android.graphics.Color.parseColor("#3300E5FF") else android.graphics.Color.TRANSPARENT)
                binding.ivPlayingIndicator.visibility =
                    if (isSelected) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                binding.root.background = null
                val isPlaying = song.id == currentSongId
                binding.ivPlayingIndicator.visibility =
                    if (isPlaying) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundResource(R.drawable.bg_playing_bar)
                binding.viewForeground.isActivated = isPlaying
            }

            binding.root.setOnClickListener {
                if (selectionMode) toggleSelection(song.id) else onSongClick(song, rawSongs)
            }
            binding.root.setOnLongClickListener {
                when {
                    selectionMode -> {
                        toggleSelection(song.id); true
                    }

                    onLongClick != null -> {
                        onLongClick?.invoke(song); true
                    }

                    else -> false
                }
            }
        }
    }

    inner class SongGridViewHolder(private val binding: ItemSongGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = formatDuration(song.duration)

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

            binding.btnFavorite.setImageResource(
                if (song.isFavorite) FavoriteIconHelper.filledRes(favoriteIconType)
                else FavoriteIconHelper.outlineRes(favoriteIconType)
            )
            binding.btnFavorite.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    binding.root.context,
                    FavoriteIconHelper.colorRes(favoriteIconType)
                )
            )
            binding.btnFavorite.setOnClickListener { onFavoriteClick(song) }

            if (selectionMode) {
                val isSelected = selectedSongs.contains(song.id)
                binding.viewForeground.isSelected = isSelected
                binding.ivPlayingIndicator.visibility =
                    if (isSelected) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            } else {
                val isPlaying = song.id == currentSongId
                binding.viewForeground.isSelected = false
                binding.viewForeground.isActivated = isPlaying
                binding.ivPlayingIndicator.visibility =
                    if (isPlaying) View.VISIBLE else View.INVISIBLE
                binding.ivPlayingIndicator.setBackgroundResource(R.drawable.bg_playing_bar)
            }

            binding.viewForeground.setOnClickListener {
                if (selectionMode) toggleSelection(song.id) else onSongClick(song, rawSongs)
            }
            binding.viewForeground.setOnLongClickListener {
                when {
                    selectionMode -> {
                        toggleSelection(song.id); true
                    }

                    onLongClick != null -> {
                        onLongClick?.invoke(song); true
                    }

                    else -> false
                }
            }
        }
    }
}
