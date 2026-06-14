# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance Android music player designed for audiophiles and power users
who demand precision, customizability, and a striking visual identity. Built with Kotlin and modern
architecture, it merges professional-grade audio manipulation with a vibrant, cyberpunk-inspired
dynamic interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine & Customization

- **DSP Processor**: Granular real-time control over **Bass Boost**, **Reverb**, and **Loudness
  Enhancement**.
- **Precision Playback Controls**: Independent, high-precision adjustment of **Playback Speed** (
  0.5x - 3.0x) and **Pitch** (-6.0 to +6.0 semitones) with dedicated ± step buttons.
- **A-B Repeat / Loop**: Precise looping with Set A/B markers and multiple loop modes.
- **Timeline Trim**: Non-destructive trimming with ±10s precision to eliminate unwanted intros or
  outros.
- **Global Reset & Comparison**: Instant "Original vs Updated" toggle and a "Destroy All States"
  safety switch to reset track customizations.
- **Profile Management**: Save and sync pitch, speed, trim, and DSP settings into custom audio
  profiles.
- **Sleep Timer**: Integrated countdown timer accessible via a dedicated bottom sheet.

### 📊 Advanced Library & Playlist Management

- **Smart Filtering System**: Organize playlists with criteria-based filters: **Smart**, **Manual**,
  **Recent**, **Has Songs**, and **Empty**.
- **Multi-Selection & Bulk Actions**: "Select All" functionality and bulk "Add to Playlist" features
  in both Library and Song Selection dialogs.
- **Folder Discovery**: Integrated folder browser to add music directly from specific directories.
- **Comprehensive Analysis**: Automatic **BPM & Key Detection** with prominent display on the Now
  Playing screen.
- **Custom Skip Regions**: Define multiple skip zones (intros, ads, outros) that the player
  automatically bypasses.
- **Visual Status Badges**: Instant feedback on track items via color-coded badges:
    - 🟢 **Pitch Badge**: Indicates manual pitch adjustments.
    - 🟠 **Trim & Speed Badges**: Indicates active trimming or speed modifications.
    - 🟢 **Manual Badge**: Confirms manual metadata or state overrides.

### 📱 Cyberpunk UI & UX

- **Neon Aesthetic**: A high-contrast, tech-forward interface featuring a spectrum of neon accents (
  Pink, Cyan, Green, Orange, Purple, and Blue).
- **Dynamic Theming**: Support for both **Dark** and **Light** modes, with UI colors that
  dynamically adapt based on album art using the Palette API.
- **Orbitron Typography**: Heavy use of the **Orbitron** (Regular & Bold) font family for a
  consistent sci-fi atmosphere.
- **Interactive List Items**: Features swipe-to-reveal backgrounds, playing indicators, and favorite
  toggles.
- **Smart Seekbar Overlay**: Visualizes custom skip regions directly on the main playback seekbar.
- **Synchronized Lyrics**: Full `.lrc` support with an **integrated editor** powered
  by [LRCLIB](https://lrclib.net/).

---

## 🚀 Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3 (DayNight), ViewBinding, ConstraintLayout, Custom Canvas Drawing
- **Database**: [Room](https://developer.android.com/training/data-storage/room) (SQLite) with KSP
  for robust local storage.
- **Audio**: Android MediaSession, AudioEffect API, ExoPlayer / Media3.
- **Concurrency**: Coroutines & Flow for reactive data streams.
- **Image Loading**: [Glide](https://github.com/bumptech/glide) for optimized album art rendering.
- **Background Tasks**: WorkManager for reliable metadata analysis and background sync.
- **Networking**: [OkHttp](https://square.github.io/okhttp/) for lyric fetching.

---

## 🛠️ Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 35 (Target) / 26 (Min)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and run on your physical device or emulator.

---

## 🛡️ Quality & Security

- **Privacy Centric**: Uses Scoped Storage and MediaStore to respect user data privacy.
- **Performance Optimized**: Low-latency playback services and efficient background processing for
  uninterrupted music.
- **Stability**: Built using official AndroidX and Google libraries for long-term maintainability.

---
*Developed with ❤️ by **Christopher Lee Cajes***
