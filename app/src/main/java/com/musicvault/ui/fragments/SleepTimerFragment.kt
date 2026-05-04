package com.musicvault.ui.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.musicvault.R

class SleepTimerFragment : BottomSheetDialogFragment() {

    var onSetTimer: ((Long) -> Unit)? = null
    var onCancelTimer: (() -> Unit)? = null
    var getRemainingMs: (() -> Long)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var tvStatus: TextView? = null

    companion object {
        private val PRESETS = listOf(
            5L * 60_000L to "5 min",
            10L * 60_000L to "10 min",
            15L * 60_000L to "15 min",
            20L * 60_000L to "20 min",
            30L * 60_000L to "30 min",
            45L * 60_000L to "45 min",
            60L * 60_000L to "1 hour",
            90L * 60_000L to "1.5 hours"
        )

        fun newInstance(
            onSet: (Long) -> Unit,
            onCancel: () -> Unit,
            getRemaining: () -> Long
        ): SleepTimerFragment = SleepTimerFragment().also {
            it.onSetTimer = onSet
            it.onCancelTimer = onCancel
            it.getRemainingMs = getRemaining
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)

            addView(TextView(context).apply {
                text = "SLEEP TIMER"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#FA024D"))
                letterSpacing = 0.15f
                setPadding(0, 0, 0, 16)
            })

            tvStatus = TextView(context).apply {
                text = "Timer not set"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8888AA"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }
            addView(tvStatus)

            // Preset grid
            val grid = GridLayout(context).apply {
                columnCount = 2
                setPadding(0, 0, 0, 16)
            }
            PRESETS.forEach { (durationMs, label) ->
                val btn = Button(context).apply {
                    text = label
                    textSize = 13f
                    setOnClickListener {
                        onSetTimer?.invoke(durationMs)
                        dismiss()
                    }
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(4, 4, 4, 4)
                    }
                }
                grid.addView(btn)
            }
            addView(grid)

            // Cancel button
            addView(Button(context).apply {
                text = "Cancel Timer"
                setTextColor(android.graphics.Color.parseColor("#FF6F00"))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    onCancelTimer?.invoke()
                    dismiss()
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        startStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopStatusUpdates()
    }

    private fun startStatusUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                val remaining = getRemainingMs?.invoke() ?: -1L
                tvStatus?.text = if (remaining >= 0) {
                    val mins = remaining / 60_000L
                    val secs = (remaining % 60_000L) / 1000L
                    "Pausing in %02d:%02d".format(mins, secs)
                } else {
                    "Timer not set"
                }
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopStatusUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
    }
}
