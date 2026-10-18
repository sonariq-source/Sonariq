# UX Feature Batch — Integration Manifest (Phase 2)

All files below are NEW (uncommitted). Phase 2 wires them in. No existing
file was edited. No third-party brand names, no upstream-project references,
no personal names anywhere in code, comments, or strings.

New strings: `app/src/main/res/values/strings_ux.xml` (only new strings file
touched; `strings.xml` untouched).

---

## 1. AppSettings keys to add (Phase 2)

Add to `app/src/main/java/com/music/sonariq/data/settings/AppSettings.kt`,
following the existing pattern for each key: `val <name> =
MutableStateFlow(<default>)`, a `private const val KEY_<NAME> = "<name>"`,
a `readAll()` line, and a `fun set<Name>(value: T)` setter that writes the
flow and `prefs.edit()`.

| Key name | Type | Default | Used by |
|---|---|---|---|
| `homeSectionOrderJson` | String | `""` | `ui/home/HomeSectionRanking.kt` (HomeSectionOrderStore) |
| `homeSectionLearningEnabled` | Boolean | `true` | Settings toggle; passed as `learn` to `rankSections` |
| `savedQueuesJson` | String | `""` | `playback/SavedQueues.kt` (SavedQueueStore) |
| `tvAmbient2Enabled` | Boolean | `false` | TvActivity ambient branch; `tv/TvAmbient2.kt` |
| `artworkGesturesEnabled` | Boolean | `true` | Now Playing gesture layer; `ui/player/PlayerArtworkGestures.kt` |
| `privateSessionEnabled` | Boolean | `false` | `data/scrobbling/PrivateSession.kt` |

New code references these as `AppSettings.<keyName>` and reads `.value` /
collects the flow. Nothing else in AppSettings changes.

Also call once at startup (e.g. in `SonariqApplication.onCreate`, after
`AppSettings.init`): `HomeSectionOrderStore.load()`,
`SavedQueueStore.load()`, `PrivateSession.load()`.

---

## 2. User-visible entry points (Phase 2 placement)

1. **Home learning** — Settings → Appearance group (`SettingsSheet.kt`,
   next to the other appearance rows): two rows —
   - Switch row `ux_home_learn_title` / `ux_home_learn_subtitle` →
     `AppSettings.homeSectionLearningEnabled`
   - Row `ux_home_reorder_title` / `ux_home_reorder_subtitle` → opens
     `SectionReorderSheet` (needs the current `List<HomeShelf>` from
     `MainViewModel.home`).
   - Optional: `HomeShelfDragAffordance(onOpenReorder)` at the trailing end
     of each home shelf header — the only additive UI on the home screen.
2. **Queue 2.0** — a "Queues" button in the player queue header, next to
   `AutoplayToggleButton` in `InlineQueue`
   (`sharedUi/.../ui/player/PlayerQueue.kt`, ~line 276 header Row). Opens
   `QueueManagerSheet(queue, currentIndex, onJumpTo, onMoveInQueue,
   onRemoveFromQueue, onPlayTracks, onDismiss, onDragActiveChange)` with the
   same callbacks the inline queue already receives. `onPlayTracks` starts
   playback of a track list (the context-menu play path).
3. **TV ambient 2.0** — phone Settings → the Cast/TV group where
   `cast_tv_lyrics` lives (`SettingsSheet.kt` ~line 2139): switch row
   `ux_tv_ambient2_title` / `ux_tv_ambient2_subtitle` →
   `AppSettings.tvAmbient2Enabled`. In `TvActivity.kt`'s ambient branch,
   `if (AppSettings.tvAmbient2Enabled.value) TvAmbient2Overlay(track,
   controller) else TvAmbientOverlay(track, controller)`.
4. **Gestures** — Settings → Playback group (`SettingsSheet.kt` ~line 761):
   switch row `ux_gestures_title` / `ux_gestures_subtitle` →
   `AppSettings.artworkGesturesEnabled`.
5. **Android Auto** — no settings entry; it replaces the browse tree served
   to the car (see §3). User-visible the moment a car connects.
6. **Private session** — `AccountAndScrobblingScreen.kt` (the scrobbling
   screen), below the existing scrobbling switches: switch row
   `ux_private_session_title` / `ux_private_session_subtitle` →
   `PrivateSession.setActive(...)`, collecting `PrivateSession.active`.

---

## 3. Minimal edits to existing files (Phase 2)

### A. Home — sort shelves before display
File: `app/src/main/java/com/music/sonariq/ui/MainViewModel.kt`
Anchor: `publishHomeShelves()` (~line 1650), the single funnel all home
shelf publications go through.
Edit: sort before publishing —
```kotlin
private fun publishHomeShelves(shelves: List<HomeShelf>, prepend: Boolean = false) {
    val ordered = HomeSectionRanking.rankSections(
        shelves = shelves,
        recentArtists = recentTopArtistNames(),   // from ListeningStats.summary; empty set if unavailable
        manualOrder = HomeSectionOrderStore.order.value,
        learn = AppSettings.homeSectionLearningEnabled.value,
    )
    // ... existing publish logic, using `ordered` instead of `shelves`
}
```
Nothing in `HomeScreen.kt` (sharedUi) changes — order-only, as required.

### B. Queue 2.0 — feed the history log
File: `app/src/main/java/com/music/sonariq/playback/PlaybackService.kt`
Anchor: the track-change handler (where the current `Song` is resolved for
the new media item; near the progress sampler at ~line 5486).
Edit: `QueueHistoryLog.notePlayed(song)` on every track change to a new
`videoId`.

### C. TV ambient 2.0 — style switch
File: `app/src/tv/java/com/music/sonariq/tv/TvActivity.kt`
Anchor: the `showAmbient` branch that composes `TvAmbientOverlay`.
Edit: branch on `AppSettings.tvAmbient2Enabled.value` (collected as state)
between `TvAmbient2Overlay` and `TvAmbientOverlay`.

### D. Gestures — layer the double-tap detector
File: `sharedUi/src/jvmSharedMain/kotlin/com/music/sonariq/ui/player/NowPlayingScreen.kt`
Anchor: the portrait sleeve modifier chain ending `.then(skipSwipeGesture)`
(~line 2295).
Edit: append `.artworkDoubleTapSeek(AppSettings.artworkGesturesEnabled.value) { delta ->
    onSeek((position.nowMs + delta).coerceAtLeast(0)) }`
after `skipSwipeGesture`. `skipSwipeGesture` itself is untouched — swipe for
next/previous keeps working exactly as before. `position` is the
`PlaybackPosition` param already in scope; use whatever accessor the host
uses for the current playhead.
(Landscape sleeve at ~line 1821 may get the same one-line append; optional.)

### E. Android Auto — serve the new tree
File: `app/src/main/java/com/music/sonariq/playback/PlaybackService.kt`
Anchor: `MediaLibraryCallback.onGetChildren` (~line 7521) and the
`MEDIA_*_ID` constants (~line 8233).
Edit:
- `onGetLibraryRoot`: keep, or point at the new root (both work; the new
  tree uses its own `auto2:` ids).
- `onGetChildren`: for `MEDIA_ROOT_ID` return
  `AutoBrowseTree.topLevelItems(titles)` with localized titles from
  resources; for any id starting with `"auto2:"` return
  `AutoBrowseTree.children(parentId, autoDataSource, page, pageSize)`.
- Implement `AutoBrowseTree.AutoDataSource` on the service using the
  existing snapshot helpers (`snapshotQuickPicks()`, `snapshotRecents()`,
  `snapshotLiked()`, download store, local library, cached home-feed mood
  shelves, playlist list). `NOW_PLAYING` children: return the live queue as
  playable items (or an info row when empty).
- Keep the old `MEDIA_*_ID` branches or remove them; old car head-units
  with cached ids get empty folders either way (unknown ids → empty list).

### F. Private session — gate the three writers
File: `app/src/main/java/com/music/sonariq/playback/PlaybackService.kt`
1. ~line 5486 (`ListeningRecorder.onSample(it, player.duration)`): wrap —
   `if (!PrivateSession.suspendsRecording()) ListeningRecorder.onSample(...)`.
2. ~lines 6601, 6611, 6689 (`ListenBrainzManager.submitPlayingNow` /
   `submitFinished`): skip when `PrivateSession.suspendsRecording()`.
3. Where the service observes settings (or in the new toggle handler):
   when `privateSessionEnabled` flips to true, call
   `scrobbleManager?.onSongStop()` and `ListeningRecorder.onStopped()` to
   cancel the in-flight scrobble timer and forget the current track.
File: `app/src/main/java/com/music/sonariq/data/scrobbling/ScrobbleManager.kt`
4. `onSongStart`, `onPlayerStateChanged`, and `updateNowPlaying`: return
   early when `PrivateSession.suspendsRecording()`.

That is the complete gate: no scrobble timer starts, no now-playing ping
sends, no stats sample records while the toggle is on.

---

## 4. What was deliberately left out, and why

- **Swipe artwork for next/previous (Feature 4, half):** already exists as
  `skipSwipeGesture` in `NowPlayingScreen.kt` (damped drag, haptics, blocked
  during parties). Re-implementing it would duplicate behaviour and risk the
  existing handling. The new work is the genuinely missing gesture:
  double-tap sides to seek ±10s.
- **Swipe-to-reorder inside the existing inline queue (Feature 2, half):**
  the inline queue already reorders by drag handle (`rememberQueueDrag` in
  `PlayerQueue.kt`); the brief forbids redesigning it. Reorder lives in the
  new Queue Manager screen instead (handle-drag for Up next and for saved
  queues, swipe-away to remove), which is additive.
- **Mood-shelf / playlist track listings for Auto (Feature 5, partial):**
  `AutoDataSource.moodTracks` / `playlistTracks` default to empty — the
  service's current snapshot helpers don't expose per-shelf track lists, and
  inventing tracks would be fake data. The shelves and playlists themselves
  are real and browsable; Phase 2 can wire the track endpoints when they
  exist.
- **A TV-side settings screen for ambient 2.0:** the TV app has no settings
  UI; the style switch lives in the phone's Settings (Cast/TV group), which
  is where the existing TV-related switches already are.
- **Home tap-telemetry:** section ranking uses real listening behaviour
  (recent top artists from the Replay stats + artist affinity per shelf).
  Per-section tap counts don't exist anywhere in the codebase, so no
  fabricated engagement scores were introduced.

---

## 5. Tests

`app/src/test/java/com/music/sonariq/` (JUnit4, matching existing patterns):
- `HomeSectionRankingTest.kt` — section ids, manual-beats-learned ordering,
  recents surfacing, artist affinity, learn-off, move/encode/decode.
- `SavedQueuesTest.kt` — create validation, newest-first + cap, rename,
  move/remove track, JSON round-trip, garbage tolerance, snapshot round-trip.
- `PrivateSessionTest.kt` — gate allows/blocks/follows-toggle; plus
  `PlayerArtworkGesturesTest` — double-tap half detection, middle boundary,
  unknown width, 10s step.
- `AutoBrowseTreeTest.kt` — six top-level sections, stable ids, folder vs
  playable flags, library sub-folders, playlist/mood listing, unknown-id and
  paging behaviour, shelves listed without wired tracks.
