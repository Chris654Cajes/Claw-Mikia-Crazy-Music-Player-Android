# Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance, aesthetic Android music player designed for audiophiles and power users who demand precision, customizability, and a striking visual identity. Built with Kotlin and modern architecture, it merges professional-grade audio manipulation with a vibrant, cyberpunk-inspired dynamic interface.

---

## 🎨 Cyberpunk UI & Aesthetic UX

- **Five Distinct Player UIs**: Interchangeable player themes selectable from Settings:
    - **Standard**: Classic layout with album art, seekbar, and full control suite.
    - **Cover Flow**: 3D-style interactive carousel with swipeable album art.
    - **Radial Wheel**: Futuristic circular dial-based player with neon animations.
    - **VU Meter**: Retro analog-style visualizer with real-time level bars.
    - **Circular**: Minimalist ring-style progress with centered art and controls.
- **Neon Aesthetic**: High-contrast interface using a vibrant "Neon" color palette (Cyan, Pink, Green, Orange, Purple, Teal, Yellow, Red, Blue).
- **Adaptive Theming**: Real-time palette extraction that morphs UI colors and glow effects to match the current album art.
- **Orbitron Typography**: Futuristic sci-fi fonts for a consistent, immersive atmosphere.
- **Collapsible Mini Player**: A unique, space-saving mini player that expands to show controls or collapses into a minimalist accent bar.
- **Interactive Waveforms**: Real-time seekable waveforms for surgical navigation and state visualization.

---

## 🎧 Professional Audio Engine & Studio Controls

- **Precision Playback**: Independent adjustment of **Speed** (0.5x - 3.0x) and **Pitch** (-6.0 to +6.0 semitones) with dedicated fine-tuning and instant reset.
- **Playback Profiles**: Create multiple custom profiles per song. Each profile stores unique pitch, speed, trim, volume, and looping settings.
- **State Toggling**: Instantly switch between the "Original" (unprocessed) and "Updated" (customized) playback states.
- **Timeline Trim**: Non-destructive trimming to eliminate unwanted intros or outros with +/-100ms precision.
- **A-B Repeat & Smart Looping**: Define precise looping points with "Set A" and "Set B" controls. Includes a global loop toggle and A-B mode.
- **Skip Regions**: Define and automatically bypass specific segments (ads, long intros, or fillers) within tracks.
- **Granular Volume Management**: High-fidelity volume control with dedicated mute and "Reset to 100%" functionality.

---

## 🔍 Intelligent Song Analysis

- **BPM & Key Detection**: Automatic analysis of tracks for seamless mixing and organization.
- **Chorus Detection**: Identifies the most energetic parts of a song automatically.
- **Silence Detection**: Finds and marks silent regions at the start, end, or middle of tracks.
- **Suggested Loops**: Automatically identifies the best segments for seamless looping.
- **Visual Indicators**: Aesthetic markers for manually edited songs and active playback states.

---

## 📜 Lyrics & Metadata Engine

- **LRC Synced Lyrics**: Full support for synced lyrics with smooth auto-scrolling.
- **Karaoke Mode**: Real-time highlighting of the current lyric line.
- **Online Lyrics Fetching**: Automatic metadata and lyric retrieval via LrcLib and MusicBrainz API.
- **Manual Lyrics Editor**: Integrated editor for creating or correcting LRC and plain text lyrics.
- **Album Art Management**: Pick custom album art from your gallery or fetch high-res versions online.
- **Tag Editing**: Manual control over Title, Artist, and Album metadata.

---

## 📂 Advanced Library Management

- **Deep Folder Integration**: Browse and play music directly from your file system with persistent URI permissions.
- **Smart Filtering System**: Advanced filtering by Duration, Pitch, Speed, Favorite status, Metadata state, Manual edits, Trim state, and Dates.
- **Global Search**: High-performance search engine for songs, folders, and playlists.
- **Library Export**: Package your entire curated library and metadata into a single ZIP archive for backup or transfer.
- **Smart Playlists**: Create, manage, and filter playlists with aesthetic icons and dedicated views.
- **Folder Management**: Move songs between folders or delete them directly from the app.

---

## 🛠 Tech Stack

| Category | Technology |
|---|---|
| **Language** | Kotlin |
| **Architecture** | MVVM + Repository Pattern |
| **UI Framework** | Material 3, ViewBinding, ConstraintLayout, Custom Canvas |
| **Database** | Room (SQLite) with KSP |
| **Audio Engine** | Android Media3 (ExoPlayer), AudioEffect API |
| **Concurrency** | Kotlin Coroutines & Flow |
| **Image Loading** | Glide |
| **Networking** | OkHttp |
| **Metadata** | MusicBrainz API, LrcLib |
| **Build System** | Gradle, KSP |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1+)
- JDK 17+
- Android SDK 35 (Target) / 26 (Min)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open in Android Studio and sync Gradle.
3. Run the `:app` module.

---

## 🛡 Permissions & Security
- **Privacy First**: Respects Scoped Storage; no unnecessary data collection.
- **Respectful Playback**: Uses standard Android MediaSession for seamless background control.
- **Nuclear Reset**: "Destroy All States" button to clear all metadata and start fresh.

---
*Developed by **Christopher Lee Cajes***
