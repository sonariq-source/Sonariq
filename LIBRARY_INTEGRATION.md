# Library features — integration manifest

Eight library features, implemented as new files only. No existing file was
edited. **The app will not compile until Phase 2 adds the 8 AppSettings keys
below** — new code references `AppSettings.<key>` and `AppSettings.set<Key>`
per the sanctioned pattern. Add the keys first, then wire the entry points.

## Files created

### Data layer (`app/src/main/java/com/music/sonariq/data/library/`)
- `PlaylistFolders.kt` — playlist folders (named sets of playlist ids) and
  smart folders (saved rule sets evaluated against real metadata). Rules:
  artist/album text, added-in-last-N-days, min/max plays, not-played-in-N-days,
  explicit flag, served quality. SharedPreferences + StateFlow store; pure
  `matches()` / `evaluate()` functions.
- `LibraryBackup.kt` — one-file backup/restore (`sonariq-library-backup` JSON
  v1): settings (typed prefs via `Backup.PrefValue`), listening history,
  search history, local playlists, folders, smart folders, download record.
  Extends `data/stats/Backup.kt`'s coverage without editing it.
- `AutoDownloads.kt` — auto-download liked songs + chosen playlists;
  `AutoDownloadWorker` (daily WorkManager, same pattern as
  `SmartDownloadWorker`). Liked songs via a snapshot (see manifest note 3).
- `ScheduledDownloads.kt` — overnight scheduling: `nextRunMillis()` /
  `delayUntilMillis()` pure clock math, `ScheduledDownloadWorker`
  (one-time WorkManager, reschedules itself after each run).
- `StorageManager.kt` — real disk numbers: downloads (private dir walk, or
  media-store `SIZE` sum when exported), `cache/audio`, `cache/image_cache`,
  `files/download-artwork`; one-tap cache clear (caches only).
- `M3uPlaylists.kt` — extended/plain m3u parse + write + path-first,
  artist/title-second matching with miss reporting.
- `LibraryCleanup.kt` — duplicate finder (normalised title/artist + duration
  within 3s) and missing-metadata reporter with file-name suggestions.
  Report-only; never writes.

### UI (`app/src/main/java/com/music/sonariq/ui/library/`)
- `LibraryFoldersScreen.kt` — folders + smart folders list, create/delete.
- `SmartFolderEditorScreen.kt` — title, all/any match mode, rule add/remove
  (8 rule kinds with text/number/boolean/quality inputs).
- `LibraryBackupScreen.kt` — SAF export/import with confirmation + status.
- `StorageManagerScreen.kt` — measured numbers + clear-caches flow.
- `LibraryCleanupScreen.kt` — duplicates (keep suggestion + remove-others)
  and metadata gaps (suggestion + apply), via caller-wired callbacks.
- `M3uTransferScreen.kt` — per-playlist m3u export + m3u import with
  match/miss report.
- `AutoDownloadSettings.kt` — settings rows for the Downloads group
  (switches, playlist multi-select, time picker).

### Resources
- `app/src/main/res/values/strings_library.xml` — all new user-visible
  strings (existing `delete`/`cancel`/`save` reused, not redefined).

### Tests (`app/src/test/java/com/music/sonariq/`)
- `PlaylistFoldersTest.kt`, `M3uPlaylistsTest.kt`, `LibraryCleanupTest.kt`,
  `ScheduledDownloadsTest.kt`, `StorageManagerTest.kt`, `AutoDownloadsTest.kt`,
  `LibraryBackupTest.kt`

## 1. AppSettings keys to add (all in `data/settings/AppSettings.kt`)

Follow the existing `smartDownloadsEnabled` pattern (MutableStateFlow +
KEY_ constant + read in `readAll()` + setter):

| Key name | Type | Default | Prefs key |
|---|---|---|---|
| `autoDownloadsEnabled` | Boolean | false | `KEY_AUTO_DOWNLOADS_ENABLED` |
| `autoDownloadLikedSongs` | Boolean | true | `KEY_AUTO_DOWNLOAD_LIKED_SONGS` |
| `autoDownloadPlaylistIds` | Set<String> | emptySet() | `KEY_AUTO_DOWNLOAD_PLAYLIST_IDS` |
| `autoDownloadWifiOnly` | Boolean | true | `KEY_AUTO_DOWNLOAD_WIFI_ONLY` |
| `scheduledDownloadsEnabled` | Boolean | false | `KEY_SCHEDULED_DOWNLOADS_ENABLED` |
| `scheduledDownloadHour` | Int | 2 | `KEY_SCHEDULED_DOWNLOAD_HOUR` |
| `scheduledDownloadMinute` | Int | 0 | `KEY_SCHEDULED_DOWNLOAD_MINUTE` |
| `scheduledDownloadWifiOnly` | Boolean | true | `KEY_SCHEDULED_DOWNLOAD_WIFI_ONLY` |

Setters the new code calls (mirror `setSmartDownloadsEnabled` etc.):
`setAutoDownloadsEnabled(Boolean)`, `setAutoDownloadLikedSongs(Boolean)`,
`setAutoDownloadPlaylistIds(Set<String>)`, `setAutoDownloadWifiOnly(Boolean)`,
`setScheduledDownloadsEnabled(Boolean)`, `setScheduledDownloadHour(Int)`,
`setScheduledDownloadMinute(Int)`, `setScheduledDownloadWifiOnly(Boolean)`.

New code references (must all resolve after the above):
- `AutoDownloads.kt`: `autoDownloadsEnabled`, `autoDownloadLikedSongs`,
  `autoDownloadPlaylistIds`; worker: `autoDownloadsEnabled`, `autoDownloadWifiOnly`
- `ScheduledDownloads.kt`: `scheduledDownloadHour`, `scheduledDownloadMinute`,
  `scheduledDownloadsEnabled`, `scheduledDownloadWifiOnly`
- `AutoDownloadSettings.kt`: all 8 getters + all 8 setters
- `StorageManager.kt`: existing `exportDownloads` (no new key)

## 2. Entry points (Phase 2 wiring)

- **Settings → Downloads group** (`ui/screens/SettingsSheet.kt`, ~line 580):
  insert `AutoDownloadSettings(playlists = …)` rows (id/title pairs from
  `LocalPlaylistStore.playlists`).
- **Settings → Storage group** (`SettingsSheet.kt`, ~line 1774): row
  "Storage" → `StorageManagerScreen(onBack)`.
- **Settings → Your data group** (`SettingsSheet.kt`, ~line 1840): rows
  "Library backup" → `LibraryBackupScreen(onBack)`; "Library cleanup" →
  `LibraryCleanupScreen(songs, …)` with the library song list.
- **Library tab**: a "Folders" row/section → `LibraryFoldersScreen(...)`;
  playlist overflow menus → "Add to folder" (calls
  `PlaylistFolders.addPlaylistToFolder`), "Export as m3u".
- **Library menus / playlist detail**: "Playlists as files" →
  `M3uTransferScreen(playlists, librarySongs, onImportSongs, onBack)`.

## 3. Minimal edits to existing files (Phase 2)

1. `data/settings/AppSettings.kt` — add the 8 keys + 8 setters (§1).
   Anchor: `KEY_SMART_DOWNLOADS_ENABLED` declarations (~line 225–237) and
   `setSmartDownloadsEnabled` (~line 1969).
2. `SonariqApplication.kt` `onCreate`, after
   `SmartDownloads.applySchedule()` (~line 112):
   ```kotlin
   com.music.sonariq.data.library.PlaylistFolders.init(this)
   if (AppSettings.autoDownloadsEnabled.value)
       com.music.sonariq.data.library.AutoDownloads.schedule(this)
   if (AppSettings.scheduledDownloadsEnabled.value)
       com.music.sonariq.data.library.ScheduledDownloads.schedule(this)
   ```
3. `ui/MainViewModel.kt`, in `setLike()`'s `onSuccess` (~line 618–631):
   refresh the liked snapshot so the worker sees likes promptly:
   ```kotlin
   val liked = (_library.value as? UiState.Success)?.data?.likedSongs
   if (liked != null) com.music.sonariq.data.library.AutoDownloads
       .snapshotLiked(getApplication(), liked)
   ```
   (Needs a Context in MainViewModel — use the application context.)
4. `ui/screens/SettingsSheet.kt` — entry-point rows (§2). No existing rows
   are touched; new rows are appended to the Downloads, Storage, and Your
   data groups.
5. Optional — `download/Downloads.kt`: to make backup *restore* the download
   record, add a public API such as
   `fun reregister(videoIdToUri: Map<String, String>)` that validates each
   uri with `DownloadStore.exists()` before putting it in `_saved`. Without
   it, import reports the download count and leaves files untouched.
6. Optional — `data/spotify/LocalPlaylistStore.kt`: to let cleanup
   remove duplicates / apply metadata fixes inside playlists, add public
   `removeSongs(playlistId, videoIds)` and `updateSong(playlistId, videoId,
   title, artist, albumName)`. The cleanup screen already delegates via
   `onRemoveDuplicates` / `onApplyFix` callbacks.

## 4. Features deliberately left out / limited, and why

- **Genre / star-rating smart rules** — neither exists anywhere in the app's
  metadata (`Song` has no genre or rating field). Rules cover only real
  fields; inventing metadata would be fake data.
- **Download-record restore in backup** — `Downloads._saved` has no public
  write API; the backup carries the list and import reports it honestly
  instead of faking the restore. See §3.5.
- **Automatic metadata fixing** — the finder is report-only by design
  ("never silently alter"). Applying needs the store APIs in §3.6.
- **Exact-time overnight alarm** — WorkManager one-time work is inexact by
  design; an exact alarm would need a new permission for a job that is fine
  starting at 2:47 instead of 2:30.
- **Party-shared (collab) playlists in backup** — owned by the party, re-sync
  from it; backing them up would restore ghosts.

## 5. Tests

New files under `app/src/test/java/com/music/sonariq/`, following existing
JUnit4 patterns (e.g. `SmartPlaylistsTest`, `DownloadStoreTest`):
rule matching/evaluation + JSON round-trip, m3u parse/build/match,
duplicate detection + metadata gaps + duration parsing, schedule clock math
(day rollover), directory-walk measurement + byte formatting, target
selection (dedupe, already-downloaded filtering), backup schema round-trip
including forward-compat with older files.
