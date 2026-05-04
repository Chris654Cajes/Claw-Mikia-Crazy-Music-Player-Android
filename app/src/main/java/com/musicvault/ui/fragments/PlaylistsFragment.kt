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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        val et = EditText(requireContext()).apply { hint = "Playlist name" }
        AlertDialog.Builder(requireContext())
            .setTitle("New Playlist")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim().ifBlank { return@setPositiveButton }
                scope.launch { repo.createPlaylist(name) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPlaylist(playlist: Playlist) {
        PlaylistDetailFragment.newInstance(playlist.id, playlist.name).show(
            childFragmentManager, "playlist_detail"
        )
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Playlist")
            .setMessage("Delete \"${playlist.name}\"? Songs will NOT be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch(Dispatchers.IO) { repo.deletePlaylist(playlist) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class PlaylistAdapter(
        private val onOpen: (Playlist) -> Unit,
        private val onDelete: (Playlist) -> Unit
    ) : ListAdapter<Playlist, PlaylistAdapter.VH>(object : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(a: Playlist, b: Playlist) = a.id == b.id
        override fun areContentsTheSame(a: Playlist, b: Playlist) = a == b
    }) {
        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 18, 24, 18)
                setBackgroundResource(android.R.color.transparent)
            }
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val pl = getItem(pos)
            (holder.itemView as LinearLayout).removeAllViews()
            val tv = TextView(holder.itemView.context).apply {
                text = pl.name; textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#F0F0FF"))
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = TextView(holder.itemView.context).apply {
                text = "✕"; textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#8888AA"))
                setPadding(16, 0, 0, 0)
                setOnClickListener { onDelete(pl) }
            }
            (holder.itemView as LinearLayout).addView(tv)
            (holder.itemView as LinearLayout).addView(del)
            holder.itemView.setOnClickListener { onOpen(pl) }
        }
    }
}

class PlaylistDetailFragment : androidx.fragment.app.DialogFragment() {
    companion object {
        fun newInstance(id: Long, name: String): PlaylistDetailFragment =
            PlaylistDetailFragment().apply {
                arguments = Bundle().apply { putLong("id", id); putString("name", name) }
            }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        TextView(requireContext()).apply {
            text = "Playlist: ${arguments?.getString("name")}"; setPadding(
            32,
            32,
            32,
            32
        ); textSize = 16f
        }
}
