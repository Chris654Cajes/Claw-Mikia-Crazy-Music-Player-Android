# MusicVault (Claw Mikia) - Android Music Player

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange.svg)](https://developer.android.com/about/versions/oreo/android-8.0.html)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-green.svg)](https://developer.android.com/about/versions/15)

**MusicVault** (Claw Mikia) is a modern, high-performance music player for Android, designed with a
focus on audio quality and user experience. It features advanced audio controls like pitch shifting,
playback speed adjustment, and a built-in equalizer.

## 🎵 Features

### Core Features

- **Local Music Library** - Automatically scans and organizes your music by song, artist, album, and
  folder.
- **Dynamic Theming** - UI colors adapt to the currently playing album art using the Palette API.
- **Advanced Audio Controls** - Pitch shifting (-6 to +6 semitones), speed control (0.5x to 2.0x),
  and audio trimming.
- **Lyrics Support** - View and edit lyrics directly within the app.
- **Playlists & Favorites** - Create custom playlists and quickly mark tracks as favorites.
- **Smart Metadata** - Fetches missing album art and metadata from MusicBrainz and Cover Art
  Archive.

### User Experience

- **Material Design 3** - A clean, modern interface following the latest Android design guidelines.
- **Seamless Background Playback** - Reliable playback with foreground services and media session
  integration.
- **Notification Controls** - Control your music from the lock screen and notification shade.
- **Sleep Timer** - Automatically stop music after a set duration.
- **Profile Management** - Save and switch between different audio profiles and settings.

## 🚀 Getting Started

### Prerequisites

- Android Studio Ladybug or later
- JDK 17
- Android SDK 35+

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and run the `app` module.

## 🛠️ Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Architecture**: MVVM with Repository Pattern
- **UI**: ViewBinding, Material 3, Palette API, ViewPager2
- **Database**: Room Persistence Library
- **Asynchrony**: Coroutines & Flow
- **Network**: OkHttp 4.x
- **Image Loading**: Glide 4.x
- **Dependency Injection**: Manual / Simple Singleton Pattern

## 🛡️ Security & Quality

We prioritize security and stability by using only official and well-maintained libraries:

- **AndroidX & Google**: Core app functionality and UI components.
- **JetBrains**: Kotlin standard library and coroutines.
- **Square (OkHttp)**: Reliable and secure networking.
- **Glide**: Efficient and safe image processing.

All dependencies are regularly audited for security vulnerabilities and performance issues.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
*Music is the soundtrack of your life. Enjoy it with MusicVault.*
