package com.musicvault.ui.fragments

import android.os.Bundle
import android.view.*
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
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
            onClick = { profile -> showProfileDetails(profile) },
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
        showAestheticInputDialog(
            title = "New Playback Profile",
            hint = "Profile name"
        ) { name ->
            val profileName = name.ifBlank { "Profile ${System.currentTimeMillis() % 1000}" }
            viewModel.createProfile(songId, profileName)
        }
    }

    private fun confirmDelete(profile: PlaybackProfile) {
        if (profile.name == "Default") {
            showAestheticConfirmDialog(
                title = "Cannot Delete",
                message = "Cannot delete the Default profile."
            ) { }
            return
        }
        showAestheticConfirmDialog(
            title = "Delete Profile",
            message = "Delete \"${profile.name}\"? This cannot be undone."
        ) {
            viewModel.deleteProfile(profile)
        }
    }

    private fun showProfileDetails(profile: PlaybackProfile) {
        val details = buildString {
            appendLine("Pitch: ${if (profile.pitchSemitones >= 0) "+${profile.pitchSemitones}" else profile.pitchSemitones} semitones")
            appendLine("Speed: ${"%.2f".format(profile.playbackSpeed)}x")
            appendLine("EQ: ${if (profile.eqEnabled) profile.eqPresetName else "Off"}")
            appendLine("Bass Boost: ${if (profile.bassBoostEnabled) profile.bassBoostStrength else "Off"}")
            appendLine("Reverb: ${if (profile.reverbEnabled) "Preset ${profile.reverbPreset}" else "Off"}")
            if (profile.loopEnabled) appendLine("Loop: ${profile.loopStart}ms → ${profile.loopEnd}ms")
            if (profile.abRepeatEnabled) appendLine("A-B: ${profile.abRepeatA}ms → ${profile.abRepeatB}ms")
            if (profile.trimEnd > 0) appendLine("Trim: ${profile.trimStart}ms → ${profile.trimEnd}ms")
        }
        showAestheticConfirmDialog(
            title = profile.name,
            message = details
        ) { }
    }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticConfirmDialog(
        title: String,
        message: String,
        onPositive: () -> Unit,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<android.widget.TextView>(R.id.tvTitle).text = title
        dialogView.findViewById<android.widget.TextView>(R.id.tvMessage).text = message

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

    private fun showAestheticInputDialog(
        title: String,
        hint: String = "",
        onPositive: (String) -> Unit,
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_generic, null)

        dialogView.findViewById<android.widget.TextView>(R.id.tvTitle).text = title
        val tilInput =
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilInput)
        val etInput =
            dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etInput)
        val btnNegative = dialogView.findViewById<android.widget.ImageButton>(R.id.btnNegative)

        tilInput.visibility = View.VISIBLE
        tilInput.hint = hint
        btnNegative.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnNegative.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.ImageButton>(R.id.btnPositive).setOnClickListener {
            onPositive(etInput.text.toString())
            dialog.dismiss()
        }

        dialog.show()
    }
}
