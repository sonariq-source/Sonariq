# Sonariq Fork Plan

Fork of https://github.com/kushagrasinghx/BitChord (GPL-3.0) → "Sonariq" by Joel.
Decisions below were confirmed by Joel on 2026-10-14.

## Confirmed decisions
- **Base:** `v1.8` tag (commit `32f4e7c5`) — matches Joel's APK (versionCode 26, versionName 1.8).
- **applicationId:** `com.sonariq.app` (was `com.music.bitchord` upstream). Dev flavor: `com.sonariq.app.dev` (was `com.dev.bitchord` upstream).
  - Change the `applicationId` / `applicationIdSuffix` in `app/build.gradle.kts` only; Kotlin packages and source dirs are now `com/music/sonariq`.
  - Grep for any hardcoded old package name (deep links, FileProvider authorities, etc.) and update them.
- **Full redesign:** Sonariq neon-purple dark theme (see the "Sonariq App Mockups" artifact for the design system), Joel's new icon
  (`workspace/user/media_library/image/d9/d982213c85e2956d78936dec38acfb63f79bed292e1889bfa28edae58deacf1b.png`
  → `app/src/main/res/mipmap-*/ic_launcher*.webp` + adaptive `mipmap-anydpi`), display name "Sonariq"
  (`app/src/main/res/values*/strings.xml` → `app_name`).

## License obligations (GPL-3.0) — must survive the fork
- Keep the original author's copyright notices and the `LICENSE` file.
- The fork stays GPL-3.0; note what was changed in modified files.
- If the Sonariq APK is distributed, the corresponding source must be made available (publish the fork, e.g. on GitHub).
- Keep visible credit to the original BitChord project (e.g. an About-screen line).

## Signing
- Release builds: reuse `~/workspace/sonariq/keystore/sonariq.keystore` (alias `sonariq`; password in `~/workspace/sonariq/keystore/README.txt`).

## Build notes (from investigation)
- Retheme target: `app/src/main/java/com/music/sonariq/ui/theme/Theme.kt` (Android app module — safe).
- Caution: `sharedUi/src/jvmSharedMain` is shared with the desktop app. Prefer Android-scoped changes (`androidMain` / app module); touch shared screens only deliberately.
- `NowPlayingScreen.kt` is huge (~5,500 dex instructions; upstream notes an R8 register-allocation bug) — split it during the redesign.
- Navigation is custom/state-driven (no `NavHost`) — follow the existing pattern.
- `MainActivity.kt` (3,200+ lines) and `MainViewModel.kt` are monoliths; redesign touches a lot of surface — stage the work.
