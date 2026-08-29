package com.mochimochi.clawmikiacrazy.ui.fragments

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.db.FolderInfo
import com.mochimochi.clawmikiacrazy.data.model.Playlist
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.data.repository.PlaylistRepository
import com.mochimochi.clawmikiacrazy.ui.activities.MainActivity
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A browsable accordion section (folder / playlist / album) with its songs. */
data class BrowseSection(
    val title: String,
    val entityLabel: String, // "folder" | "playlist" | "album"
    val iconRes: Int,
    val iconTint: Int,
    val accent: Int,
    val sectionKey: String,
    val songs: List<Song>
)

class BrowseAccordionDialog(
    private val onApplySections: ((List<BrowseSection>) -> Unit)? = null,
    private val resetToOriginal: (() -> Unit)? = null
) : DialogFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private enum class Mode { FOLDERS, PLAYLISTS, ALBUMS }

    private var mode: Mode = Mode.FOLDERS
    private lateinit var repo: PlaylistRepository

    private lateinit var tabFolders: TextView
    private lateinit var tabPlaylists: TextView
    private lateinit var tabAlbums: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var container: LinearLayout
    private lateinit var scroll: View
    private lateinit var cbSelectAll: MaterialCheckBox
    private lateinit var tvSelectedCount: TextView
    private lateinit var selectAllArea: View

    private val expandedRows = mutableSetOf<Int>()
    private var renderGeneration = 0
    private var updatingSelectAll = false

    // Rendering + selection state
    private lateinit var currentSections: List<BrowseSection>
    private lateinit var selectedIndices: MutableSet<Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.MusicVaultDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_browse_accordion, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        repo = PlaylistRepository(requireContext())

        tabFolders = view.findViewById(R.id.tabFolders)
        tabPlaylists = view.findViewById(R.id.tabPlaylists)
        tabAlbums = view.findViewById(R.id.tabAlbums)
        tvHint = view.findViewById(R.id.tvBrowseHint)
        tvEmpty = view.findViewById(R.id.tvBrowseEmpty)
        container = view.findViewById(R.id.accordionContainer)
        scroll = view.findViewById(R.id.browseScroll)
        cbSelectAll = view.findViewById(R.id.cbSelectAll)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        selectAllArea = view.findViewById(R.id.selectAllRow)

        currentSections = emptyList()
        selectedIndices = mutableSetOf()

        view.findViewById<View>(R.id.btnBrowseClose).setOnClickListener { dismiss() }
        tabFolders.setOnClickListener { setMode(Mode.FOLDERS) }
        tabPlaylists.setOnClickListener { setMode(Mode.PLAYLISTS) }
        tabAlbums.setOnClickListener { setMode(Mode.ALBUMS) }

        // Select All toggling
        selectAllArea.setOnClickListener {
            val next = !cbSelectAll.isChecked
            setSelectAllChecked(next)
            applySelectAll(next)
        }
        cbSelectAll.setOnCheckedChangeListener { _, checked ->
            if (updatingSelectAll) return@setOnCheckedChangeListener
            applySelectAll(checked)
        }

        view.findViewById<View>(R.id.btnApplyBrowse).setOnClickListener { applySelection() }
        view.findViewById<View>(R.id.btnClearBrowse).setOnClickListener { clearSelection() }
        view.findViewById<View>(R.id.btnResetBrowse).setOnClickListener {
            resetToOriginal?.invoke()
            dismiss()
        }

        // Keep UI in sync with repository changes
        viewModel.folders.observe(viewLifecycleOwner) { if (mode == Mode.FOLDERS) rerender() }
        viewModel.allPlaylists.observe(viewLifecycleOwner) { if (mode == Mode.PLAYLISTS) rerender() }
        viewModel.allSongs.observe(viewLifecycleOwner) { if (mode == Mode.ALBUMS) rerender() }

        setMode(Mode.FOLDERS)
    }

    // ─── Selection helpers ─────────────────────────────────────────────────────────

    private fun applySelectAll(checked: Boolean) {
        selectedIndices.clear()
        if (checked) {
            currentSections.indices.forEach { selectedIndices.add(it) }
        }
        rerender()
    }

    private fun setSelectAllChecked(checked: Boolean) {
        updatingSelectAll = true
        cbSelectAll.isChecked = checked
        updatingSelectAll = false
    }

    private fun clearSelection() {
        selectedIndices.clear()
        setSelectAllChecked(false)
        rerender()
    }

    private fun applySelection() {
        val selected = currentSections.filterIndexed { index, _ -> index in selectedIndices }
        if (selected.isEmpty()) {
            dismiss()
            return
        }
        if (mode == Mode.PLAYLISTS) {
            // Ensure playlist songs are loaded before handing sections over
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    selected.map { section ->
                        section to repo.getSongsInPlaylistSync(playlistIdFromKey(section.sectionKey))
                    }
                }
                if (!isAdded) return@launch
                val sections = loaded.map { (s, songs) ->
                    BrowseSection(
                        s.title, s.entityLabel, s.iconRes, s.iconTint, s.accent, s.sectionKey, songs
                    )
                }
                onApplySections?.invoke(sections)
                dismiss()
            }
        } else {
            onApplySections?.invoke(selected)
            dismiss()
        }
    }

    private fun toggleSelection(index: Int) {
        if (index in selectedIndices) selectedIndices.remove(index) else selectedIndices.add(index)
        updateSelectAllUi()
        rerender()
    }

    private fun updateSelectAllUi() {
        val total = currentSections.size
        val selectedCount = selectedIndices.size
        tvSelectedCount.text = if (total == 0) "" else "$selectedCount/$total"
        setSelectAllChecked(total > 0 && selectedCount == total)
    }

    // ─── Mode switching ──────────────────────────────────────────────────────────

    private fun setMode(newMode: Mode) {
        mode = newMode
        expandedRows.clear()
        selectedIndices.clear()

        tabFolders.isSelected = newMode == Mode.FOLDERS
        tabPlaylists.isSelected = newMode == Mode.PLAYLISTS
        tabAlbums.isSelected = newMode == Mode.ALBUMS

        tabFolders.setTextColor(
            getColor(if (newMode == Mode.FOLDERS) R.color.neon_yellow else R.color.text_primary)
        )
        tabPlaylists.setTextColor(
            getColor(if (newMode == Mode.PLAYLISTS) R.color.neon_pink else R.color.text_primary)
        )
        tabAlbums.setTextColor(
            getColor(if (newMode == Mode.ALBUMS) R.color.neon_cyan else R.color.text_primary)
        )

        cbSelectAll.buttonTintList = android.content.res.ColorStateList.valueOf(
            modeAccent(newMode)
        )
        setSelectAllChecked(false)
        tvSelectedCount.text = ""

        tvHint.text = when (newMode) {
            Mode.FOLDERS -> getString(R.string.browse_hint_folders)
            Mode.PLAYLISTS -> getString(R.string.browse_hint_playlists)
            Mode.ALBUMS -> getString(R.string.browse_hint_albums)
        }

        tvEmpty.visibility = View.GONE
        scroll.scrollTo(0, 0)
        rerender()
    }

    private fun modeAccent(m: Mode): Int = when (m) {
        Mode.FOLDERS -> getColor(R.color.neon_yellow)
        Mode.PLAYLISTS -> getColor(R.color.neon_pink)
        Mode.ALBUMS -> getColor(R.color.neon_cyan)
    }

    // ─── Rendering ───────────────────────────────────────────────────────────────

    private fun rerender() {
        renderGeneration++
        val generation = renderGeneration

        val sections = buildSections()
        currentSections = sections
        selectedIndices.retainAll(sections.indices.toList())
        expandedRows.retainAll(sections.indices.toList())

        container.removeAllViews()
        tvEmpty.visibility = View.GONE

        if (sections.isEmpty()) {
            val emptyRes = when (mode) {
                Mode.FOLDERS -> R.string.browse_empty_folders
                Mode.PLAYLISTS -> R.string.browse_empty_playlists
                Mode.ALBUMS -> R.string.browse_empty_albums
            }
            tvEmpty.text = getString(emptyRes)
            tvEmpty.visibility = View.VISIBLE
            updateSelectAllUi()
            return
        }

        if (mode == Mode.PLAYLISTS) {
            renderPlaylistsAsync(generation)
        } else {
            sections.forEachIndexed { index, section ->
                addAccordionRow(generation, index, section)
            }
            updateSelectAllUi()
        }
    }

    private fun buildSections(): List<BrowseSection> = when (mode) {
        Mode.FOLDERS -> folderSections()
        Mode.PLAYLISTS -> playlistSections()
        Mode.ALBUMS -> albumSections()
    }

    private fun folderSections(): List<BrowseSection> {
        return viewModel.folders.value.orEmpty().map { folder ->
            BrowseSection(
                title = folder.folderName,
                entityLabel = "folder",
                iconRes = R.drawable.ic_folder,
                iconTint = getColor(R.color.neon_yellow),
                accent = getColor(R.color.neon_yellow),
                sectionKey = "folder:${folder.folderPath}",
                songs = viewModel.allSongs.value.orEmpty()
                    .filter { it.folderPath == folder.folderPath }
            )
        }
    }

    private fun playlistSections(): List<BrowseSection> {
        return viewModel.allPlaylists.value.orEmpty().map { playlist ->
            BrowseSection(
                title = playlist.name,
                entityLabel = "playlist",
                iconRes = R.drawable.ic_playlist,
                iconTint = getColor(R.color.neon_pink),
                accent = getColor(R.color.neon_pink),
                sectionKey = "playlist:${playlist.id}",
                songs = emptyList() // loaded async
            )
        }
    }

    private fun albumSections(): List<BrowseSection> {
        return viewModel.allSongs.value.orEmpty()
            .groupBy { it.albumName.ifBlank { "Unknown Album" } }
            .map { (name, songs) ->
                BrowseSection(
                    title = name,
                    entityLabel = "album",
                    iconRes = R.drawable.ic_music_note_outline,
                    iconTint = getColor(R.color.neon_cyan),
                    accent = getColor(R.color.neon_cyan),
                    sectionKey = "album:$name",
                    songs = songs
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    private fun playlistIdFromKey(key: String): Long =
        key.removePrefix("playlist:").toLongOrNull() ?: -1L

    private fun renderPlaylistsAsync(generation: Int) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                currentSections.map { section ->
                    section to repo.getSongsInPlaylistSync(playlistIdFromKey(section.sectionKey))
                }
            }
            if (generation != renderGeneration) return@launch
            currentSections = loaded.map { (s, songs) ->
                BrowseSection(
                    s.title, s.entityLabel, s.iconRes, s.iconTint, s.accent, s.sectionKey, songs
                )
            }
            selectedIndices.retainAll(currentSections.indices.toList())
            expandedRows.retainAll(currentSections.indices.toList())
            container.removeAllViews()
            currentSections.forEachIndexed { index, section ->
                addAccordionRow(generation, index, section)
            }
            updateSelectAllUi()
        }
    }

    private fun addAccordionRow(
        generation: Int,
        index: Int,
        section: BrowseSection
    ) {
        if (generation != renderGeneration) return
        val selected = index in selectedIndices

        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = bg(R.drawable.bg_accordion_header)
        }

        val cb = MaterialCheckBox(requireContext()).apply {
            isChecked = selected
            buttonTintList = android.content.res.ColorStateList.valueOf(section.accent)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            setOnClickListener { toggleSelection(index) }
        }

        val ivIcon = ImageView(requireContext()).apply {
            setImageResource(section.iconRes)
            setColorFilter(section.iconTint)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                .apply { marginStart = dp(8) }
        }

        val textCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }

        val tvTitle = TextView(requireContext()).apply {
            text = section.title
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            maxLines = 1
        }

        val displayLabel = if (section.entityLabel.endsWith("y")) {
            section.entityLabel.dropLast(1) + "ies"
        } else section.entityLabel + "s"
        val tvSub = TextView(requireContext()).apply {
            text = "${section.songs.size} $displayLabel"
            setTextColor(getColor(R.color.text_hint))
            textSize = 10f
        }

        val chevron = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(section.accent)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                .apply {
                    marginStart = dp(8)
                    marginEnd = dp(2)
                }
            if (section.songs.isEmpty()) alpha = 0.3f
        }

        textCol.addView(tvTitle)
        textCol.addView(tvSub)
        header.addView(cb)
        header.addView(ivIcon)
        header.addView(textCol)
        header.addView(chevron)

        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(R.drawable.bg_accordion_body)
            visibility = View.GONE
        }

        if (section.songs.isEmpty()) {
            body.addView(TextView(requireContext()).apply {
                text = "No songs"
                setTextColor(getColor(R.color.text_hint))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
            })
        } else {
            section.songs.forEach { song ->
                body.addView(makeSongRow(song, section.accent, section.songs))
            }
        }

        var expanded = index in expandedRows
        header.setOnClickListener {
            if (expanded) {
                collapse(body, chevron)
                expandedRows.remove(index)
                expanded = false
            } else {
                expand(body, chevron)
                expandedRows.add(index)
                expanded = true
            }
        }

        wrapper.addView(header)
        wrapper.addView(body)
        container.addView(wrapper)

        if (index in expandedRows) {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            body.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, body.measuredHeight
            )
            body.visibility = View.VISIBLE
            chevron.rotation = 180f
        }
    }

    private fun makeSongRow(song: Song, accent: Int, queue: List<Song>): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { playSong(song, queue) }
        }

        val bullet = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(12) }
            background = bg(R.drawable.bg_indicator)
            setBackgroundColor(accent)
        }

        val tvTitle = TextView(requireContext()).apply {
            text = song.title
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvArtist = TextView(requireContext()).apply {
            text = song.artist
            setTextColor(getColor(R.color.text_hint))
            textSize = 11f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

        row.addView(bullet)
        row.addView(tvTitle)
        row.addView(tvArtist)
        return row
    }

    private fun playSong(song: Song, queue: List<Song>) {
        (activity as? MainActivity)?.playSong(song, queue)
    }

    // ─── Sliding expand / collapse ──────────────────────────────────────────────

    private fun expand(body: View, chevron: ImageView) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            container.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST
        )
        body.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val targetHeight = body.measuredHeight

        body.visibility = View.VISIBLE
        body.alpha = 0f
        body.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0
        )

        ValueAnimator.ofInt(0, targetHeight).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                body.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h
                )
                body.alpha = if (targetHeight == 0) 0f else h / targetHeight.toFloat()
                body.requestLayout()
            }
            start()
        }
        chevron.animate().rotation(180f).setDuration(260).start()
    }

    private fun collapse(body: View, chevron: ImageView) {
        val startHeight = body.height.coerceAtLeast(1)
        ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                body.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h
                )
                body.alpha = h / startHeight.toFloat()
                body.requestLayout()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {
                    body.visibility = View.GONE
                    resetLayout(body)
                }
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    body.visibility = View.GONE
                    resetLayout(body)
                }
            })
            start()
        }
        chevron.animate().rotation(0f).setDuration(260).start()
    }

    private fun resetLayout(body: View) {
        body.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun getColor(res: Int): Int =
        androidx.core.content.ContextCompat.getColor(requireContext(), res)

    private fun bg(res: Int): android.graphics.drawable.Drawable? =
        androidx.core.content.ContextCompat.getDrawable(requireContext(), res)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
