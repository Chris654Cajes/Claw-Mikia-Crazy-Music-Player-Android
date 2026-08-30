package com.mochimochi.clawmikiacrazy.ui.fragments

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mochimochi.clawmikiacrazy.ui.activities.MainActivity
import com.mochimochi.clawmikiacrazy.ui.viewmodels.MainViewModel
import com.mochimochi.clawmikiacrazy.utils.MetadataFetcher
import com.mochimochi.clawmikiacrazy.utils.ProfileBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsRepo: SettingsRepository
    private val mainViewModel: MainViewModel by activityViewModels()

    private val exportProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportProfilesTo(it) }
    }

    private val importProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importProfilesFrom(it) }
    }

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
        setupPitchStep()
        setupSpeedStep()
        setupTrimStep()
        setupSkipStep()
        setupNowPlayingView()
        setupEnvironmentSpinner()
        setupEditingLock()
        setupMetadataUpdateButton()
        setupProfileBackup()
        setupResetButton()
        observeSettings()
    }

    // ── Now Playing View ───────────────────────────────────────────────────

    private fun setupNowPlayingView() {
        val rg = binding.rgNowPlayingView
        rg.setOnCheckedChangeListener { _, checkedId ->
            val viewType = when (checkedId) {
                R.id.rbCoverFlow -> "coverflow"
                R.id.rbRadial -> "radial"
                R.id.rbVuMeter -> "vumeter"
                R.id.rbCircular -> "circular"
                else -> "standard"
            }
            settingsRepo.setNowPlayingView(viewType)
        }
    }

    private fun checkRadioForNowPlayingView(viewType: String) {
        when (viewType) {
            "coverflow" -> binding.rgNowPlayingView.check(R.id.rbCoverFlow)
            "radial" -> binding.rgNowPlayingView.check(R.id.rbRadial)
            "vumeter" -> binding.rgNowPlayingView.check(R.id.rbVuMeter)
            "circular" -> binding.rgNowPlayingView.check(R.id.rbCircular)
            else -> binding.rgNowPlayingView.check(R.id.rbStandard)
        }
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

    // ── Editing Lock ─────────────────────────────────────────────────────────

    private fun setupEditingLock() {
        binding.switchStatesEnabled.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setStatesEnabled(isChecked)
        }
        binding.switchLockEditing.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setEditingLocked(isChecked)
        }
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

    // ── Profile Backup ────────────────────────────────────────────────────────

    private fun setupProfileBackup() {
        binding.btnExportProfiles.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("EXPORT PROFILES")
                .setMessage("Export a JSON file containing only songs that have their own profiles with updated states?")
                .setPositiveButton("EXPORT") { _, _ ->
                    exportProfilesLauncher.launch("ClawMikia_Profiles.json")
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        binding.btnImportProfiles.setOnClickListener {
            importProfilesLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun exportProfilesTo(uri: Uri) {
        lifecycleScope.launch {
            val main = activity as? MainActivity
            val json = withContext(Dispatchers.IO) {
                ProfileBackup.buildExportJson(requireContext())
            }
            if (json == null) {
                main?.showAestheticStatusDialog(
                    success = false,
                    title = "NO MATCHES",
                    message = "No songs with their own updated profiles found."
                )
                return@launch
            }
            val tempFile = File(requireContext().cacheDir, "profiles_export.json")
            withContext(Dispatchers.IO) {
                tempFile.writeText(json.toString(2))
            }
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
                val count = json.optJSONArray("songs")?.length() ?: 0
                main?.showAestheticStatusDialog(
                    success = true,
                    title = "EXPORT SUCCESS",
                    message = "Exported profiles for $count song(s)."
                )
            } catch (e: Exception) {
                main?.showAestheticStatusDialog(
                    success = false,
                    title = "SAVE FAILED",
                    message = "Could not write to the selected location."
                )
            }
        }
    }

    private fun importProfilesFrom(uri: Uri) {
        lifecycleScope.launch {
            val main = activity as? MainActivity
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                main?.showAestheticStatusDialog(
                    success = false,
                    title = "READ FAILED",
                    message = "Could not read the selected JSON file."
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                ProfileBackup.importJson(requireContext(), content)
            }
            if (result.songsMatched == 0) {
                main?.showAestheticStatusDialog(
                    success = false,
                    title = "NO MATCHES",
                    message = "No existing songs matched the imported file."
                )
            } else {
                main?.showAestheticStatusDialog(
                    success = true,
                    title = "IMPORT SUCCESS",
                    message = "Matched ${result.songsMatched} song(s) - " +
                            "added ${result.profilesAdded}, " +
                            "updated ${result.profilesUpdated} profile(s), " +
                            "added ${result.skipRegionsAdded} skip region(s)."
                )
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
        // Removed as per request
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
                    binding.etPitchStep.setText(formatDecimal1(SettingsRepository.DEFAULT_PITCH_STEP))
                    binding.etSpeedStep.setText(SettingsRepository.DEFAULT_SPEED_STEP.toString())
                    binding.etTrimStep.setText(formatDecimal1(SettingsRepository.DEFAULT_TRIM_STEP))
                    binding.etSkipStep.setText(SettingsRepository.DEFAULT_SKIP_STEP.toString())

                    binding.spinnerEnvironment.setSelection(0)

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

        settingsRepo.nowPlayingViewLive.observe(viewLifecycleOwner) { viewType ->
            checkRadioForNowPlayingView(viewType)
        }

        settingsRepo.editingLockedLive.observe(viewLifecycleOwner) { isLocked ->
            binding.switchLockEditing.isChecked = isLocked
        }

        settingsRepo.statesEnabledLive.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchStatesEnabled.isChecked = isEnabled
        }

        // Only populate initial values if EditText is empty (first load).

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
