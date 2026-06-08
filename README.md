# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance Android music player designed for audiophiles and power users
who demand precision and style.
Built with Kotlin and modern architecture, it combines professional-grade audio processing with a
striking, cyberpunk-inspired dynamic user interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine

- **DSP Processor**: Real-time control over Bass Boost, Reverb, and Loudness Enhancement.
- **Pitch & Speed Control**: Independent adjustment of playback speed (0.5x - 3.0x) and pitch (-6.0
  to +6.0 semitones) with high-precision increments.
- **A-B Repeat**: High-precision looping for practice or study with dedicated Set A/B markers and
  toggleable loop modes.
- **Timeline Trim**: Non-destructive start and end point trimming with ±10s precision adjustments.
- **Playback Comparison**: Instant "Original vs Updated" toggle to compare audio adjustments with
  the raw track.
- **Volume Management**: Fine-grained volume control (0-100%) with dedicated mute and 100% reset
  shortcuts.
- **Shuffle & Repeat**: Advanced playback order management with neon-coded visual feedback for
  current modes.
- **Sleep Timer**: Integrated countdown timer to automatically pause playback, accessible directly
  from the audio features.
- **Profile Management**: Create custom audio profiles that automatically sync pitch, speed, trim,
  and DSP settings.
- **Global Reset**: Dedicated "Destroy All States" safety switch to instantly wipe all
  customizations
  and return a track to its original form.

### 📊 Advanced Analysis

- **BPM & Key Detection**: Automatic analysis of tracks to determine tempo and musical key.
- **Waveform Visualization**: Interactive waveform rendering for precise navigation and trimming.
- **Smart Skip Regions**: Define and automatically skip specific sections (intros, outros, ads)
  with visual overlay support.
- **Silence Detection**: Smart analysis to identify and skip silence in tracks.
- **Advanced Playlist Management**: Powerful search and multi-criteria filtering (Smart, Manual,
  Recent, Status-based) to organize your library.

### 📱 Cyberpunk Interface

- **Neon Aesthetic**: A futuristic, high-contrast UI featuring vibrant neon accents in Pink, Cyan,
  Blue, Green, and Purple.
- **Orbitron Typography**: Built using the iconic Orbitron font for a consistent, tech-forward
  sci-fi atmosphere.
- **Dynamic Theming**: Automatic UI color adaptation based on album art using the Android Palette
  API.
- **Glow & Glassmorphism**: Immersive visual depth with animated neon glow circles and
  semi-transparent surfaces.
- **Uniform Precision**: Standardized control layouts with ± step buttons and one-tap resets across
  all audio adjustment modules.
- **Synchronized Lyrics**: Full `.lrc` support with an **integrated editor** to create, sync, and
  save lyrics directly to the local database. Powered by [LRCLIB](https://lrclib.net/).

---

## 🚀 Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3, ViewBinding, ConstraintLayout, Custom Canvas Drawing
- **Database**: [Room](https://developer.android.com/training/data-storage/room) (SQLite) with KSP
- **Audio**: Android MediaSession, AudioEffect API, ExoPlayer
- **Concurrency**: Coroutines & Flow
- **Image Loading**: [Glide](https://github.com/bumptech/glide)
- **Background Tasks**: WorkManager
- **Networking**: [OkHttp](https://square.github.io/okhttp/)

---

## 🛠️ Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17
- Android SDK 35 (Target) / 26 (Min)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and run on your device.

---

## 🛡️ Quality & Security

- **Official Dependencies**: Built with standard AndroidX and Google libraries for stability.
- **Privacy First**: Uses Scoped Storage and MediaStore to respect user data privacy.
- **Performance**: Optimized background services for low-latency, uninterrupted playback and
  seamless song transitions.

---
*Developed with ❤️ by **Christopher Lee Cajes***
