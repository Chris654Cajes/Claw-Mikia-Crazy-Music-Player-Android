# ClawMikia — Patch Changelog

## Fix 1 — Now Playing Button Alignment (All Control Sections)

### Problem

In the Volume, Pitch/Key, Playback Speed, and Timeline Trim cards the button row
was a `wrap_content`-width `LinearLayout` anchored to the left edge. The Reset
button used a different height (`32dp`) from the ± buttons and had `layout_gravity`
set to `"end"`, which is not honoured inside a horizontal `LinearLayout`. Result:
the Reset button was visually disconnected (different height, wrong X-position,
unrelated to the seekbar or value label above it).

### Fix (`activity_now_playing.xml`)

* Every button-row `LinearLayout` is now `match_parent` width with `gravity="center_vertical"`.
* All step buttons (−, +) and Reset buttons share a uniform `36dp` height.
* `minWidth="0dp"` and `minHeight="0dp"` remove Material Button's minimum-touch
  implicit padding that was stretching the height beyond `36dp`.
* `gravity="center"` added to every Button so the text sits perfectly centred.
* A `<View weight="1">` spacer between the ± pair and the Reset button pushes Reset
  flush-right, mirroring the numeric value label in the header row.
* `layout_marginStart="10dp"` between the two step buttons for consistent gutter.
* All four sections (Volume, Pitch, Speed, Trim) receive identical treatment.

### Additional UI Changes

* **Shuffle button** added to the header row (between the title and the Favourite
  button) — it was wired in the Kotlin but had no XML view.
* **Sleep Timer button** added as a fourth tile in the Audio Features card so the
  `btnSleepTimer` wiring in the Activity compiles correctly.
* Volume button row uses `ImageButton` (`36dp × 36dp`) for ± to match the icon-only
  nature of volume step controls, keeping consistent icon padding (`7dp`).

---

## Fix 2 — Now Playing State Persistence / Song-Change Race Condition

### Problem

When a new song started (via Next/Prev, tap-to-play, or end-of-track auto-advance)
the UI partially reset:

* `currentRepeatMode` was never re-synced from the service for the new song.
* The shuffle button tint never updated.
* The ViewModel (`NowPlayingViewModel`) was never initialised or told about the new
  song, so Equalizer/Lyrics/Profiles fragments opened with stale data.
* The DB reload in `onSongChanged` could overwrite live service state with stale data
  if the coroutine resolved out of order.
* Progress bar, play/pause icon, trim seekbars, and pitch/speed sliders could show
  values for the previous song for up to one polling cycle.

### Fix (`NowPlayingActivity.kt`)

* `registerCallbacks()` now calls `currentRepeatMode = svc.getRepeatMode()` and
  `updateRepeatButton()` / `updateShuffleButton()` immediately when a song changes.
* `NowPlayingViewModel` is initialised in `onCreate` via `ViewModelProvider` so all
  bottom-sheet fragments share the correct ViewModel scope.
* `syncNow()` and `onSongChanged` both call `viewModel.setSong(fresh, isPlaying)` so
  Equalizer, Lyrics, and Profiles panels always reflect the current song.
* `populate()` is documented as idempotent — it is safe to call twice (once from the
  service callback with the in-memory Song, once from the DB coroutine with the fresh
  persisted Song).
* Play/pause icon, seekbar, and all control values are set atomically inside
  `populate()`, eliminating partial-state frames.
* `startProgressUpdates()` is called after `populate()` completes so the seekbar
  cannot jump backwards.
* The lyrics sync ticker (`viewModel.onLyricsPositionChanged`) is called from inside
  the 500 ms progress-update loop, keeping lyrics highlighting alive across song
  changes.

---

## Fix 3 — Non-Working Buttons / Functionalities

### `btnShuffle` — New, fully wired

* Added `ImageButton` `@+id/btnShuffle` to the XML header row.
* `setupControls()` attaches a click listener that calls `musicService?.toggleShuffle()`
  and immediately updates the button tint/icon via `updateShuffleButton()`.
* `updateShuffleButton()` uses `ic_shuffle_on` (neon cyan) when shuffle is active and
  `ic_shuffle` (text_hint) when inactive — consistent with the repeat-button style.
* `updateShuffleButton()` is also called in `onServiceConnected` so the icon is correct
  when reopening the screen mid-session.

### `btnSleepTimer` — New, fully wired

* Added `ImageButton` `@+id/btnSleepTimer` to the Audio Features grid in the XML.
* `setupControls()` shows `SleepTimerFragment` via `showFeatureFragment()`, passing
  live lambdas to `setSleepTimer`, `cancelSleepTimer`, and `getSleepTimerRemainingMs`
  on the service.

### `btnProfiles` — `onProfileActivated` callback connected

* The `ProfilesFragment` now receives
  `onProfileActivated = { musicService?.reloadActiveProfile() }`.
* Previously this callback was null, so activating a profile had no audible effect
  until the next song change.

### `btnEqualizer` — callback lifecycle fixed

* The `onApplyCallback` now captures `musicService` in a local val before the lambda
  so it cannot NPE if the service unbinds between fragment open and user interaction.

### `LyricsFragment` — Save callbacks fully implemented

* `showEditLyricsDialog()` previously passed empty lambdas (`{ }`) to both
  `onSaveLRC` and `onSavePlain` — nothing was written to the database.
* Both callbacks now call `saveLyrics(songId, text, synced)` which:
    1. Deletes existing lyrics meta + lines for the song (via DAO).
    2. Parses the text (LRC timestamps if `synced=true`, plain lines otherwise).
    3. Inserts the new `LyricsMeta` and `LyricLine` rows.
    4. Triggers `viewModel.setSong(...)` so the RecyclerView updates without
       requiring the sheet to be closed and reopened.
* A self-contained `parseLrc()` helper handles `[mm:ss.xx]` and `[mm:ss.xxx]`
  timestamps; centisecond and millisecond variants are both normalised to ms.
* `btnFetchLyrics` and `btnAddLyrics` both delegate to `showEditLyricsDialog()`
  so all three entry-points are functional.

### `btnTrimStart` / `btnTrimEnd` — Position calculation corrected

* The old implementation added `+ 10000` (10 s) to the position, which made "Set
  Start" always push the start 10 s ahead of the playhead — confusing.
* Corrected to: `newStart = (currentPosition - trimOffset).coerceAtLeast(0)` where
  `trimOffset` is the song's stored `trimStart`, giving the real playhead position
  relative to the trim window.

### `showFeatureFragment()` — Tag collision fixed

* Was calling `fragment.show(supportFragmentManager, fragment.tag)` where `tag`
  is `null` until the fragment is attached. Using the same null tag twice causes
  an `IllegalStateException`.
* Fixed to use `fragment::class.java.simpleName` as the tag.

---

## Files Modified

| File                                  | Change                                                |
|---------------------------------------|-------------------------------------------------------|
| `res/layout/activity_now_playing.xml` | Fix 1 (alignment) + new Shuffle & Sleep Timer buttons |
| `ui/activities/NowPlayingActivity.kt` | Fix 2 (state persistence) + Fix 3 (all wiring)        |
| `ui/fragments/LyricsFragment.kt`      | Fix 3 (save callbacks fully implemented)              |

No other files were modified. All existing themes, colors, drawables, adapters,
repositories, services, and database schema are preserved exactly.
