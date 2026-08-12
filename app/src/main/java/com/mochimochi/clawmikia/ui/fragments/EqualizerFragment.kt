package com.mochimochi.clawmikiacrazy.ui.fragments

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.os.IBinder
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.Song
import com.mochimochi.clawmikiacrazy.databinding.FragmentEqualizerBinding
import com.mochimochi.clawmikiacrazy.service.MusicService
import com.mochimochi.clawmikiacrazy.audio.dsp.DSPProcessor

class EqualizerFragment : Fragment() {

    private var _binding: FragmentEqualizerBinding? = null
    private val binding get() = _binding!!

    private var musicService: MusicService? = null
    private var isPresetsInitialized = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService()
            setupEqualizerUI()
            musicService?.getCurrentSong()?.let { applySongPalette(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEqualizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().bindService(
            Intent(requireActivity(), MusicService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun applySongPalette(song: Song) {
        if (song.albumArtUrl.isBlank()) return
        Glide.with(this).asBitmap().load(song.albumArtUrl).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                Palette.from(resource).generate { palette ->
                    palette?.let {
                        val neon = it.getVibrantColor(
                            ContextCompat.getColor(
                                requireContext(),
                                R.color.neon_cyan
                            )
                        )
                        updateUIColors(neon)
                    }
                }
            }

            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
        })
    }

    private fun updateUIColors(color: Int) {
        val csl = ColorStateList.valueOf(color)
        binding.switchEqualizer.thumbTintList = csl
        binding.switchEqualizer.trackTintList = csl.withAlpha(128)
        binding.seekBassBoost.progressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.neon_pink))
        binding.seekVirtualizer.progressTintList = csl

        for (i in 0 until binding.eqSlidersContainer.childCount) {
            val child = binding.eqSlidersContainer.getChildAt(i)
            val seek = child.findViewById<SeekBar>(R.id.seekBand)
            seek?.progressTintList = csl
            seek?.thumbTintList = csl
        }
    }

    private fun setupEqualizerUI() {
        val svc = musicService ?: return
        val dsp = svc.getDspProcessor() ?: return
        val eq = dsp.getEqualizer() ?: return

        binding.switchEqualizer.setOnCheckedChangeListener(null)
        binding.switchEqualizer.isChecked = eq.enabled
        updateEqualizerState(eq.enabled)

        binding.switchEqualizer.setOnCheckedChangeListener { _, isChecked ->
            dsp.setEqualizerEnabled(isChecked)
            updateEqualizerState(isChecked)
        }

        binding.eqSlidersContainer.removeAllViews()
        val numBands = eq.numberOfBands
        val range = eq.bandLevelRange
        val minLevel = range[0]
        val maxLevel = range[1]

        for (i in 0 until numBands.toInt()) {
            val band = i.toShort()
            val freq = eq.getCenterFreq(band) / 1000 // Convert to Hz

            val bandLayout = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_eq_band, binding.eqSlidersContainer, false)
            val tvFreq = bandLayout.findViewById<TextView>(R.id.tvFreq)
            val seekBar = bandLayout.findViewById<SeekBar>(R.id.seekBand)
            val tvLevel = bandLayout.findViewById<TextView>(R.id.tvLevel)

            tvFreq.text = if (freq >= 1000) "${freq / 1000}kHz" else "${freq}Hz"
            seekBar.max = (maxLevel - minLevel).toInt()
            seekBar.progress = (eq.getBandLevel(band) - minLevel).toInt()

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val level = (progress + minLevel).toShort()
                        dsp.applyEqualizerBand(band, level)
                        tvLevel.text = getString(R.string.db_format, level / 100)
                        sBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (binding.spinnerPresets.selectedItemPosition != 0) {
                            binding.spinnerPresets.setSelection(0, false)
                        }
                    }
                }

                override fun onStartTrackingTouch(sBar: SeekBar?) {}
                override fun onStopTrackingTouch(sBar: SeekBar?) {}
            })

            tvLevel.text = getString(R.string.db_format, eq.getBandLevel(band) / 100)
            binding.eqSlidersContainer.addView(bandLayout)
        }

        // Bass Boost
        binding.seekBassBoost.max = 1000
        binding.seekBassBoost.progress = dsp.getBassBoostStrength()
        binding.seekBassBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    dsp.applyBassBoost(progress, true)
                    sBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }

            override fun onStartTrackingTouch(sBar: SeekBar?) {}
            override fun onStopTrackingTouch(sBar: SeekBar?) {}
        })

        // Virtualizer
        binding.seekVirtualizer.max = 1000
        binding.seekVirtualizer.progress = dsp.getVirtualizerStrength()
        binding.seekVirtualizer.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    dsp.applyVirtualizer(progress, true)
                    sBar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }

            override fun onStartTrackingTouch(sBar: SeekBar?) {}
            override fun onStopTrackingTouch(sBar: SeekBar?) {}
        })

        setupPresets(eq)
    }

    private fun updateEqualizerState(enabled: Boolean) {
        binding.eqSlidersContainer.alpha = if (enabled) 1.0f else 0.4f
        binding.spinnerPresets.isEnabled = enabled
        binding.spinnerPresets.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun setupPresets(eq: Equalizer) {
        if (!isPresetsInitialized) {
            val numPresets = eq.numberOfPresets
            val presetNames = mutableListOf<String>()
            presetNames.add("Manual")
            for (i in 0 until numPresets.toInt()) {
                presetNames.add(eq.getPresetName(i.toShort()))
            }

            val adapter =
                ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerPresets.adapter = adapter
            isPresetsInitialized = true
        }

        val current = eq.currentPreset
        val targetPos = if (current >= 0) current.toInt() + 1 else 0

        binding.spinnerPresets.onItemSelectedListener = null
        binding.spinnerPresets.setSelection(targetPos, false)

        binding.spinnerPresets.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position > 0) {
                        val presetIndex = (position - 1).toShort()
                        try {
                            eq.usePreset(presetIndex)
                            updateSlidersFromEq(eq)
                            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Preset not supported", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun updateSlidersFromEq(eq: Equalizer) {
        val range = eq.bandLevelRange
        val minLevel = range[0]

        for (i in 0 until eq.numberOfBands.toInt()) {
            val band = i.toShort()
            val child = binding.eqSlidersContainer.getChildAt(i) ?: continue
            val seekBar = child.findViewById<SeekBar>(R.id.seekBand)
            val tvLevel = child.findViewById<TextView>(R.id.tvLevel)

            val level = eq.getBandLevel(band)
            seekBar.progress = (level - minLevel).toInt()
            tvLevel.text = getString(R.string.db_format, level / 100)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireActivity().unbindService(serviceConnection)
        } catch (e: Exception) {
        }
        _binding = null
    }
}
