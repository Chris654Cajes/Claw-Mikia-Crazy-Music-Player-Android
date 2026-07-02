# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance, aesthetic Android music player designed for audiophiles and
power users who demand precision, customizability, and a striking visual identity. Built with Kotlin
and modern architecture, it merges professional-grade audio manipulation with a vibrant,
cyberpunk-inspired dynamic interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine & Studio Controls

- **Precision Playback**: Independent adjustment of **Speed** (0.5x - 3.0x) and **Pitch** (-6.0 to
  +6.0 semitones).
- **A-B Repeat & Smart Looping**: Precise looping with dedicated markers and suggested loop points
  from song analysis.
- **Timeline Trim**: Non-destructive trimming to eliminate unwanted intros or outros without
  modifying the original file.
- **Playback Profiles**: Create and save multiple custom profiles per song, storing unique pitch,
  speed, and trim settings.
- **Skip Regions**: Define and automatically bypass intros, ads, or outros for a seamless listening
  experience.

### 📊 Intelligent Song Analysis

- **BPM & Key Detection**: Automatic analysis of tracks for seamless mixing and library
  organization.
- **Chorus Detection**: Automatically identifies the most energetic parts of a song.
- **Silence Detection**: Finds and marks silent regions at the start, end, or middle of tracks.
- **Waveform Visualization**: Real-time interactive waveform for precise seeking and trimming.

### 📱 Cyberpunk UI & Aesthetic UX

- **Neon Aesthetic**: High-contrast, tech-forward interface with dynamic neon accents and "
  Aesthetic" dialogs.
- **Adaptive Theming**: Palette-based UI that morphs its colors to match the current album art.
- **Collapsible Mini Player**: A sleek playback bar that can be collapsed into a minimal "瞼" (
  eyelid) mode to maximize screen space.
- **Orbitron Typography**: Futuristically styled fonts for a consistent, immersive sci-fi
  atmosphere.
- **Synchronized Lyrics**: Full `.lrc` support with real-time scrolling and a dedicated lyrics
  panel.

### 📂 Advanced Library Management

- **Smart Scanning**: Deep scan folders with persistent URI permissions.
- **Cloud Sync**: Fetch missing high-resolution album art and metadata automatically via the
  MusicBrainz API.
- **Advanced Filtering**: Find songs using complex criteria: duration ranges, pitch/speed offsets,
  addition date, and manual edit status.
- **Library Export**: Package your curated library and metadata into a single ZIP archive for backup
  or transfer.
- **File Management**: Move files between folders and manage playlists directly within the app.

---

## 🚀 Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3, ViewBinding, ConstraintLayout, Custom Canvas (for Waveforms)
- **Database**: Room (SQLite) with KSP for high-performance metadata storage
- **Audio**: Android Media3 (ExoPlayer), AudioEffect API for DSP
- **Concurrency**: Coroutines & Flow for reactive data streams
- **Image Loading**: Glide for efficient album art rendering
- **Networking**: OkHttp & Retrofit for MusicBrainz integration
- **Background Tasks**: WorkManager for library scanning and metadata fetching

---

## 🛠️ Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.1+) or newer
- JDK 17
- Android SDK 35 (Target) / 26 (Min)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and run the `:app` module.

---

## 🛡️ Quality & Security

- **Privacy First**: Respects Scoped Storage and uses standard MediaStore permissions where
  appropriate.
- **Performance Optimized**: Low-latency audio playback and efficient background processing to
  minimize battery impact.
- **Modern Standards**: Uses official AndroidX libraries and Google-recommended architectural
  patterns.

---
*Developed by **Christopher Lee Cajes***
