package com.mochimochi.clawmikia.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.data.model.SongAnalysis
import com.mochimochi.clawmikia.ui.viewmodels.NowPlayingViewModel

class SongAnalysisFragment : BottomSheetDialogFragment() {

    private val viewModel: NowPlayingViewModel by activityViewModels()

    companion object {
        fun newInstance() = SongAnalysisFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ScrollView(requireContext()).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 32)
                id = android.R.id.content
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<LinearLayout>(android.R.id.content)

        fun label(text: String) = TextView(requireContext()).apply {
            this.text = text; textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#8888AA"))
            letterSpacing = 0.1f
            setPadding(0, 16, 0, 2)
        }

        fun value(text: String) = TextView(requireContext()).apply {
            this.text = text; textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#F0F0FF"))
        }

        container.addView(TextView(requireContext()).apply {
            text = "SONG ANALYSIS"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#FA024D"))
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 16)
        })

        val tvBpm = value("Analyzing…")
        val tvKey = value("Analyzing…")
        val tvChorus = value("—")
        val tvSilence = value("—")
        val tvLoop = value("—")

        container.addView(label("BPM"))
        container.addView(tvBpm)
        container.addView(label("KEY"))
        container.addView(tvKey)
        container.addView(label("CHORUS TIMESTAMPS"))
        container.addView(tvChorus)
        container.addView(label("SILENCE REGIONS"))
        container.addView(tvSilence)
        container.addView(label("SUGGESTED LOOP"))
        container.addView(tvLoop)

        val btnApplyLoop = Button(requireContext()).apply {
            text = "Apply Suggested Loop"
            setTextColor(android.graphics.Color.parseColor("#00E5FF"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            visibility = View.GONE
        }
        container.addView(btnApplyLoop)

        viewModel.songAnalysis.observe(viewLifecycleOwner) { analysis ->
            if (analysis == null) {
                tvBpm.text = "Not yet analyzed"
                tvKey.text = "Not yet analyzed"
                return@observe
            }
            tvBpm.text = if (analysis.bpm > 0) "%.1f BPM (confidence %.0f%%)".format(
                analysis.bpm,
                analysis.bpmConfidence * 100
            ) else "Not detected"
            tvKey.text = if (analysis.key.isNotBlank()) "%s (confidence %.0f%%)".format(
                analysis.key,
                analysis.keyConfidence * 100
            ) else "Not detected"

            val choruses = analysis.chorusList()
            tvChorus.text =
                if (choruses.isEmpty()) "None detected" else choruses.joinToString(", ") {
                    formatMs(it)
                }

            val silences = analysis.silenceList()
            tvSilence.text =
                if (silences.isEmpty()) "None detected" else silences.joinToString("\n") {
                    "${
                        formatMs(it.first)
                    } → ${formatMs(it.second)}"
                }

            if (analysis.suggestedLoopStart >= 0 && analysis.suggestedLoopEnd >= 0) {
                tvLoop.text =
                    "${formatMs(analysis.suggestedLoopStart)} → ${formatMs(analysis.suggestedLoopEnd)}"
                btnApplyLoop.visibility = View.VISIBLE
                btnApplyLoop.setOnClickListener { applyLoop(analysis); dismiss() }
            } else {
                tvLoop.text = "No suitable loop found"
            }
        }
    }

    private fun applyLoop(analysis: SongAnalysis) {
        val profile = viewModel.activeProfile.value ?: return
        viewModel.updateLoop(
            profile.id,
            analysis.suggestedLoopStart,
            analysis.suggestedLoopEnd,
            true
        )
    }

    private fun formatMs(ms: Long): String {
        val m = ms / 60_000L
        val s = (ms % 60_000L) / 1000L
        return "%d:%02d".format(m, s)
    }
}
