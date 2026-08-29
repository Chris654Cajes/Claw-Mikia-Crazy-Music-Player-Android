package com.mochimochi.clawmikiacrazy.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.databinding.ItemAccordionHeaderBinding
import com.mochimochi.clawmikiacrazy.databinding.ItemSongBinding
import com.mochimochi.clawmikiacrazy.databinding.ItemSongGridBinding
import com.mochimochi.clawmikiacrazy.ui.fragments.BrowseSection
import com.mochimochi.clawmikiacrazy.utils.FavoriteIconHelper
import com.mochimochi.clawmikiacrazy.utils.formatDuration

/**
 * Renders a set of [BrowseSection]s (folders / playlists / albums) as an expandable
 * accordion inside the Library recyclerview. Tapping a header expands/collapses its
 * song rows with RecyclerView's sliding item animations.
 */
class LibraryAccordionAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onFavoriteClick: (Song) -> Unit,
    var onLongClick: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SONG_LIST = 1
        const val TYPE_SONG_GRID = 2
    }

    var gridMode: Boolean = false
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    var favoriteIconType: String = "heart"
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    private var currentSongId: Long? = null
    private val sections = mutableListOf<BrowseSection>()
    private val expanded = mutableSetOf<Int>()
    private var flatItems: MutableList<Any> = mutableListOf() // Int(header idx) or Song

    fun submitSections(list: List<BrowseSection>) {
        sections.clear()
        sections.addAll(list)
        expanded.clear() // start every newly applied selection fully collapsed
        rebuildFlat()
        notifyDataSetChanged()
    }

    /**
     * Updates the section song lists in place (e.g. when a search query changes)
     * while preserving which sections are currently expanded.
     */
    fun refreshSections(list: List<BrowseSection>) {
        val old = flatItems.toList()
        sections.clear()
        sections.addAll(list)
        expanded.retainAll(sections.indices.toList())
        rebuildFlat()
        dispatchDiff(old)
    }

    private fun dispatchDiff(old: List<Any>) {
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = flatItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]; val b = flatItems[newPos]
                    return when {
                        a is Int && b is Int -> a == b
                        a is Song && b is Song -> a.id == b.id
                        else -> false
                    }
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos] == flatItems[newPos]
            }
        )
        diff.dispatchUpdatesTo(this)
    }

    fun setCurrentSong(id: Long?) {
        val old = currentSongId; currentSongId = id
        flatItems.forEachIndexed { index, item ->
            if (item is Song && (item.id == old || item.id == id)) notifyItemChanged(index)
        }
    }

    fun isShowingSections() = sections.isNotEmpty()

    private fun rebuildFlat() {
        flatItems = mutableListOf()
        sections.forEachIndexed { index, section ->
            flatItems.add(index)
            if (index in expanded) flatItems.addAll(section.songs)
        }
    }

    override fun getItemCount(): Int = flatItems.size

    override fun getItemViewType(position: Int): Int = when (flatItems[position]) {
        is Int -> TYPE_HEADER
        else -> if (gridMode) TYPE_SONG_GRID else TYPE_SONG_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemAccordionHeaderBinding.inflate(inflater, parent, false))
            TYPE_SONG_GRID -> SongGridViewHolder(ItemSongGridBinding.inflate(inflater, parent, false))
            else -> SongViewHolder(ItemSongBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val sectionIndex = flatIndexAt(position)
        when (val item = flatItems[position]) {
            is Int -> (holder as HeaderViewHolder).bind(item)
            is Song -> when (holder) {
                is SongGridViewHolder -> holder.bind(item, sectionIndex)
                else -> (holder as SongViewHolder).bind(item, sectionIndex)
            }
        }
    }

    /** Given a flat-list position (header or song), return the owning section index. */
    private fun flatIndexAt(target: Int): Int {
        var pos = 0
        sections.forEachIndexed { index, section ->
            if (pos == target) return index // header
            pos++ // consume header
            if (index in expanded) {
                val end = pos + section.songs.size
                if (target < end) return index
                pos = end
            }
        }
        return 0
    }

    private fun expandHeader(index: Int) {
        val section = sections.getOrNull(index) ?: return
        if (section.songs.isEmpty()) return
        val headerFlatPos = flatItems.indexOfFirst { it is Int && it == index }
        if (headerFlatPos < 0) return
        expanded.add(index)
        val insertionStart = headerFlatPos + 1
        flatItems.addAll(insertionStart, section.songs.map { it as Any })
        notifyItemRangeInserted(insertionStart, section.songs.size)
    }

    private fun collapseHeader(index: Int) {
        val section = sections.getOrNull(index) ?: return
        val headerFlatPos = flatItems.indexOfFirst { it is Int && it == index }
        if (headerFlatPos < 0) return
        expanded.remove(index)
        val removalStart = headerFlatPos + 1
        notifyItemRangeRemoved(removalStart, section.songs.size)
        repeat(section.songs.size) { flatItems.removeAt(removalStart) }
    }

    inner class HeaderViewHolder(private val binding: ItemAccordionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(index: Int) {
            val section = sections[index]
            binding.tvSectionTitle.text = section.title
            val label = if (section.entityLabel.endsWith("y")) {
                section.entityLabel.dropLast(1) + "ies"
            } else section.entityLabel + "s"
            binding.tvSectionCount.text = "${section.songs.size} $label"
            binding.ivSectionIcon.setImageResource(section.iconRes)
            binding.ivSectionIcon.setColorFilter(section.iconTint)
            binding.ivChevron.setColorFilter(section.accent)

            val isExpanded = index in expanded
            binding.ivChevron.animate()
                .rotation(if (isExpanded) 180f else 0f)
                .setDuration(220)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            binding.root.setOnClickListener {
                if (index in expanded) collapseHeader(index) else expandHeader(index)
            }
        }
    }

    inner class SongViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, sectionIndex: Int) {
            val section = sections[sectionIndex]
            binding.tvTitle.text = song.title
            binding.tvArtist.text =
                if (song.albumName.isNotBlank()) "${song.artist} \u2022 ${song.albumName}" else song.artist
            binding.tvDuration.text = formatDuration(song.duration)
            binding.tvFolder.text = song.folderName

            if (song.albumArtUrl.isNotBlank()) {
                binding.ivAlbumArt.visibility = android.view.View.VISIBLE
                binding.ivNoteIcon.visibility = android.view.View.GONE
                Glide.with(binding.root.context)
                    .load(song.albumArtUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivAlbumArt)
            } else {
                binding.ivAlbumArt.visibility = android.view.View.GONE
                binding.ivNoteIcon.visibility = android.view.View.VISIBLE
                Glide.with(binding.root.context).clear(binding.ivAlbumArt)
            }

            binding.tvPitchBadge.visibility =
                if (song.pitchSemitones != 0f) android.view.View.VISIBLE else android.view.View.GONE
            if (song.pitchSemitones != 0f) {
                binding.tvPitchBadge.text =
                    if (song.pitchSemitones > 0) "+%.1f".format(song.pitchSemitones)
                    else "%.1f".format(song.pitchSemitones)
            }
            binding.ivTrimBadge.visibility =
                if (song.trimStart > 0 || (song.trimEnd > 0 && song.trimEnd < song.duration))
                    android.view.View.VISIBLE else android.view.View.GONE
            binding.ivSpeedBadge.visibility =
                if (song.playbackSpeed != 1.0f) android.view.View.VISIBLE else android.view.View.GONE
            binding.ivManualBadge.visibility =
                if (song.isManuallyEdited) android.view.View.VISIBLE else android.view.View.GONE

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

            binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val isPlaying = song.id == currentSongId
            binding.ivPlayingIndicator.visibility =
                if (isPlaying) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.ivPlayingIndicator.setBackgroundResource(R.drawable.bg_playing_bar)
            binding.viewForeground.isActivated = isPlaying

            binding.root.setOnClickListener {
                onSongClick(song, section.songs)
            }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(song)
                true
            }
        }
    }

    inner class SongGridViewHolder(private val binding: ItemSongGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, sectionIndex: Int) {
            val section = sections[sectionIndex]
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvDuration.text = formatDuration(song.duration)

            if (song.albumArtUrl.isNotBlank()) {
                binding.ivAlbumArt.visibility = android.view.View.VISIBLE
                binding.ivNoteIcon.visibility = android.view.View.GONE
                Glide.with(binding.root.context)
                    .load(song.albumArtUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.ivAlbumArt)
            } else {
                binding.ivAlbumArt.visibility = android.view.View.GONE
                binding.ivNoteIcon.visibility = android.view.View.VISIBLE
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

            val isPlaying = song.id == currentSongId
            binding.viewForeground.isActivated = isPlaying
            binding.ivPlayingIndicator.visibility =
                if (isPlaying) android.view.View.VISIBLE else android.view.View.INVISIBLE
            binding.ivPlayingIndicator.setBackgroundResource(R.drawable.bg_playing_bar)

            binding.viewForeground.setOnClickListener {
                onSongClick(song, section.songs)
            }
            binding.viewForeground.setOnLongClickListener {
                onLongClick?.invoke(song)
                true
            }
        }
    }
}

