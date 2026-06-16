# 🎧 Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance Android music player designed for audiophiles and power users
who demand precision, customizability, and a striking visual identity. Built with Kotlin and modern
architecture, it merges professional-grade audio manipulation with a vibrant, cyberpunk-inspired
dynamic interface.

---

## 🎵 Key Features

### 🎧 Professional Audio Engine

- **DSP Processor**: Real-time control over **Bass Boost**, **Reverb**, and **Loudness Enhancement
  **.
- **Precision Playback**: Independent adjustment of **Speed** (0.5x - 3.0x) and **Pitch** (-6.0 to
  +6.0 semitones).
- **A-B Repeat & Looping**: Precise looping with dedicated markers and multiple modes.
- **Timeline Trim**: Non-destructive trimming to eliminate unwanted intros/outros.
- **Custom Profiles**: Save and sync pitch, speed, and DSP settings into per-song audio profiles.

### 📊 Advanced Library Management

- **Smart Filtering**: Organize playlists with advanced criteria-based filters.
- **BPM & Key Detection**: Automatic analysis of tracks for seamless mixing and discovery.
- **Custom Skip Regions**: Define and automatically bypass intros, ads, or outros.
- **Multi-Selection**: Bulk actions for playlist management and library organization.

### 📱 Cyberpunk UI & UX

- **Neon Aesthetic**: High-contrast, tech-forward interface with dynamic neon accents.
- **Material 3 & Palette**: Adaptive theming based on album art for a cohesive visual experience.
- **Orbitron Typography**: Futuristically styled fonts for a consistent sci-fi atmosphere.
- **Synchronized Lyrics**: Full `.lrc` support with an integrated editor.

---

## 🚀 Tech Stack

- **Language**: Kotlin
- **Architecture**: MVVM + Repository Pattern
- **UI**: Material 3, ViewBinding, ConstraintLayout, Custom Canvas
- **Database**: Room (SQLite) with KSP
- **Audio**: Android MediaSession, AudioEffect API, ExoPlayer / Media3
- **Concurrency**: Coroutines & Flow
- **Image Loading**: Glide
- **Background Tasks**: WorkManager
- **Networking**: OkHttp

---

## 🛠️ Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2.1+)
- JDK 17
- Android SDK 35 (Target) / 26 (Min)

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open in Android Studio.
3. Sync Gradle and run.

---

## 🛡️ Quality & Security

- **Privacy First**: Respects Scoped Storage and MediaStore permissions.
- **Performance Optimized**: Low-latency playback and efficient background processing.
- **Modern Libraries**: Uses official AndroidX and Google-recommended dependencies.

---
*Developed by **Christopher Lee Cajes***
