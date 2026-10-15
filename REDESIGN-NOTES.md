# Sonariq 2.1 — UI redesign

The current Sonariq look. It replaces the earlier plum / coral theme.

## Identity
- **Palette:** deep-teal ink backgrounds (#06121A, #0C1D28, #132A38), amber primary (#FFB020), kelp teal secondary (#4FD1B5), pearl tertiary (#CFE6F0). The light theme is a cool foam white with a deepened amber (#9A5600).
- **Accents (6):** Amber, Kelp, Ice, Anemone, Sulfur, Violet. Enum IDs (LIME, EMERALD, TEAL, GREEN, YELLOW, AMBER) are unchanged so saved preferences still load. Translated accent names were removed, so they fall back to English.
- **Type:** Caladea Bold serif for headlines (`SonariqDisplay`; `SpaceGrotesk` kept as an alias), Inter for body and labels.
- **Shapes:** tighter radii (4/8/12/16/24dp).
- **Navigation:** floating icon capsule — only the selected tab opens to show its name, in an amber pill (replaces the full-width labelled dock).
- **Mini-player:** round pill with circular artwork and an amber play button.
- **Home:** left-aligned serif header with sonar rings; the spotlight is a full-width poster with the title over its foot; section headings lead with an amber rule.
- **Covers:** one signature "leaf" cover shape (two hard corners, two tight) used on shelf cards, folder tiles, the poster, mood cards and the search field.
- **Explore:** staggered mosaic of equal-height mood cards instead of a uniform two-column grid.
- **Library:** quick access is a ruled index list (glyph, name, detail, chevron) instead of a card grid.
- **Now Playing:** the play control is an amber leaf key; shuffle, skip and repeat flank it as before.
- **Copy:** page eyebrow labels are sentence case.
- **Desktop:** the desktop palette uses the same tones with an amber accent.

## Naming
Everything internal is named Sonariq: Kotlin packages and folders (`com.music.sonariq`), namespaces, JNI function names, native library names, env vars, resource names and services. Deliberately unchanged: links to the original BitChord project and its hosted services (GitHub repo and releases, `bitchord.kushagrasingh.in` jam/invite and presence servers, the `onrender.com` jam servers, the Go backend module path) — these are other people's servers and the GPL credit, and renaming them would break Listen Together, invite links, presence and update checks. Point them at your own servers when you host the backend.

## Pre-existing import fixes
The source as received was missing `height` imports in LibraryScreen.kt and ExploreScreen.kt (and `border` in ExploreScreen.kt); they are added.

## Not verified
No Android SDK was available, so nothing was compiled or run. Build in Android Studio (`./gradlew :app:assembleDebug`).
