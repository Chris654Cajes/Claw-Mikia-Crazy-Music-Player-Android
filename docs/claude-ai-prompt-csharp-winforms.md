# Claude AI Prompt for C# WinForms Development

## Ready-to-Use Prompt

Copy and paste this complete prompt directly into Claude AI to develop the C# WinForms version of
Claw Mikia:

---

**Create a C# WinForms music player application called "Claw Mikia" with these exact specifications:
**

**TECHNICAL REQUIREMENTS:**

- Target: .NET 10.0.103 with C# 12.0
- UI: Windows Forms with modern dark/light theme support
- Audio: NAudio library for playback and effects
- Database: Entity Framework Core with SQLite
- Architecture: MVVM pattern with dependency injection

**PROJECT STRUCTURE TO CREATE:**

```
MusicVault.WinForms/
├── Models/
│   ├── Song.cs
│   ├── Playlist.cs
│   └── UserSettings.cs
├── Views/
│   ├── MainForm.cs
│   ├── NowPlayingForm.cs
│   └── SettingsForm.cs
├── ViewModels/
│   ├── MainViewModel.cs
│   └── PlayerViewModel.cs
├── Services/
│   ├── AudioEngine.cs
│   ├── DatabaseService.cs
│   └── MetadataService.cs
├── Controls/
│   ├── ModernButton.cs
│   ├── VolumeSlider.cs
│   └── PlaylistControl.cs
└── Utilities/
    ├── ThemeManager.cs
    └── FileHelper.cs
```

**CORE FEATURES TO IMPLEMENT:**

1. **Audio Playback Engine:**
    - Support MP3, FLAC, WAV, M4A, AAC formats
    - Pitch shifting (-6 to +6 semitones)
    - Tempo control (0.5x to 2.0x speed)
    - Audio trimming with custom start/end points
    - Volume normalization
    - Cross-fade between tracks

2. **Modern UI Interface:**
    - Custom title bar with window controls
    - Dark/light theme switching with smooth transitions
    - Responsive layout that adapts to different screen sizes
    - High DPI support (150%, 200%) with crisp rendering
    - System tray integration with minimize to tray
    - Taskbar media controls (Windows 10/11)
    - Clean, minimalist design with thoughtful spacing
    - Smooth animations and micro-interactions
    - Accessibility support with proper contrast ratios

3. **Music Library Management:**
    - Folder scanning and file importing
    - Metadata extraction (ID3 tags)
    - Search and filter capabilities
    - Playlist creation and management
    - Favorites system
    - Play statistics tracking

4. **Advanced Features:**
    - 10-band equalizer with presets
    - Gapless playback
    - Global hotkeys support
    - Drag-and-drop files/folders
    - Sleep timer
    - Auto-start with Windows option

**DATABASE SCHEMA TO IMPLEMENT:**

```sql
CREATE TABLE Songs (
    Id INTEGER PRIMARY KEY,
    Title TEXT NOT NULL,
    Artist TEXT NOT NULL,
    Album TEXT,
    FilePath TEXT UNIQUE NOT NULL,
    Duration INTEGER,
    FileSize INTEGER,
    DateAdded INTEGER,
    PlayCount INTEGER DEFAULT 0,
    LastPlayed INTEGER,
    IsFavorite BOOLEAN DEFAULT 0,
    PitchSemitones INTEGER DEFAULT 0,
    TrimStart INTEGER DEFAULT 0,
    TrimEnd INTEGER DEFAULT -1,
    AlbumArtPath TEXT
);

CREATE TABLE Playlists (
    Id INTEGER PRIMARY KEY,
    Name TEXT NOT NULL,
    Description TEXT,
    DateCreated INTEGER,
    IsSystem BOOLEAN DEFAULT 0
);

CREATE TABLE PlaylistSongs (
    PlaylistId INTEGER,
    SongId INTEGER,
    Position INTEGER,
    PRIMARY KEY (PlaylistId, SongId)
);
```

**IMPLEMENTATION STEPS:**

1. Create the solution structure with all folders
2. Set up Entity Framework Core with SQLite
3. Implement the AudioEngine class using NAudio
4. Create the main UI with custom controls
5. Add theme management system
6. Implement playlist and library management
7. Add advanced audio features
8. Integrate Windows system features
9. Test with various audio formats
10. Optimize performance and memory usage

**CODE QUALITY REQUIREMENTS:**

- Use async/await for all I/O operations
- Implement proper error handling and logging
- Follow C# naming conventions
- Add XML documentation for public APIs
- Use dependency injection for services
- Implement IDisposable for audio resources

**DELIVERABLES:**

- Complete Visual Studio solution file
- All source code files with proper implementation
- Working application with all features functional
- Basic unit tests for core services
- README with build instructions

**DO NOT:**

- Ask about platform choice (Windows is specified)
- Request clarification on basic requirements
- Suggest alternative frameworks or libraries
- Create placeholder or incomplete implementations

**START IMMEDIATELY** with creating the project structure and implement all features as specified
above.

---

## Additional Specific Prompts

### For Audio Engine Issues:

```
Fix the AudioEngine class in Claw Mikia WinForms. The pitch shifting is causing audio glitches. Implement smooth pitch shifting using NAudio's WaveOutEvent with proper buffering. Ensure thread safety and prevent audio dropouts during pitch changes.
```

### For UI Theme Problems:

```
Implement a complete theme system for Claw Mikia WinForms. Create ThemeManager class that handles dark/light theme switching. Update all forms to use custom colors that respond to theme changes. Ensure smooth transitions and proper color contrast for accessibility.
```

### For Database Performance:

```
Optimize the Entity Framework Core queries in Claw Mikia WinForms. The library loading is slow with 10,000+ songs. Implement lazy loading, add proper indexes, and use compiled queries. Add background scanning that doesn't block the UI.
```

### For System Integration:

```
Add Windows 11 taskbar media controls to Claw Mikia WinForms. Implement the Windows.Media.SystemMediaTransportControls API. Show current track info, playback controls, and progress bar. Handle media key events globally.
```

## Quick Copy-Paste Prompts

**Basic Setup:**

```
Create a C# WinForms music player project with NAudio, Entity Framework Core SQLite, and modern UI. Implement basic audio playback, playlist management, and dark theme support.
```

**Audio Features:**

```
Add pitch shifting, tempo control, and audio trimming to the existing AudioEngine. Use NAudio's WaveStream processing with real-time effects. Ensure smooth audio processing without glitches.
```

**UI Enhancements:**

```
Redesign the main form with modern controls, smooth animations, and high DPI support. Add custom drawn controls for volume slider, progress bar, and playlist view. Implement hover effects and smooth transitions. Focus on clean aesthetics with proper visual hierarchy and intuitive user experience. Use consistent spacing, modern typography, and subtle shadows for depth. Ensure all UI elements are responsive and adapt gracefully to different window sizes.
```

**Database Integration:**

```
Set up Entity Framework Core with SQLite for music library. Create Song, Playlist, and UserSettings entities. Implement repository pattern with async operations for database access.
```

---

*Use these prompts directly without modification for best results with Claude AI.*

```
Create a C# WinForms application for Claw Mikia music player with the following specifications:

- Target .NET 10.0.103
- Use C# 12.0 features
- Implement MVVM-ready architecture
- Include Entity Framework Core with SQLite
- Integrate NAudio for audio processing
- Support dark/light theme switching
- Include modern UI controls and smooth animations

Project structure should include:
- Models/ (Data models)
- Views/ (WinForms)
- ViewModels/ (MVVM support)
- Services/ (Business logic)
- Controls/ (Custom UI controls)
- Utilities/ (Helper classes)
- Resources/ (Icons, images, fonts)

Ensure the solution follows best practices for WinForms development with proper separation of concerns.
```

### 2. Audio Engine Development

```
Implement an AudioEngine class using NAudio with the following capabilities:

- Audio playback for MP3, FLAC, WAV, M4A, AAC formats
- Pitch shifting from -6 to +6 semitones without affecting speed
- Tempo control (speed adjustment)
- Audio trimming with custom start/end points
- Volume normalization
- Cross-fade between tracks
- Real-time audio visualization (waveform, spectrum)
- Gapless playback support
- Audio effects (reverb, echo, equalizer)

The AudioEngine should:
- Use event-driven architecture for state changes
- Support background playback
- Handle audio device management
- Provide error handling and recovery
- Include comprehensive logging

Thread safety is critical - ensure all audio operations are properly synchronized.
```

### 3. Database and Data Models

```
Design and implement Entity Framework Core models for Claw Mikia with SQLite:

Create the following entities:
- Song (ID, Title, Artist, Album, FilePath, Duration, FileSize, DateAdded, PlayCount, LastPlayed, IsFavorite, PitchSemitones, TrimStart, TrimEnd, AlbumArtPath)
- Playlist (ID, Name, Description, DateCreated, IsSystem)
- PlaylistSong (PlaylistID, SongID, Position)
- UserSettings (Key, Value, Category)
- AlbumArt (ID, AlbumName, ArtPath, Source)

Implement:
- DbContext with proper relationships
- Repository pattern for data access
- Database migrations support
- Data validation and error handling
- Async operations for database queries
- Caching layer for frequently accessed data

Ensure the database schema supports:
- Fast searching and filtering
- Metadata indexing
- Cross-platform compatibility with Android version
```

### 4. Modern UI Development

```
Create a modern WinForms UI for Claw Mikia with the following requirements:

Main Features:
- Dark/light theme support with smooth transitions and color consistency
- Custom title bar with window controls that match the theme
- Responsive layout that adapts to window resizing and different screen sizes
- Modern controls with hover effects, smooth animations, and micro-interactions
- Keyboard shortcuts and global hotkeys with visual feedback
- Drag-and-drop support for files and folders with clear visual indicators
- System tray integration with minimize to tray and context menu
- Taskbar media controls (Windows 10/11) with thumbnail preview

Key Forms:
- MainForm: Main interface with playlist, player controls, library browser
- NowPlayingForm: Full-screen or floating now playing view with album art
- SettingsForm: Application settings and preferences with categorized options
- EqualizerForm: Audio equalizer with presets and visual feedback
- TagEditorForm: Metadata editing interface with validation

UI Requirements:
- Use custom drawn controls for modern appearance with consistent styling
- Implement smooth animations and transitions with proper timing
- Support high DPI displays (150%, 200%, etc.) with crisp rendering
- Include loading states and progress indicators with animations
- Provide accessibility support (screen readers, keyboard navigation)
- Follow Windows 11 design guidelines where applicable for modern look
- Maintain consistent color palette throughout the application
- Use proper typography hierarchy for better readability
- Implement responsive design patterns for different window sizes
- Add subtle shadows and depth for visual interest without clutter

Use GDI+ for custom drawing and consider Windows API integration for advanced features. Focus on creating a clean, professional interface that prioritizes usability while maintaining visual appeal.
```

### 5. Media Integration

```
Implement Windows media integration features:

Taskbar Integration:
- Windows 7+ taskbar thumbnail toolbar
- Windows 10/11 taskbar media controls
- Progress bar in taskbar button
- Overlay icons for playback state

System Integration:
- Windows shell extensions for "Play with MusicVault"
- Auto-start with Windows option
- File type associations (.mp3, .flac, .wav, .m4a, .aac)
- Default music player registration

Media Session:
- Implement Windows Media Session APIs
- Support system media transport controls
- Handle media key events (Play/Pause, Next, Previous)
- Display current track info in volume mixer

Global Hotkeys:
- Configurable global hotkeys
- Support Play/Pause, Next, Previous, Volume Up/Down
- Handle hotkey conflicts with other applications
- Provide hotkey registration and unregistration

Ensure proper cleanup and resource management for all system integrations.
```

### 6. Online Services Integration

```
Implement online services for metadata fetching:

MusicBrainz Integration:
- Query MusicBrainz API for recording information
- Fetch album details and track listings
- Handle rate limiting and API quotas
- Implement caching for API responses
- Provide fallback for network failures

Cover Art Archive:
- Fetch album art from Cover Art Archive
- Support multiple image sizes and formats
- Implement local caching of cover art
- Handle missing or corrupt images gracefully
- Provide fallback to embedded album art

Additional Services:
- Last.fm scrobbling support
- Spotify integration for playlist import/export
- Apple Music integration (if feasible)
- Lyrics fetching from third-party services

Requirements:
- Async/await patterns for network operations
- Proper error handling and retry logic
- User consent for online features
- Offline-first approach with background updates
- Respect API terms of service and rate limits
```

### 7. Advanced Features Implementation

```
Implement advanced features for Claw Mikia:

Audio Effects:
- 10-band equalizer with presets (Rock, Pop, Jazz, Classical, etc.)
- Bass boost and treble enhancement
- Virtual surround sound simulation
- Loudness normalization (EBU R128)
- Audio compression and limiting

Playlist Management:
- Smart playlists based on criteria (genre, year, rating)
- Auto-generated playlists (Most Played, Recently Added)
- Playlist import/export (M3U, PLS, XSPF)
- Folder watching for automatic library updates
- Duplicate track detection and management

User Experience:
- Gapless playback between tracks
- Cross-fade with configurable duration
- Sleep timer with gradual volume fade
- Bookmarking positions within long tracks
- Playback history with undo functionality

Performance:
- Lazy loading for large music libraries
- Background scanning and metadata processing
- Memory-efficient audio buffering
- Multi-threaded UI updates
- Responsive UI during intensive operations

Ensure all features are properly tested and include comprehensive error handling.
```

## Development Guidelines

### Code Quality Standards

```
When writing code for Claw Mikia WinForms, follow these guidelines:

1. Code Style:
   - Use C# 12.0 features appropriately (file-scoped namespaces, primary constructors)
   - Follow Microsoft C# coding conventions
   - Use meaningful variable and method names
   - Add XML documentation for public APIs
   - Keep methods focused and under 50 lines when possible

2. Architecture:
   - Implement MVVM pattern where applicable
   - Use dependency injection for services
   - Separate UI logic from business logic
   - Implement proper error handling and logging
   - Use async/await for I/O operations

3. Performance:
   - Profile performance bottlenecks
   - Use object pooling for frequently created objects
   - Implement proper disposal of resources (IDisposable)
   - Avoid blocking the UI thread
   - Use efficient data structures and algorithms

4. Testing:
   - Write unit tests for business logic
   - Implement integration tests for database operations
   - Test UI interactions with automation frameworks
   - Include performance benchmarks
   - Test on different Windows versions and DPI settings

5. Security:
   - Validate all user inputs
   - Use secure file handling practices
   - Implement proper error messages without exposing internals
   - Follow principle of least privilege
   - Keep dependencies updated and secure
```

### Debugging and Troubleshooting

```
For debugging Claw Mikia WinForms issues:

Audio Problems:
- Check NAudio initialization and device selection
- Verify audio format compatibility
- Test with different audio files
- Check for threading issues in audio callbacks
- Monitor memory usage during playback

UI Issues:
- Verify DPI scaling on different displays
- Test with Windows high contrast themes
- Check for GDI resource leaks
- Validate control z-order and focus management
- Test window state changes (minimize, maximize, restore)

Database Issues:
- Verify SQLite file permissions
- Check for database corruption
- Test migration scripts
- Monitor connection pooling
- Validate entity relationships

Performance Issues:
- Profile with Visual Studio Diagnostic Tools
- Check for memory leaks
- Monitor CPU usage during audio processing
- Test with large music libraries (>10,000 tracks)
- Verify UI responsiveness during background operations

Always include detailed logging and provide clear error messages to users.
```

## Testing Prompts

```
Create comprehensive tests for Claw Mikia WinForms:

Unit Tests:
- AudioEngine playback controls and state management
- Database operations (CRUD, queries, migrations)
- Metadata parsing and validation
- Playlist management logic
- Settings persistence and retrieval

Integration Tests:
- End-to-end audio playback pipeline
- Database integration with Entity Framework
- Online services API integration
- File system operations and permissions
- Windows shell integration

UI Tests:
- Form initialization and disposal
- Control event handling and validation
- Theme switching and UI updates
- Keyboard shortcuts and hotkeys
- Drag-and-drop operations

Performance Tests:
- Library scanning with large file collections
- Memory usage during extended playback
- UI responsiveness with background operations
- Audio processing CPU usage
- Database query performance

Test Coverage Goals:
- Minimum 80% code coverage for business logic
- All public APIs tested
- Critical user flows covered
- Error conditions and edge cases tested
- Performance benchmarks established
```

## Deployment and Distribution

```
Prepare Claw Mikia WinForms for distribution:

Build Configuration:
- Create Release build with optimizations
- Configure ClickOnce or MSI installer
- Set up automatic update mechanism
- Include prerequisites (.NET runtime, Visual C++ redistributables)
- Configure code signing for security

Packaging:
- Create installer with custom UI
- Include uninstaller with clean removal
- Bundle required audio codecs if needed
- Configure file associations during installation
- Set up desktop and Start menu shortcuts

Distribution:
- Prepare GitHub releases with changelog
- Configure auto-update server
- Create portable version for USB drives
- Set up telemetry and crash reporting (opt-in)
- Prepare documentation and help files

Requirements:
- Test on clean Windows installations
- Verify antivirus compatibility
- Test upgrade scenarios from previous versions
- Validate installation on different Windows versions
- Ensure proper cleanup on uninstall
```

## Maintenance and Updates

```
Plan for ongoing maintenance of Claw Mikia WinForms:

Regular Updates:
- Monthly security patches for dependencies
- Quarterly feature updates and improvements
- Annual major version with new capabilities
- Bug fixes and performance optimizations
- User feedback incorporation

Monitoring:
- Crash reporting and error analytics
- Usage statistics and feature adoption
- Performance metrics and benchmarks
- User satisfaction surveys
- Competitive analysis and market trends

Support:
- User documentation and help system
- FAQ and troubleshooting guides
- Community forums and feedback channels
- Issue tracking and resolution process
- Version compatibility matrix

Long-term Roadmap:
- Cross-platform sync with mobile versions
- Cloud storage integration
- Advanced audio processing features
- AI-powered music recommendations
- Streaming service integration
```

## Usage Instructions

1. **Copy and paste** the appropriate prompt into Claude AI
2. **Modify** the prompt to match your specific requirements
3. **Review** the generated code for quality and correctness
4. **Test** the implementation thoroughly before integration
5. **Iterate** with Claude AI to refine and improve the code

## Tips for Best Results

- **Be specific** about requirements and constraints
- **Provide context** about existing code and architecture
- **Request incremental changes** rather than complete rewrites
- **Ask for explanations** of complex logic
- **Verify compatibility** with existing codebase
- **Test thoroughly** before merging changes

---

*This prompt guide is designed to help developers leverage AI assistance effectively while
maintaining code quality and architectural consistency across the Claw Mikia project.*
