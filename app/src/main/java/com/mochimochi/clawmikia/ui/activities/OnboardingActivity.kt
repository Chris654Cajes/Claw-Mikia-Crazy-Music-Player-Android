package com.mochimochi.clawmikiacrazy.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.mochimochi.clawmikiacrazy.MusicVaultApp
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        val onboardingItems = listOf(
            OnboardingItem(
                "AESTHETIC AUDIO",
                "Welcome to Music Vault. Your local library, transformed with high-contrast neon vibes and adaptive theming.",
                R.drawable.ic_vault_logo,
                R.color.neon_pink
            ),
            OnboardingItem(
                "SMART SCAN & SYNC",
                "Effortlessly scan folders. Tap the Cloud icon to auto-fetch missing album art and metadata from MusicBrainz.",
                R.drawable.ic_folder_add,
                R.color.neon_cyan
            ),
            OnboardingItem(
                "ADVANCED FILTERS",
                "Find exactly what you need. Filter by duration, pitch, speed, or even the date you added the song.",
                R.drawable.ic_layers,
                R.color.neon_green
            ),
            OnboardingItem(
                "STUDIO CONTROLS",
                "Professional-grade tools. Independent Pitch and Speed control, A-B Repeat, and non-destructive Timeline Trimming.",
                R.drawable.ic_pitch,
                R.color.neon_yellow
            ),
            OnboardingItem(
                "SONG ANALYSIS",
                "Deep dive into your audio. Automatic detection of BPM, Key, Chorus sections, and Silence regions.",
                R.drawable.ic_speaker,
                R.color.neon_orange
            ),
            OnboardingItem(
                "PLAYBACK PROFILES",
                "Set it and forget it. Create custom profiles for each song to save your preferred pitch, speed, and loop settings.",
                R.drawable.ic_save,
                R.color.neon_purple
            ),
            OnboardingItem(
                "SYNCED LYRICS",
                "Sing along in style. Full LRC support with a sleek, real-time synchronized lyrics interface.",
                R.drawable.ic_note_alt,
                R.color.neon_blue
            ),
            OnboardingItem(
                "READY TO ROCK?",
                "Your personal music sanctuary is ready. Let's get started.",
                R.drawable.ic_sparkles,
                R.color.neon_pink
            )
        )

        val adapter = OnboardingAdapter(onboardingItems)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { _, _ -> }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == onboardingItems.size - 1) {
                    binding.btnNext.text = "FINISH"
                } else {
                    binding.btnNext.text = "NEXT"
                }
            }
        })

        binding.btnNext.setOnClickListener {
            if (binding.viewPager.currentItem < onboardingItems.size - 1) {
                binding.viewPager.currentItem += 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        MusicVaultApp.instance.prefs.edit {
            putBoolean(MusicVaultApp.KEY_FIRST_LAUNCH, false)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    data class OnboardingItem(
        val title: String,
        val description: String,
        val imageRes: Int,
        val colorRes: Int
    )

    inner class OnboardingAdapter(private val items: List<OnboardingItem>) :
        RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder>() {

        inner class OnboardingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivImage = view.findViewById<ImageView>(R.id.ivOnboardingImage)
            private val tvTitle = view.findViewById<TextView>(R.id.tvOnboardingTitle)
            private val tvDesc = view.findViewById<TextView>(R.id.tvOnboardingDesc)

            fun bind(item: OnboardingItem) {
                ivImage.setImageResource(item.imageRes)
                ivImage.setColorFilter(getColor(item.colorRes))
                tvTitle.text = item.title
                tvTitle.setTextColor(getColor(item.colorRes))
                tvDesc.text = item.description
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
            return OnboardingViewHolder(
                LayoutInflater.from(parent.context).inflate(
                    R.layout.item_onboarding_page,
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }
}
