package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.musicvault.R
import com.musicvault.data.model.Playlist
import com.musicvault.data.model.Song
import com.musicvault.data.repository.PlaylistRepository
import com.musicvault.data.repository.SongRepository
import com.musicvault.ui.activities.MainActivity
import com.musicvault.ui.adapters.SongAdapter
import kotlinx.coroutines.*

class PlaylistsFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repo: PlaylistRepository
    private lateinit var songRepo: SongRepository
    private lateinit var adapter: PlaylistAdapter
    private lateinit var recycler: RecyclerView

    companion object {
        fun newInstance() = PlaylistsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_playlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = PlaylistRepository(requireContext())
        songRepo = SongRepository(requireContext())

        adapter = PlaylistAdapter(
            onOpen = { playlist -> openPlaylist(playlist) },
            onPlay = { playlist -> playPlaylist(playlist) },
            onEdit = { playlist -> editPlaylist(playlist) },
            onDelete = { playlist -> confirmDelete(playlist) }
        )
        recycler = view.findViewById(R.id.rvPlaylists)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabNewPlaylist)?.setOnClickListener {
            showCreatePlaylistDialog()
        }

        repo.allPlaylists.observe(viewLifecycleOwner) { playlists ->
            adapter.submitList(playlists)
        }
    }

    private fun showCreatePlaylistDialog() {
        showAestheticPlaylistDialog(
            title = "New Playlist",
            hint = "Playlist name",
            positiveText = "Create"
        ) { name ->
            scope.launch { repo.createPlaylist(name) }
        }
    }

    private fun openPlaylist(playlist: Playlist) {
        val fragment = PlaylistDetailFragment.newInstance(playlist.id, playlist.name)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun confirmDelete(playlist: Playlist) {
        showAestheticConfirmDialog(
            title = "Delete Playlist",
            message = "Delete \"${playlist.name}\"? Songs will NOT be deleted.",
            positiveText = "Delete"
        ) {
            scope.launch(Dispatchers.IO) { repo.deletePlaylist(playlist) }
        }
    }

    private fun playPlaylist(playlist: Playlist) {
        scope.launch {
            val songs = repo.getSongsInPlaylistSync(playlist.id)
            if (songs.isNotEmpty()) {
                (requireActivity() as? MainActivity)?.playSong(songs[0], songs)
            }
        }
    }

    private fun editPlaylist(playlist: Playlist) {
        showAestheticPlaylistDialog(
            title = getString(R.string.rename_playlist),
            hint = getString(R.string.enter_new_name),
            positiveText = getString(R.string.rename),
            onPositive = { newName ->
                scope.launch {
                    repo.updatePlaylist(playlist.copy(name = newName))
                }
            }
        )
    }

    inner class PlaylistAdapter(
        private val onOpen: (Playlist) -> Unit,
        private val onPlay: (Playlist) -> Unit,
        private val onEdit: (Playlist) -> Unit,
        private val onDelete: (Playlist) -> Unit
    ) : ListAdapter<Playlist, PlaylistAdapter.VH>(object : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(a: Playlist, b: Playlist) = a.id == b.id
        override fun areContentsTheSame(a: Playlist, b: Playlist) = a == b
    }) {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvPlaylistName)
            val info: TextView = v.findViewById(R.id.tvPlaylistInfo)
            val btnPlay: View = v.findViewById(R.id.btnPlayPlaylist)
            val btnEdit: View = v.findViewById(R.id.btnEditPlaylist)
            val btnDelete: View = v.findViewById(R.id.btnDeletePlaylist)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v =
                LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val pl = getItem(pos)
            holder.name.text = pl.name
            holder.info.text = holder.itemView.context.getString(R.string.tap_to_view_tracks)
            holder.btnPlay.setOnClickListener { onPlay(pl) }
            holder.btnEdit.setOnClickListener { onEdit(pl) }
            holder.btnDelete.setOnClickListener { onDelete(pl) }
            holder.itemView.setOnClickListener { onOpen(pl) }
        }
    }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticConfirmDialog(
        title: String,
        message: String,
        positiveText: String = "Confirm",
        onPositive: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.tvTitle).text = title
        dialogView.findViewById<TextView>(R.id.tvMessage).text = message

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnConfirm).setOnClickListener {
            onPositive()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAestheticPlaylistDialog(
        title: String,
        hint: String = "",
        positiveText: String = "Save",
        onPositive: (String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_playlist, null)

        dialogView.findViewById<TextView>(R.id.tvTitle).text = title
        val tilPlaylistName =
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPlaylistName)
        val etPlaylistName =
            dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPlaylistName)

        tilPlaylistName.hint = hint

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnCreate).setOnClickListener {
            val name = etPlaylistName.text.toString().trim()
            if (name.isNotBlank()) {
                onPositive(name)
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}

class PlaylistDetailFragment : Fragment() {
    private lateinit var adapter: SongAdapter
    private lateinit var repo: PlaylistRepository
    private var playlistId: Long = -1
    private var playlistName: String = ""

    companion object {
        fun newInstance(id: Long, name: String): PlaylistDetailFragment =
            PlaylistDetailFragment().apply {
                arguments = Bundle().apply { putLong("id", id); putString("name", name) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = arguments?.getLong("id") ?: -1
        playlistName = arguments?.getString("name") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_playlist_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = PlaylistRepository(requireContext())

        view.findViewById<TextView>(R.id.tvPlaylistName).text = playlistName
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            if (adapter.isSelectionMode()) {
                adapter.setSelectionMode(false)
            } else {
                parentFragmentManager.popBackStack()
            }
        }
        view.findViewById<View>(R.id.fabAddSongs).setOnClickListener {
            showSongSelectionDialog()
        }

        val btnDeleteSelected = view.findViewById<View>(R.id.btnDeleteSelected)
        btnDeleteSelected.setOnClickListener {
            val selectedIds = adapter.getSelectedSongIds()
            if (selectedIds.isNotEmpty()) {
                lifecycleScope.launch {
                    repo.removeSongsFromPlaylist(playlistId, selectedIds)
                    adapter.setSelectionMode(false)
                    btnDeleteSelected.visibility = View.GONE
                }
            }
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvTracks)
        rv.layoutManager = LinearLayoutManager(requireContext())

        adapter = SongAdapter(
            onSongClick = { song, list ->
                (requireActivity() as? MainActivity)?.playSong(song, list)
            },
            onFavoriteClick = { song ->
                (requireActivity() as? MainActivity)?.viewModel?.toggleFavorite(song)
            },
            onRemoveClick = { song ->
                lifecycleScope.launch {
                    repo.removeSongFromPlaylist(playlistId, song.id)
                }
            }
        ).apply {
            onSelectionChanged = { mode, count ->
                btnDeleteSelected.visibility = if (mode && count > 0) View.VISIBLE else View.GONE
            }
        }
        rv.adapter = adapter

        // If selection mode starts, show the delete button
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            // We use long press in Adapter, but we need to sync UI here
        })

        // Actually, let's use a simpler way to sync UI for selection mode.
        // We'll wrap the adapter and observe its state if we had a ViewModel, 
        // but here we'll just check it periodically or add a listener to the adapter.

        repo.getSongsInPlaylist(playlistId).observe(viewLifecycleOwner) { songs ->
            adapter.submitList(songs)
            view.findViewById<View>(R.id.btnPlayAll).setOnClickListener {
                if (songs.isNotEmpty()) {
                    (requireActivity() as? MainActivity)?.playSong(songs[0], songs)
                }
            }
        }
    }

    private fun showSongSelectionDialog() {
        SongSelectionFragment.newInstance(playlistId).show(childFragmentManager, "song_selection")
    }
}

class SongSelectionFragment : androidx.fragment.app.DialogFragment() {
    private lateinit var repo: SongRepository
    private lateinit var playlistRepo: PlaylistRepository
    private var playlistId: Long = -1
    private val selectedSongIds = mutableSetOf<Long>()

    companion object {
        fun newInstance(playlistId: Long) = SongSelectionFragment().apply {
            arguments = Bundle().apply { putLong("playlistId", playlistId) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog)
        playlistId = arguments?.getLong("playlistId") ?: -1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_song_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = SongRepository(requireContext())
        playlistRepo = PlaylistRepository(requireContext())

        view.findViewById<View>(R.id.btnBack).setOnClickListener { dismiss() }
        val btnAdd = view.findViewById<android.widget.Button>(R.id.btnAdd)
        btnAdd.isEnabled = false
        btnAdd.setOnClickListener {
            if (selectedSongIds.isNotEmpty()) {
                lifecycleScope.launch {
                    playlistRepo.addSongsToPlaylist(playlistId, selectedSongIds.toList())
                    dismiss()
                }
            }
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvSongs)
        rv.layoutManager = LinearLayoutManager(requireContext())

        repo.allSongs.observe(viewLifecycleOwner) { allSongs ->
            rv.adapter = object : RecyclerView.Adapter<SongSelectionViewHolder>() {
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = SongSelectionViewHolder(
                    LayoutInflater.from(p.context).inflate(R.layout.item_song_selection, p, false)
                )

                override fun onBindViewHolder(h: SongSelectionViewHolder, p: Int) {
                    val song = allSongs[p]
                    h.title.text = song.title
                    h.artist.text = song.artist
                    h.checkBox.setOnCheckedChangeListener(null)
                    h.checkBox.isChecked = selectedSongIds.contains(song.id)
                    h.checkBox.setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedSongIds.add(song.id) else selectedSongIds.remove(song.id)
                        btnAdd.isEnabled = selectedSongIds.isNotEmpty()
                    }
                    h.itemView.setOnClickListener { h.checkBox.toggle() }
                }

                override fun getItemCount() = allSongs.size
            }
        }
    }

    class SongSelectionViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val checkBox: android.widget.CheckBox = v.findViewById(R.id.cbSelect)
    }
}
