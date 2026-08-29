# Claw Mikia (MusicVault)

**Claw Mikia** is a high-performance, aesthetic Android music player designed for audiophiles and
power users who demand precision, customizability, and a striking visual identity. Built with Kotlin
and modern architecture, it merges professional-grade audio manipulation with a vibrant,
cyberpunk-inspired dynamic interface.

---

## Key Features

### Professional Audio Engine & Studio Controls

- **Precision Playback**: Independent adjustment of **Speed** (0.5x - 3.0x) and **Pitch** (-6.0 to
  +6.0 semitones) with dedicated fine-tuning buttons and instant reset.
- **A-B Repeat & Smart Looping**: Define precise looping points with "Set A" and "Set B" controls.
  Includes a global loop toggle and A-B mode.
- **Timeline Trim**: Non-destructive trimming to eliminate unwanted intros or outros. Features
  +/-10s
  fine-adjustment buttons for surgical precision.
- **Playback Profiles**: Create and save multiple custom profiles per song. Each profile stores
  unique pitch, speed, and trim settings, switchable instantly from the main player.
- **Skip Regions**: Define and automatically bypass specific segments (ads, long intros, or fillers)
  within tracks for a seamless listening experience.
- **Granular Volume Management**: High-fidelity volume slider with dedicated mute and "Reset to
  100%" functionality.

### Intelligent Song Analysis

- **BPM & Key Detection**: Automatic analysis of tracks for seamless mixing and library
  organization.
- **Chorus Detection**: Automatically identifies the most energetic parts of a song.
- **Silence Detection**: Finds and marks silent regions at the start, end, or middle of tracks.
- **Waveform Visualization**: Real-time interactive waveform for precise seeking, trimming, and
  skip-region visualization.

### Five Distinct Player UIs

The app offers five interchangeable player themes, selectable from Settings:

| Mode             | Activity                 | Description                                                           |
|------------------|--------------------------|-----------------------------------------------------------------------|
| **Standard**     | `NowPlayingActivity`     | Classic now-playing layout with album art, seekbar, and controls      |
| **Cover Flow**   | `CoverFlowActivity`      | 3D-style cover flow with swipeable album art and compact controls     |
| **Radial Wheel** | `RadialPlayerActivity`   | Circular dial-based player with radial seek and neon animations       |
| **VU Meter**     | `VuMeterPlayerActivity`  | Retro VU meter visualizer with analog-style level bars                |
| **Circular**     | `CircularPlayerActivity` | Ring-style progress with centered album art and circular controls     |

### Cyberpunk UI & Aesthetic UX

- **Neon Aesthetic**: High-contrast, tech-forward interface using "Neon Cyan", "Neon Pink", "Neon
  Green", "Neon Orange", "Neon Purple", "Neon Teal", "Neon Yellow", "Neon Red", and "Neon Blue"
  accents.
- **Card-Based Control Center**: All studio controls organized into themed cards (Volume, Pitch,
  Speed, A-B Repeat, Trim, Skip/Cut, Profiles, State Toggle), each with a unique stroke/border
  color.
- **Adaptive Theming**: Palette-based UI that morphs its colors and glow effects to match the
  current album art.
- **Orbitron Typography**: Futuristically styled fonts for a consistent, immersive sci-fi
  atmosphere.
- **State Persistence**: Visual indicators for manually edited songs and active playback states
  (Original vs Updated).
- **Edge-to-Edge Design**: Full-bleed layouts with proper system bar inset handling across all
  activities.

### Advanced Library Management

- **Smart Playlists**: Filter and organize your collection using criteria like "Smart", "Manual",
  "Has Songs", "Empty", and "Recent".
- **Deep Folder Integration**: Browse and play music directly from your file system with persistent
  URI permissions.
- **Global Search**: Find songs or playlists instantly with a high-performance search engine.
- **Cloud Sync**: Fetch missing high-resolution album art and metadata automatically via the
  MusicBrainz API.
- **Library Export**: Package your curated library and metadata into a single ZIP archive for backup
  or transfer.

### Additional Features

- **Foreground Service**: Background music playback with media notification controls (play, pause,
  skip, shuffle, repeat).
- **Boot Resume**: Automatically resumes playback after device reboot.
- **Onboarding Flow**: First-launch walkthrough introducing the app's core capabilities.
- **Splash Screen**: Animated branded splash with app icon on cold start.
- **Profile Switching**: Per-song playback profiles with instant switching from the player UI.
- **Destroy All States**: Nuclear reset button to clear all song metadata and start fresh.

---

## Tech Stack

| Category             | Technology                                               |
|----------------------|----------------------------------------------------------|
| **Language**         | Kotlin                                                   |
| **Architecture**     | MVVM + Repository Pattern                                |
| **UI Framework**     | Material 3, ViewBinding, ConstraintLayout, Custom Canvas |
| **Database**         | Room (SQLite) with KSP annotation processing             |
| **Audio Engine**     | Android Media3 (ExoPlayer), AudioEffect API              |
| **Concurrency**      | Kotlin Coroutines & Flow                                 |
| **Image Loading**    | Glide                                                    |
| **Networking**       | OkHttp                                                   |
| **Background Tasks** | WorkManager                                              |
| **Color Extraction** | Palette                                                  |
| **Build**            | Gradle, KSP                                              |

---

## Getting Started

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

## Permissions

| Permission                          | Purpose                                    |
|-------------------------------------|--------------------------------------------|
| `READ_MEDIA_AUDIO`                  | Access device music library (Android 13+)  |
| `READ_EXTERNAL_STORAGE`             | Legacy music access (Android 12 and below) |
| `FOREGROUND_SERVICE`                | Background playback                        |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Foreground service type (Android 14+)      |
| `WAKE_LOCK`                         | Keep CPU alive during playback             |
| `INTERNET`                          | MusicBrainz metadata fetching              |
| `ACCESS_NETWORK_STATE`              | Check connectivity before network calls    |
| `POST_NOTIFICATIONS`                | Show playback notification (Android 13+)   |
| `RECEIVE_BOOT_COMPLETED`            | Resume playback after reboot               |

---

## Quality & Security

- **Privacy First**: Respects Scoped Storage and uses standard MediaStore permissions where
  appropriate.
- **Performance Optimized**: Low-latency audio playback and efficient background processing to
  minimize battery impact.
- **Large Heap**: Enabled for high-resolution album art and waveform processing.
- **ProGuard**: Code shrinking and obfuscation enabled for release builds.
- **Safety First**: "Destroy All States" reset button for troubleshooting or starting fresh with
  song metadata.

---

*Developed by **Christopher Lee Cajes***
