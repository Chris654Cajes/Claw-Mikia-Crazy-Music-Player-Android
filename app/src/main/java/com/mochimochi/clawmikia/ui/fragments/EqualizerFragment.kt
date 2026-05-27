package com.mochimochi.clawmikia.ui.fragments

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mochimochi.clawmikia.R
import com.mochimochi.clawmikia.data.model.EqPreset
import com.mochimochi.clawmikia.data.model.PlaybackProfile
import com.mochimochi.clawmikia.ui.viewmodel.NowPlayingViewModel

/**
 * Bottom-sheet fragment presenting the full DSP/EQ suite:
 *  - EQ on/off toggle
 *  - 10-band sliders (-15 dB to +15 dB)
 *  - Preset chip group (built-in + user)
 *  - Bass boost slider
 *  - Reverb preset spinner
 *  - Loudness enhancer slider
 *  - Save custom preset button
 *
 * Changes are applied live via ViewModel → Service → DSPProcessor.
 */
class EqualizerFragment : BottomSheetDialogFragment() {

    private val viewModel: NowPlayingViewModel by activityViewModels()
    var onApplyCallback: ((PlaybackProfile) -> Unit)? = null

    private val bandLabels =
        listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
    private val bandSliders = arrayOfNulls<SeekBar>(10)
    private var eqEnabledSwitch: SwitchMaterial? = null
    private var bassBoostSlider: SeekBar? = null
    private var bassBoostSwitch: SwitchMaterial? = null
    private var reverbSpinner: Spinner? = null
    private var reverbSwitch: SwitchMaterial? = null
    private var loudnessSlider: SeekBar? = null
    private var loudnessSwitch: SwitchMaterial? = null
    private var presetChipGroup: ChipGroup? = null

    private var suppressUpdate = false

    companion object {
        fun newInstance(callback: (PlaybackProfile) -> Unit): EqualizerFragment {
            return EqualizerFragment().also { it.onApplyCallback = callback }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_equalizer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEqSection(view)
        setupEffectsSection(view)
        setupPresets(view)
        observeProfile()
    }

    private fun setupEqSection(view: View) {
        eqEnabledSwitch = view.findViewById(R.id.switchEqEnabled)
        val bandContainer = view.findViewById<LinearLayout>(R.id.containerEqBands)
        val ctx = requireContext()

        for (i in 0 until 10) {
            val bandView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4, 0, 4, 0)
            }
            val seekBar = SeekBar(ctx).apply {
                max = 30  // 0–30 maps to -15dB to +15dB
                progress = 15
                rotation = -90f
                layoutParams = LinearLayout.LayoutParams(180, 40)
                progressTintList =
                    ContextCompat.getColorStateList(ctx, android.R.color.holo_red_light)
                thumbTintList = ContextCompat.getColorStateList(ctx, android.R.color.white)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser && !suppressUpdate) commitEqChange()
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            bandSliders[i] = seekBar
            val label = TextView(ctx).apply {
                text = bandLabels[i]; textSize = 9f
                setTextColor(ContextCompat.getColor(ctx, android.R.color.darker_gray))
            }
            bandView.addView(seekBar)
            bandView.addView(label)
            bandContainer.addView(bandView)
        }

        eqEnabledSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!suppressUpdate) commitEqChange()
        }

        view.findViewById<Button>(R.id.btnResetEq)?.setOnClickListener {
            suppressUpdate = true
            bandSliders.forEach { it?.progress = 15 }
            suppressUpdate = false
            commitEqChange()
        }

        view.findViewById<Button>(R.id.btnSavePreset)?.setOnClickListener {
            showAestheticInputDialog(
                title = "Save EQ Preset",
                hint = "Preset name"
            ) { name ->
                val presetName = name.ifBlank { "Custom" }
                viewModel.saveCustomEqPreset(presetName, getCurrentBands())
            }
        }
    }

    private fun setupEffectsSection(view: View) {
        bassBoostSwitch = view.findViewById(R.id.switchBassBoost)
        bassBoostSlider = view.findViewById(R.id.sliderBassBoost)
        reverbSwitch = view.findViewById(R.id.switchReverb)
        reverbSpinner = view.findViewById(R.id.spinnerReverb)
        loudnessSwitch = view.findViewById(R.id.switchLoudness)
        loudnessSlider = view.findViewById(R.id.sliderLoudness)

        bassBoostSlider?.max = 1000
        bassBoostSlider?.setOnSeekBarChangeListener(simpleSeekListener { if (!suppressUpdate) commitEffectsChange() })

        bassBoostSwitch?.setOnCheckedChangeListener { _, _ -> if (!suppressUpdate) commitEffectsChange() }

        val reverbPresets = arrayOf(
            "None",
            "Small Room",
            "Medium Room",
            "Large Room",
            "Medium Hall",
            "Large Hall",
            "Plate"
        )
        reverbSpinner?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            reverbPresets
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        reverbSwitch?.setOnCheckedChangeListener { _, _ -> if (!suppressUpdate) commitEffectsChange() }

        loudnessSlider?.max = 1000
        loudnessSlider?.setOnSeekBarChangeListener(simpleSeekListener { if (!suppressUpdate) commitEffectsChange() })
        loudnessSwitch?.setOnCheckedChangeListener { _, _ -> if (!suppressUpdate) commitEffectsChange() }
    }

    private fun setupPresets(view: View) {
        presetChipGroup = view.findViewById(R.id.chipGroupPresets)
        viewModel.eqPresets.observe(viewLifecycleOwner) { presets ->
            presetChipGroup?.removeAllViews()
            presets.forEach { preset ->
                val chip = Chip(requireContext()).apply {
                    text = preset.name
                    isCheckable = true
                    setOnClickListener { applyPreset(preset) }
                }
                presetChipGroup?.addView(chip)
            }
        }
    }

    private fun applyPreset(preset: EqPreset) {
        suppressUpdate = true
        val bands = preset.bandList()
        bands.forEachIndexed { i, db -> bandSliders[i]?.progress = (db + 15).coerceIn(0, 30) }
        suppressUpdate = false
        commitEqChange(presetName = preset.name)
    }

    private fun observeProfile() {
        viewModel.activeProfile.observe(viewLifecycleOwner) { profile ->
            profile ?: return@observe
            suppressUpdate = true
            val bands = profile.eqBandList()
            bands.forEachIndexed { i, db -> bandSliders[i]?.progress = (db + 15).coerceIn(0, 30) }
            eqEnabledSwitch?.isChecked = profile.eqEnabled
            bassBoostSlider?.progress = profile.bassBoostStrength
            bassBoostSwitch?.isChecked = profile.bassBoostEnabled
            reverbSpinner?.setSelection((profile.reverbPreset + 1).coerceIn(0, 6))
            reverbSwitch?.isChecked = profile.reverbEnabled
            loudnessSlider?.progress = profile.loudnessGain
            loudnessSwitch?.isChecked = profile.loudnessEnabled
            suppressUpdate = false
        }
    }

    private fun getCurrentBands(): List<Int> =
        bandSliders.map { (it?.progress ?: 15) - 15 }

    private fun commitEqChange(presetName: String = "Custom") {
        val currentProfile = viewModel.activeProfile.value ?: return
        val bands = getCurrentBands()
        val enabled = eqEnabledSwitch?.isChecked ?: false
        val name = if (bands.all { it == 0 }) "Flat" else presetName

        viewModel.updateEq(currentProfile.id, bands, name, enabled)

        // Pass the updated values immediately to avoid stale state from ViewModel async update
        val updatedProfile = currentProfile.copy(
            eqBands = bands.joinToString(","),
            eqPresetName = name,
            eqEnabled = enabled
        )
        onApplyCallback?.invoke(updatedProfile)
    }

    private fun commitEffectsChange() {
        val currentProfile = viewModel.activeProfile.value ?: return
        val updated = currentProfile.copy(
            bassBoostStrength = bassBoostSlider?.progress ?: 0,
            bassBoostEnabled = bassBoostSwitch?.isChecked ?: false,
            reverbPreset = (reverbSpinner?.selectedItemPosition ?: 0) - 1,
            reverbEnabled = reverbSwitch?.isChecked ?: false,
            loudnessGain = loudnessSlider?.progress ?: 0,
            loudnessEnabled = loudnessSwitch?.isChecked ?: false
        )
        viewModel.updateEq(
            currentProfile.id, updated.eqBandList(), updated.eqPresetName, updated.eqEnabled
        )
        onApplyCallback?.invoke(updated)
    }

    private fun simpleSeekListener(onChanged: () -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChanged()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }

    // ─── Aesthetic Dialogs ───────────────────────────────────────────────────────

    private fun showAestheticInputDialog(
        title: String,
        hint: String = "",
        positiveText: String = "Save",
        onPositive: (String) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_generic, null)

        dialogView.findViewById<TextView>(R.id.tvTitle).text = title
        val tilInput =
            dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilInput)
        val etInput =
            dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etInput)
        val btnNegative = dialogView.findViewById<ImageButton>(R.id.btnNegative)

        tilInput.visibility = View.VISIBLE
        tilInput.hint = hint
        btnNegative.visibility = View.VISIBLE

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        btnNegative.setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<ImageButton>(R.id.btnPositive).setOnClickListener {
            onPositive(etInput.text.toString())
            dialog.dismiss()
        }

        dialog.show()
    }
}
