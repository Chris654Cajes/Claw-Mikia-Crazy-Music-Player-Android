# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance, aesthetic Android music player designed for audiophiles and
power users who demand precision, customizability, and a striking visual identity. Built with Kotlin
and modern architecture, it merges professional-grade audio manipulation with a vibrant,
cyberpunk-inspired dynamic interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine & Studio Controls

- **Precision Playback**: Independent adjustment of **Speed** (0.5x - 3.0x) and **Pitch** (-6.0 to
  +6.0 semitones) with dedicated fine-tuning buttons and instant reset.
- **A-B Repeat & Smart Looping**: Define precise looping points with "Set A" and "Set B" controls.
  Includes a global loop toggle and A-B mode.
- **Timeline Trim**: Non-destructive trimming to eliminate unwanted intros or outros. Features ±10s
  fine-adjustment buttons for surgical precision.
- **Playback Profiles**: Create and save multiple custom profiles per song. Each profile stores
  unique pitch, speed, and trim settings, switchable instantly from the main player.
- **Skip Regions**: Define and automatically bypass specific segments (ads, long intros, or fillers)
  within tracks for a seamless listening experience.
- **Granular Volume Management**: High-fidelity volume slider with dedicated mute and "Reset to
  100%" functionality.

### 📊 Intelligent Song Analysis

- **BPM & Key Detection**: Automatic analysis of tracks for seamless mixing and library
  organization.
- **Chorus Detection**: Automatically identifies the most energetic parts of a song.
- **Silence Detection**: Finds and marks silent regions at the start, end, or middle of tracks.
- **Waveform Visualization**: Real-time interactive waveform for precise seeking, trimming, and
  skip-region visualization.

### 📱 Cyberpunk UI & Aesthetic UX

- **Neon Aesthetic**: High-contrast, tech-forward interface using "Neon Cyan", "Neon Pink", "Neon
  Green", and "Neon Orange" accents.
- **Card-Based Control Center**: All studio controls are organized into sleek, semi-transparent
  cards (Volume, Pitch, Speed, A-B Repeat, Trim, Skip/Cut, Profiles).
- **Adaptive Theming**: Palette-based UI that morphs its colors and glow effects to match the
  current album art.
- **Orbitron Typography**: Futuristically styled fonts for a consistent, immersive sci-fi
  atmosphere.
- **State Persistence**: Visual indicators for manually edited songs and active playback states (
  Original vs Updated).

### 📂 Advanced Library Management

- **Smart Playlists**: Filter and organize your collection using criteria like "Smart", "Manual", "
  Has Songs", "Empty", and "Recent".
- **Deep Folder Integration**: Browse and play music directly from your file system with persistent
  URI permissions.
- **Global Search**: Find songs or playlists instantly with a high-performance search engine.
- **Cloud Sync**: Fetch missing high-resolution album art and metadata automatically via the
  MusicBrainz API.
- **Library Export**: Package your curated library and metadata into a single ZIP archive for backup
  or transfer.

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
- **Safety First**: "Destroy All States" reset button for troubleshooting or starting fresh with
  song metadata.

---
*Developed by **Christopher Lee Cajes***
