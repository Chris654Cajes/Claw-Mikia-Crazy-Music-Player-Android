package com.mochimochi.clawmikia.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.data.model.PlaybackProfile

class ProfilesAdapter(
    private val onActivate: (PlaybackProfile) -> Unit,
    private val onDelete: (PlaybackProfile) -> Unit,
    private val onClick: (PlaybackProfile) -> Unit
) : ListAdapter<PlaybackProfile, ProfilesAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaybackProfile>() {
            override fun areItemsTheSame(a: PlaybackProfile, b: PlaybackProfile) = a.id == b.id
            override fun areContentsTheSame(a: PlaybackProfile, b: PlaybackProfile) = a == b
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val activeDot: View = itemView.findViewById(R.id.viewActiveDot)
        val tvName: TextView = itemView.findViewById(R.id.tvProfileName)
        val tvPitch: TextView = itemView.findViewById(R.id.tvProfilePitch)
        val tvSpeed: TextView = itemView.findViewById(R.id.tvProfileSpeed)
        val tvEq: TextView = itemView.findViewById(R.id.tvProfileEq)
        val btnActivate: ImageButton = itemView.findViewById(R.id.btnActivateProfile)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteProfile)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)
        holder.tvName.text = profile.name
        holder.activeDot.alpha = if (profile.isActive) 1f else 0.2f

        val pitchStr = when {
            profile.pitchSemitones > 0 -> "+%.1f st".format(profile.pitchSemitones)
            profile.pitchSemitones < 0 -> "%.1f st".format(profile.pitchSemitones)
            else -> "0 st"
        }
        holder.tvPitch.text = pitchStr

        holder.tvSpeed.text = "%.2fx".format(profile.playbackSpeed)

        holder.tvEq.text = if (profile.eqEnabled) "EQ: ${profile.eqPresetName}" else "EQ off"

        holder.btnActivate.setImageResource(
            if (profile.isActive) android.R.drawable.checkbox_on_background
            else android.R.drawable.ic_media_play
        )

        holder.btnActivate.setOnClickListener { onActivate(profile) }
        holder.btnDelete.setOnClickListener { onDelete(profile) }
        holder.itemView.setOnClickListener { onClick(profile) }

        // Highlight active profile
        val cardColor = if (profile.isActive)
            android.graphics.Color.parseColor("#1AFA024D")
        else
            android.graphics.Color.parseColor("#1A1A26")
        (holder.itemView as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(
            cardColor
        )
    }
}
