# Claw Mikia - Android Music Player

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-orange.svg)](https://developer.android.com/about/versions/oreo/android-8.0.html)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-green.svg)](https://developer.android.com/about/versions/14)

**Claw Mikia** (also known as **Crazy Music Player** or **MusicVault**) is a feature-rich,
Android music player application designed to provide an exceptional audio experience on
Android.

## 🎵 Features

### Core Features

- **Local Music Playback** - Play music files stored on your device
- **Library Management** - Browse by songs, albums, artists, and folders
- **Favorites** - Mark and organize your favorite tracks
- **Dark Mode** - Easy on the eyes with system theme support
- **Background Playback** - Continue listening while using other apps
- **Notification Controls** - Full playback controls from notification panel

### Advanced Features

- **Pitch Control** - Adjust pitch from -6 to +6 semitones without affecting speed
- **Audio Trimming** - Set custom start/end points for songs
- **Online Metadata** - Auto-fetch album info and cover art from MusicBrainz & Cover Art Archive
- **Play Statistics** - Track play count and last played time
- **Repeat Modes** - Repeat none, repeat one, or repeat all
- **Folder Navigation** - Browse and play music by folder structure

### Platform-Specific

#### Android

- Material Design 3 UI
- Foreground service for reliable background playback
- Audio focus management
- Lock screen controls
- Splash screen with smooth transitions



## 📱 Screenshots

> *Screenshots coming soon!*

## 🚀 Getting Started

### Prerequisites

#### For Android Development

- Android Studio Hedgehog or later
- JDK 17
- Android SDK 26+ (for testing)
- Gradle 8.0+
- Kotlin 1.9+



### Installation

1. **Clone the repository**
   ```bash
      git clone https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-.git
   cd Claw-Mikia
   ```

2. **Build for Android**
   ```bash
      # Open in Android Studio and run, or use Gradle
   ./gradlew assembleDebug
   ```

            ```

## 📁 Project Structure

```
Claw-Mikia/
├── app/                          # Android application module
│   ├── src/main/
│   │   ├── java/com/musicvault/
│   │   │   ├── data/
│   │   │   │   ├── db/          # Room database
│   │   │   │   ├── model/       # Data models (Song, etc.)
│   │   │   │   └── repository/  # Data repositories
│   │   │   ├── service/         # MusicService (background playback)
│   │   │   ├── ui/
│   │   │   │   ├── activities/  # Main, NowPlaying, Splash activities
│   │   │   │   ├── adapters/    # RecyclerView adapters
│   │   │   │   ├── fragments/   # Library, Favorites, Folders fragments
│   │   │   │   └── viewmodels/  # ViewModel classes
│   │   │   └── utils/           # Utilities (MetadataFetcher, etc.)
│   │   └── res/                 # Android resources
│   └── build.gradle             # Android app configuration
│
├── MusicVault.WinForms/          # Windows Forms application
│   ├── Controls/                 # Custom UI controls
│   ├── Forms/                    # Application forms
│   ├── Models/                   # Data models
│   ├── Resources/                # Icons, images, fonts
│   ├── Services/                 # Business logic services
│   └── Utilities/                # Helper utilities
│
├── build.gradle                  # Root Gradle configuration
├── settings.gradle               # Gradle settings
└── README.md                     # This file
```

## 🛠️ Tech Stack

### Android

- **Language**: Kotlin
- **Architecture**: MVVM with Repository pattern
- **Database**: Room (SQLite)
- **Async**: Kotlin Coroutines & Flow
- **UI**: Android Views, ViewBinding, Material Design 3
- **Media**: MediaPlayer, MediaSession, Foreground Services
- **Image Loading**: Glide 4.16.0
- **Lifecycle**: AndroidX Lifecycle components
- **Build Tool**: Gradle with KSP

### Windows

- **Language**: C# 12.0
- **Framework**: .NET 10.0.103
- **UI**: Windows Forms (WinForms)
- **Architecture**: MVVM-ready structure
- **IDE**: Visual Studio 2022

## 🎯 Key Components

### MusicService (Android)

The heart of the Android app, handling:

- Audio playback with MediaPlayer
- MediaSession for system integration
- Foreground notifications
- Audio focus management
- Pitch shifting and audio trimming
- Playlist management with repeat modes

### MusicDatabase (Android)

Room database providing:

- Local storage for songs and metadata
- User preferences (favorites, customizations)
- Online metadata caching
- DAO interfaces for data access

### MetadataFetcher (Android)

Intelligent metadata fetching:

- Queries MusicBrainz API for recording info
- Fetches cover art from Cover Art Archive
- Rate-limited to respect API terms of service
- Offline-first approach with background updates

## 📊 Database Schema

### Song Entity

```kotlin
@Entity(tableName = "songs")
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val filePath: String,
    val folderPath: String,
    val folderName: String,
    val duration: Long,
    val fileSize: Long,
    val dateAdded: Long,

    // User customizations
    var pitchSemitones: Int = 0,    // -6 to +6
    var trimStart: Long = 0,        // ms
    var trimEnd: Long = -1,         // ms, -1 = use full duration
    var isFavorite: Boolean = false,
    var playCount: Int = 0,
    var lastPlayed: Long = 0,

    // Online metadata
    var albumName: String = "",
    var albumArtUrl: String = "",
    var metadataFetched: Boolean = false
)
```

## 🔧 Configuration

### Android Permissions

```xml

<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" /><uses-permission
android:name="android.permission.READ_MEDIA_AUDIO" /><uses-permission
android:name="android.permission.FOREGROUND_SERVICE" /><uses-permission
android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" /><uses-permission
android:name="android.permission.WAKE_LOCK" /><uses-permission
android:name="android.permission.INTERNET" /><uses-permission
android:name="android.permission.ACCESS_NETWORK_STATE" /><uses-permission
android:name="android.permission.POST_NOTIFICATIONS" />
```

### Build Configuration

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 34
- **JVM Target**: 17

## 🤝 Contributing

We welcome contributions! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- Follow Kotlin/C# coding conventions
- Write meaningful commit messages
- Add tests for new features
- Update documentation as needed

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [MusicBrainz](https://musicbrainz.org/) - Music metadata database
- [Cover Art Archive](https://coverartarchive.org/) - Album art database
- [AndroidX](https://developer.android.com/jetpack/androidx) - Android support libraries
- [Material Design](https://material.io/) - Design system

## 📞 Support

For issues, questions, or suggestions:

- Open an issue
  on [GitHub](https://github.com/Chris654Cajes/Claw-Mikia-Crazy-Music-Player-Android-/issues)
- Contact the maintainers

## 🗺️ Roadmap

- [ ] **Cross-platform sync** - Sync playlists and preferences between devices
- [ ] **Equalizer** - Built-in audio equalizer with presets
- [ ] **Sleep timer** - Auto-stop playback after set time
- [ ] **Car mode** - Simplified UI for driving
- [ ] **Widgets** - Home screen widgets for quick access
- [ ] **Gapless playback** - Seamless transitions between tracks
- [ ] **Lyrics support** - Display synchronized lyrics
- [ ] **Multi-room audio** - Stream to multiple devices

---

**Made with ❤️ by the Claw Mikia Team**

*Music is the soundtrack of your life.*
