# MusicVault (Claw Mikia)

**MusicVault** is a high-performance Android music player designed for audiophiles and power users.
Built with Kotlin and modern architecture, it provides advanced audio processing capabilities and a
sleek, cyberpunk-inspired dynamic user interface.

## 🎵 Key Features

### 🎧 Professional Audio Engine

- **DSP Processor**: Real-time control over Equalizer (10-band), Bass Boost, Reverb, and Loudness
  Enhancement.
- **Pitch & Speed Control**: Independent adjustment of playback speed (0.5x - 3.0x) and pitch (-6.0
  to +6.0 semitones) with high-precision increments and instant reset capabilities.
- **A-B Repeat**: Easily loop specific sections of a track for practice or study with precision Set
  A/B markers and dedicated loop mode.
- **Timeline Trim**: Non-destructive start and end point trimming with ±10s precision adjustments
  via
  dedicated control buttons.
- **Playback Comparison**: Instant "Original vs Updated" toggle to compare your audio adjustments
  with the raw track.
- **Volume Management**: Fine-grained volume control (0-100%) with a dedicated mute and 100% reset
  shortcut.
- **Sleep Timer**: Schedule playback to stop automatically with a customizable timer.
- **Profile Management**: Create and switch between custom audio profiles for different hardware or
  environments, automatically syncing pitch, speed, trim, and DSP settings.

### 📊 Advanced Analysis

- **BPM & Key Detection**: Automatically analyzes tracks to determine their tempo and musical key.
- **Waveform Visualization**: Interactive waveform rendering for precise navigation and trimming.
- **Smart Skip Regions**: Define and automatically skip specific sections of a track (intros,
  outros,
  ads) without modifying the original file.
- **Silence Detection**: Smart analysis to identify and skip silence in tracks.
- **Song Analysis**: Detailed technical breakdown of your music library.

### 📱 Smart Interface

- **Neon Aesthetic**: A futuristic, high-contrast UI with vibrant neon accents (Cyan, Pink, Green,
  Orange, Purple).
- **Dynamic Theming**: The UI adapts its color palette based on the currently playing album art
  using the Palette API.
- **Library Management**: Organized view by Songs, Folders, Playlists, and Favorites.
- **Lyrics Support**: Synchronized .lrc support with an integrated editor, "Karaoke Mode," and
  automatic fetch capabilities powered by [LRCLIB](https://lrclib.net/).
- **Precision Controls**: High-accuracy adjustment buttons and instant reset toggles for all audio
  parameters.

## 🚀 Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3, ViewBinding, ConstraintLayout, Custom Canvas Drawing
- **Database**: [Room](https://developer.android.com/training/data-storage/room) (SQLite)
- **Audio**: Android MediaSession, AudioEffect API
- **Concurrency**: Coroutines & Flow
- **Image Loading**: [Glide](https://github.com/bumptech/glide)
- **Annotation Processing**: [KSP](https://kotlinlang.org/docs/ksp-overview.html)

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

## 🛡️ Quality & Security

- **Official Dependencies**: Uses standard AndroidX and Google libraries.
- **Safe Storage**: Uses Scoped Storage and MediaStore for privacy.
- **Performance**: Optimized background services for low-latency playback.

---
*Created with passion by the MusicVault Team.*
