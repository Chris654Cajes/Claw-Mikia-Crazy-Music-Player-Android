package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.musicvault.R
import com.musicvault.data.model.PlaybackProfile
import com.musicvault.ui.adapters.ProfilesAdapter
import com.musicvault.ui.viewmodel.NowPlayingViewModel

class ProfilesFragment : BottomSheetDialogFragment() {

    private val viewModel: NowPlayingViewModel by activityViewModels()
    private lateinit var adapter: ProfilesAdapter
    var onProfileActivated: (() -> Unit)? = null

    companion object {
        fun newInstance(onActivated: () -> Unit = {}): ProfilesFragment =
            ProfilesFragment().also { it.onProfileActivated = onActivated }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_profiles, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProfilesAdapter(
            onActivate = { profile ->
                viewModel.activateProfile(profile)
                onProfileActivated?.invoke()
                dismiss()
            },
            onDelete = { profile -> confirmDelete(profile) },
            onClick = { profile -> showProfileDetails(profile) }
        )

        view.findViewById<RecyclerView>(R.id.rvProfiles).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProfilesFragment.adapter
        }

        view.findViewById<ExtendedFloatingActionButton>(R.id.fabNewProfile)?.setOnClickListener {
            showCreateProfileDialog()
        }

        viewModel.profiles.observe(viewLifecycleOwner) { profiles ->
            adapter.submitList(profiles)
        }
    }

    private fun showCreateProfileDialog() {
        val songId = viewModel.currentSong.value?.id ?: return
        val editText = EditText(requireContext()).apply { hint = "Profile name" }
        AlertDialog.Builder(requireContext())
            .setTitle("New Playback Profile")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                    .ifBlank { "Profile ${System.currentTimeMillis() % 1000}" }
                viewModel.createProfile(songId, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(profile: PlaybackProfile) {
        if (profile.name == "Default") {
            AlertDialog.Builder(requireContext())
                .setMessage("Cannot delete the Default profile.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Profile")
            .setMessage("Delete \"${profile.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteProfile(profile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProfileDetails(profile: PlaybackProfile) {
        val details = buildString {
            appendLine("Pitch: ${if (profile.pitchSemitones >= 0) "+${profile.pitchSemitones}" else "${profile.pitchSemitones}"} semitones")
            appendLine("Speed: ${"%.2f".format(profile.playbackSpeed)}x")
            appendLine("EQ: ${if (profile.eqEnabled) profile.eqPresetName else "Off"}")
            appendLine("Bass Boost: ${if (profile.bassBoostEnabled) profile.bassBoostStrength else "Off"}")
            appendLine("Reverb: ${if (profile.reverbEnabled) "Preset ${profile.reverbPreset}" else "Off"}")
            if (profile.loopEnabled) appendLine("Loop: ${profile.loopStart}ms → ${profile.loopEnd}ms")
            if (profile.abRepeatEnabled) appendLine("A-B: ${profile.abRepeatA}ms → ${profile.abRepeatB}ms")
            if (profile.trimEnd > 0) appendLine("Trim: ${profile.trimStart}ms → ${profile.trimEnd}ms")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }
}
