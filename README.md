# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance Android music player designed for audiophiles and power users
who demand precision and style.
Built with Kotlin and modern architecture, it combines professional-grade audio processing with a
striking, cyberpunk-inspired dynamic user interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine

- **DSP Processor**: Real-time control over Bass Boost, Reverb, and Loudness Enhancement.
- **Color-Coded Precision Controls**: Independent adjustment of playback speed (0.5x - 3.0x) and
  pitch (-6.0 to +6.0 semitones) with high-precision increments and dedicated ± step buttons.
- **A-B Repeat / Loop**: High-precision looping with dedicated Set A/B markers and toggleable loop
  modes for practicing or studying specific sections.
- **Timeline Trim**: Non-destructive start and end point trimming with ±10s precision adjustments to
  skip silent intros or long outros.
- **Playback Comparison**: Instant "Original vs Updated" toggle to compare your audio adjustments
  with the raw track in real-time.
- **Volume Management**: Fine-grained volume control (0-100%) with dedicated mute and one-tap 100%
  reset shortcuts.
- **Advanced Repeat Modes**: Includes "Repeat Auto" mode and neon-coded visual feedback for current
  playback states.
- **Sleep Timer**: Integrated countdown timer to automatically pause playback, accessible via a
  dedicated bottom sheet.
- **Profile Management**: Create and activate custom audio profiles that automatically sync pitch,
  speed, trim, and DSP settings.
- **Global Reset**: Dedicated "Destroy All States" safety switch (with confirmation) to instantly
  wipe all customizations and return a track to its original form.

### 📊 Advanced Analysis & UI

- **BPM & Key Detection**: Automatic analysis of tracks to determine tempo and musical key,
  displayed prominently in the Now Playing screen.
- **Smart Skip / Cut Sections**: Define multiple custom skip regions (intros, outros, ads) that the
  player will automatically jump over during playback.
- **Visual Status Badges**: List items provide instant visual feedback on whether a song has been
  pitched, trimmed, speed-adjusted, or manually edited.
- **Smart Seekbar Overlay**: Visualize your custom skip regions directly on the main playback
  seekbar.
- **Advanced Playlist Management**: Powerful search and multi-criteria filtering (Smart, Manual,
  Recent, Has Songs, Empty) to organize your library with ease.
- **Bulk Selection**: Easily select and add multiple tracks to playlists via the Song Selection
  dialog.

### 📱 Cyberpunk Interface

- **Neon Aesthetic**: A futuristic, high-contrast UI featuring vibrant neon accents in Pink, Cyan,
  Blue, Green, Yellow, and Purple.
- **Orbitron Typography**: Built using the iconic Orbitron font for a consistent, tech-forward
  sci-fi atmosphere.
- **Dynamic Theming**: Automatic UI color adaptation based on album art using the Android Palette
  API.
- **Standardized Precision**: Uniform 36dp control heights and flush-right reset buttons for a
  consistent and professional feel across all adjustment modules.
- **Synchronized Lyrics**: Full `.lrc` support with an **integrated editor** to create, sync, and
  save lyrics directly to the local database. Powered by [LRCLIB](https://lrclib.net/).

---

## 🚀 Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3 (DayNight), ViewBinding, ConstraintLayout, Custom Canvas Drawing
- **Database**: [Room](https://developer.android.com/training/data-storage/room) (SQLite) with KSP
- **Audio**: Android MediaSession, AudioEffect API, ExoPlayer / Media3
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
