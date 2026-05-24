package com.musicvault.ui.adapters

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicvault.data.model.LyricLine

class LyricsAdapter : ListAdapter<LyricLine, LyricsAdapter.ViewHolder>(DIFF) {

    var activeLine: LyricLine? = null
        set(value) {
            val old = field
            field = value
            old?.let { notifyItemChanged(currentList.indexOfFirst { it.id == old.id }) }
            value?.let { notifyItemChanged(currentList.indexOfFirst { it.id == value.id }) }
        }

    var karaokeMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<LyricLine>() {
            override fun areItemsTheSame(a: LyricLine, b: LyricLine) = a.id == b.id
            override fun areContentsTheSame(a: LyricLine, b: LyricLine) = a == b
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLine: TextView = itemView as TextView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val textView = TextView(parent.context)
        textView.layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 4, 0, 4) }
        textView.setPadding(24, 10, 24, 10)
        textView.textSize = 16f
        textView.gravity = android.view.Gravity.CENTER
        textView.setTextColor("#8888AA".toColorInt())
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position)
        holder.tvLine.text = line.text

        val isActive = line.id == (activeLine?.id ?: -1L)

        if (karaokeMode) {
            holder.tvLine.textSize = if (isActive) 20f else 15f
            holder.tvLine.setTextColor(
                if (isActive) "#FA024D".toColorInt() // neon pink highlight
                else "#44445A".toColorInt()
            )
            holder.tvLine.alpha = if (isActive) 1f else 0.5f
        } else {
            holder.tvLine.textSize = if (isActive) 18f else 15f
            holder.tvLine.setTextColor(
                if (isActive) "#F0F0FF".toColorInt()
                else "#8888AA".toColorInt()
            )
            holder.tvLine.alpha = if (isActive) 1f else 0.75f
        }

        // Subtle scale animation for active line
        if (isActive) {
            val scaleXAnim = ObjectAnimator.ofFloat(holder.tvLine, "scaleX", 1f, 1.04f, 1f)
            val scaleYAnim = ObjectAnimator.ofFloat(holder.tvLine, "scaleY", 1f, 1.04f, 1f)
            AnimatorSet().apply {
                playTogether(scaleXAnim, scaleYAnim)
                duration = 250
                start()
            }
        }
    }
}
