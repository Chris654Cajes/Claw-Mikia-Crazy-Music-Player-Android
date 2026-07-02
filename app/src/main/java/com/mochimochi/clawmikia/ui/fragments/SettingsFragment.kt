package com.mochimochi.clawmikiacrazy.ui.fragments

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.repository.SettingsRepository
import com.mochimochi.clawmikiacrazy.databinding.FragmentSettingsBinding
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.utils.MetadataFetcher
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsRepo: SettingsRepository
    private val mainViewModel: MainViewModel by activityViewModels()

    // Suppress TextWatcher feedback loop when we programmatically set text
    private var suppressWatcher = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settingsRepo = SettingsRepository(requireContext())

        setupFavoriteIconGroup()
        setupVolumeStep()
        setupPitchStep()
        setupSpeedStep()
        setupTrimStep()
        setupSkipStep()
        setupEnvironmentSpinner()
        setupVolumeControl()
        setupMetadataUpdateButton()
        setupResetButton()
        observeSettings()
    }

    // ── Sound Environment ────────────────────────────────────────────────────

    private fun setupEnvironmentSpinner() {
        val environments = arrayOf("Default", "Car", "Plane", "Bus", "Office", "Street")
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, environments)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerEnvironment.adapter = adapter

        val currentEnv = settingsRepo.getSoundEnvironment()
        val index = environments.indexOf(currentEnv).coerceAtLeast(0)
        binding.spinnerEnvironment.setSelection(index)

        binding.spinnerEnvironment.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    settingsRepo.setSoundEnvironment(environments[position])
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    // ── Volume Control ───────────────────────────────────────────────────────

    private fun setupVolumeControl() {
        binding.seekVolume.progress = settingsRepo.getVolumeLevel()
        updateVolumeCaution(binding.seekVolume.progress)

        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    settingsRepo.setVolumeLevel(progress)
                    updateVolumeCaution(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateVolumeCaution(progress: Int) {
        binding.tvVolumeCaution.visibility = if (progress > 85) View.VISIBLE else View.GONE
    }

    // ── Metadata Update ──────────────────────────────────────────────────────

    private fun setupMetadataUpdateButton() {
        binding.btnUpdateMetadata.setOnClickListener {
            if (MetadataFetcher.isOnline(requireContext())) {
                AlertDialog.Builder(requireContext())
                    .setTitle("UPDATE METADATA")
                    .setMessage("Automatically update all songs, albums, and singers information? This might take a while.")
                    .setPositiveButton("UPDATE") { _, _ ->
                        mainViewModel.fetchMetadataManual(overwriteManual = true)
                        Toast.makeText(
                            requireContext(),
                            "Updating metadata in background...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("OFFLINE")
                    .setMessage("Please connect to the internet to update song metadata.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ── Favorite Icon ──────────────────────────────────────────────────────────

    private fun setupFavoriteIconGroup() {
        val rg = binding.rgFavoriteIcon
        val map = mapOf(
            R.id.rbHeart to "heart",
            R.id.rbSmiley to "smiley",
            R.id.rbStar to "star",
            R.id.rbEye to "eye",
            R.id.rbSun to "sun",
            R.id.rbFlower to "flower",
            R.id.rbMoon to "moon",
            R.id.rbMusic to "music",
            R.id.rbSparkles to "sparkles",
            R.id.rbCloud to "cloud"
        )

        rg.setOnCheckedChangeListener { _, checkedId ->
            map[checkedId]?.let { iconType ->
                settingsRepo.setFavoriteIcon(iconType)
            }
        }
    }

    private fun checkRadioForIcon(iconType: String) {
        val id = when (iconType) {
            "heart" -> R.id.rbHeart
            "smiley" -> R.id.rbSmiley
            "star" -> R.id.rbStar
            "eye" -> R.id.rbEye
            "sun" -> R.id.rbSun
            "flower" -> R.id.rbFlower
            "moon" -> R.id.rbMoon
            "music" -> R.id.rbMusic
            "sparkles" -> R.id.rbSparkles
            "cloud" -> R.id.rbCloud
            else -> R.id.rbHeart
        }
        binding.rgFavoriteIcon.check(id)
    }

    // ── Volume Step ────────────────────────────────────────────────────────────

    private fun setupVolumeStep() {
        val et = binding.etVolumeStep
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s.toString().trim()
                if (raw.isEmpty()) return
                val value = raw.toFloatOrNull() ?: return
                settingsRepo.setVolumeStep(value)
            }
        })

        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = et.text.toString().trim()
                val value = raw.toFloatOrNull() ?: SettingsRepository.DEFAULT_VOLUME_STEP
                val clamped = value.coerceIn(1f, 50f)
                suppressWatcher = true
                et.setText(formatFloat(clamped))
                et.setSelection(et.text.length)
                suppressWatcher = false
                settingsRepo.setVolumeStep(clamped)
            }
        }
    }

    // ── Pitch Step ─────────────────────────────────────────────────────────────

    private fun setupPitchStep() {
        val et = binding.etPitchStep
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s.toString().trim()
                if (raw.isEmpty()) return
                val value = raw.toFloatOrNull() ?: return
                settingsRepo.setPitchStep(value)
            }
        })

        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = et.text.toString().trim()
                val value = raw.toFloatOrNull() ?: SettingsRepository.DEFAULT_PITCH_STEP
                val rounded = (value * 10f).roundToInt() / 10f
                val clamped = rounded.coerceIn(0.1f, 6.0f)
                suppressWatcher = true
                et.setText(formatDecimal1(clamped))
                et.setSelection(et.text.length)
                suppressWatcher = false
                settingsRepo.setPitchStep(clamped)
            }
        }
    }

    // ── Speed Step ─────────────────────────────────────────────────────────────

    private fun setupSpeedStep() {
        val et = binding.etSpeedStep
        et.inputType = InputType.TYPE_CLASS_NUMBER

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s.toString().trim()
                if (raw.isEmpty()) return
                val value = raw.toIntOrNull() ?: return
                settingsRepo.setSpeedStep(value)
            }
        })

        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = et.text.toString().trim()
                val value = raw.toIntOrNull() ?: SettingsRepository.DEFAULT_SPEED_STEP
                val clamped = value.coerceIn(1, 10)
                suppressWatcher = true
                et.setText(clamped.toString())
                et.setSelection(et.text.length)
                suppressWatcher = false
                settingsRepo.setSpeedStep(clamped)
            }
        }
    }

    // ── Trim Step ──────────────────────────────────────────────────────────────

    private fun setupTrimStep() {
        val et = binding.etTrimStep
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s.toString().trim()
                if (raw.isEmpty()) return
                val value = raw.toFloatOrNull() ?: return
                settingsRepo.setTrimStep(value)
            }
        })

        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = et.text.toString().trim()
                val value = raw.toFloatOrNull() ?: SettingsRepository.DEFAULT_TRIM_STEP
                val rounded = (value * 10f).roundToInt() / 10f
                val clamped = rounded.coerceIn(0.1f, 60.0f)
                suppressWatcher = true
                et.setText(formatDecimal1(clamped))
                et.setSelection(et.text.length)
                suppressWatcher = false
                settingsRepo.setTrimStep(clamped)
            }
        }
    }

    // ── Skip Step ──────────────────────────────────────────────────────────────

    private fun setupSkipStep() {
        val et = binding.etSkipStep
        et.inputType = InputType.TYPE_CLASS_NUMBER

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s.toString().trim()
                if (raw.isEmpty()) return
                val value = raw.toIntOrNull() ?: return
                settingsRepo.setSkipStep(value)
            }
        })

        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = et.text.toString().trim()
                val value = raw.toIntOrNull() ?: SettingsRepository.DEFAULT_SKIP_STEP
                val clamped = value.coerceIn(1, 60)
                suppressWatcher = true
                et.setText(clamped.toString())
                et.setSelection(et.text.length)
                suppressWatcher = false
                settingsRepo.setSkipStep(clamped)
            }
        }
    }

    // ── Observe LiveData ────────────────────────────────────────────────────────

    private fun setupResetButton() {
        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("RESET SETTINGS")
                .setMessage("Reset all settings to their default values?")
                .setPositiveButton("RESET") { _, _ ->
                    settingsRepo.resetAll()
                    // Refresh all EditTexts with defaults
                    suppressWatcher = true
                    binding.etVolumeStep.setText(formatFloat(SettingsRepository.DEFAULT_VOLUME_STEP))
                    binding.etPitchStep.setText(formatDecimal1(SettingsRepository.DEFAULT_PITCH_STEP))
                    binding.etSpeedStep.setText(SettingsRepository.DEFAULT_SPEED_STEP.toString())
                    binding.etTrimStep.setText(formatDecimal1(SettingsRepository.DEFAULT_TRIM_STEP))
                    binding.etSkipStep.setText(SettingsRepository.DEFAULT_SKIP_STEP.toString())

                    binding.spinnerEnvironment.setSelection(0)
                    binding.seekVolume.progress = SettingsRepository.DEFAULT_VOLUME_LEVEL
                    updateVolumeCaution(SettingsRepository.DEFAULT_VOLUME_LEVEL)

                    suppressWatcher = false
                    Toast.makeText(
                        requireContext(),
                        "Settings reset to defaults",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun observeSettings() {
        settingsRepo.favoriteIconLive.observe(viewLifecycleOwner) { iconType ->
            checkRadioForIcon(iconType)
        }

        // Only populate initial values if EditText is empty (first load).
        // Avoid resetting text while the user is typing.
        val vol = settingsRepo.getVolumeStep()
        if (binding.etVolumeStep.text.isNullOrEmpty()) {
            binding.etVolumeStep.setText(formatFloat(vol))
        }

        val pitch = settingsRepo.getPitchStep()
        if (binding.etPitchStep.text.isNullOrEmpty()) {
            binding.etPitchStep.setText(formatDecimal1(pitch))
        }

        val speed = settingsRepo.getSpeedStep()
        if (binding.etSpeedStep.text.isNullOrEmpty()) {
            binding.etSpeedStep.setText(speed.toString())
        }

        val trim = settingsRepo.getTrimStep()
        if (binding.etTrimStep.text.isNullOrEmpty()) {
            binding.etTrimStep.setText(formatDecimal1(trim))
        }

        val skip = settingsRepo.getSkipStep()
        if (binding.etSkipStep.text.isNullOrEmpty()) {
            binding.etSkipStep.setText(skip.toString())
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun formatFloat(v: Float): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0')
            .trimEnd('.')
    }

    private fun formatDecimal1(v: Float): String = "%.1f".format(v)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
