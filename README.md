<div align="center">

<br/>
<br/>

<img src="Logo.png" alt="Sonariq app icon" width="180" />

# Sonariq

### Your music, in emerald.

*The music player that feels alive — liquid glass, synced lyrics, and sound that moves with you.*

<br/>

[![Latest release](https://img.shields.io/github/v/release/sonariq-source/Sonariq?style=for-the-badge&labelColor=0d1511&color=1db954)](https://github.com/sonariq-source/Sonariq/releases)
[![License](https://img.shields.io/github/license/sonariq-source/Sonariq?style=for-the-badge&labelColor=0d1511&color=35e887)](https://github.com/sonariq-source/Sonariq/blob/sonariq/LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/sonariq-source/Sonariq/build.yml?branch=sonariq&style=for-the-badge&labelColor=0d1511&color=32d979&label=build)](https://github.com/sonariq-source/Sonariq/actions)
[![Downloads](https://img.shields.io/github/downloads/sonariq-source/Sonariq/total?style=for-the-badge&labelColor=0d1511&color=8ef5bd)](https://github.com/sonariq-source/Sonariq/releases)
[![Support on Ko-fi](https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&labelColor=1a0b1e&logo=ko-fi&logoColor=white)](https://ko-fi.com/sonariqstudio)

<br/>

[**Download**](#-download) · [**Features**](#-features) · [**Sonariq TV**](#-sonariq-tv) · [**Contributing**](#-contributing) · [**Legal**](#-disclaimer--legal-notice)

</div>

> [!IMPORTANT]
> Sonariq is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<div align="center">

<img src="Banner.png" alt="Sonariq banner" width="100%" />

## Features

*Everything a music lover needs — wrapped in liquid glass.*

</div>

### Playback
- **Mood & time-of-day** — a home section with time-aware stations.
- **Phone-to-TV handoff** — fling playback from the phone app to Sonariq TV via a Listen Together party.
- **Shared party queues** — party members add/remove songs in Listen Together.
- **Party voting** — members vote on what plays next; the queue follows the votes.
- **Party recap** — after the party: top tracks and who added what.
- **Taste match** — a compatibility score with friends from real listening overlap.
- **Collaborative playlists** — build playlists together through a Listen Together party.
- **Friend activity feed** — see what friends are playing, from the party server.
- **Concert alerts** — upcoming shows by your top artists via Ticketmaster.
- **Search, browse and play** anything available on YouTube Music.
- **Hi-Res lossless audio** — FLAC/ALAC from a configured module source, with YouTube Music as fallback.
- **Gapless playback with true crossfade**, adjustable 0–12s.
- **Automix [Beta]** — DJ-style transitions with beat-matching and tempo-stretching.
- **Offline downloads** — save tracks with embedded metadata.
- **Local music library** integration.
- **Background playback** via a proper foreground media session.
- **Sonariq Radio** — endless station from any song, artist or playlist; the queue refills itself so the music never stops.
- **Playlist folders** — organise playlists into folders, with smart rules that auto-fill (e.g. "5-star rock added this month").
- **Queue 2.0** — save multiple named queues, browse queue history, swipe to reorder.
- **Gestures** — swipe the artwork for next/previous, double-tap the sides to seek ±10s.
- **New-release radar** — a feed of fresh drops from artists you follow.
- **Playlist cover maker** — generate custom canvas-rendered artwork for your playlists.
- **Monthly recap** — a shareable story of your month in music.
- **Auto-downloads** — liked songs and chosen playlists download themselves; schedule overnight downloads.
- **Storage manager** — real disk numbers for downloads, cache and artwork, with one-tap cache clear.
- **Import/export playlists** — m3u files in and out.
- **Library cleanup** — duplicate finder plus automatic metadata fixing for missing tags.
- **Backup & restore** — one file saves your library, playlists, settings and history.
- **Private session** — pause scrobbling and history when you don't want it counted.
- **Data saver** — streaming quality cap plus WiFi-only mode.

### Synced lyrics
- **Word-synced lyrics** — real per-word and per-syllable timings, never estimated or interpolated. TTML timings are hunted first for every track; if a song only has line timings at first, Sonariq keeps looking in the background and upgrades it the moment real word timings are found.
- **Depth and motion** — every line stays completely sharp; distance is carried by dimming and a slight settle-back, with readable floors so no line disappears — the playing line blooms as it settles in, and every sung word lands with a soft pop and glow.
- **Fully automatic** — every provider is raced in parallel; the best sync wins with zero setup.
- **Smart timing** — cross-checks providers so the first line never jumps the gun on instrumental intros.
- **Clean UI** — no provider badges, no sync estimates. Just the words.
- **Lyrics appearance menu** — right on the lyrics screen: text size, highlight intensity, background (artwork, dimmed or solid), word highlighting, auto-scroll and reduced motion, all saved.
- **Tap a word to seek** — on word-synced lines, tapping a single word jumps the song to that word; tapping a line still seeks to the line.
- **Translations & romanisation**, per-track offset, in-lyrics search and copy.
- **Lyrics translation overlay** — automatic translated subtitle lines in Now Playing (an explicit romanise tap wins over the overlay).
- **Lock-screen lyrics** — the current line shows right on your lock screen.
- **Instant lyrics** — your whole queue, home shelves and playlist pages are prefetched before you press play, and your liked songs and favourites are warmed up in the background; lookups surface the best match within seconds, so lyrics are simply there when the song starts.
- **AI word sync** — if no timed source has a song, Sonariq can measure the word timings from the track's own audio, so every song can word sync.
- **Reference-style presentation** — the sung word lights up and holds on the beat, lines fade with distance from the active one, auto-scroll glides smoothly, tap any line to seek, and animated dots count down the instrumental breaks.

### Smart autoplay & AI radio
- **Vibe autoplay engine** — detects the vibe of what you're playing (Last.fm listener tags + on-device tempo/key/energy analysis + your listening behaviour) and keeps the music flowing in that mood forever; the queue refills when 3 songs remain, skips teach it your taste, and a vibe badge on Now Playing shows what it's feeling.
- **Smart Autoplay** — learns your song-to-song transitions and predicts what plays next.
- **Fresh every session** — replaying a song starts a new discovery session: what autoplay recommended last time is demoted, never handed back as the same sequence, and nothing is banned for good — favourites return, just not on repeat.
- **AI mood stations** — pick Chill, Party, Focus or Workout; Sonariq builds a ~25-track station from your taste.
- **Autoplay mix control** — sliders for where autoplay pulls from: your library, downloads and new discoveries.
- **Safe vs Adventurous** — choose how far autoplay may stray from what you know.
- **Seed anything** — start autoplay from an artist, genre, mood or era, not just the current song.
- **Autoplay preview** — see upcoming autoplay picks in the queue; remove or reorder them before they play.
- **Never autoplay this** — ban a song or an artist from autoplay, managed from Settings.
- **Discovery mode** — lean autoplay into songs you have never played.
- **Downloads-only autoplay** — pick only from music on this device; kicks in automatically when offline.
- **Activity autoplay** — uses motion and step sensors to shape the session while driving, walking or running.
- **Wind down** — calms the music as bedtime approaches, then hands off to the sleep timer.
- **Workout session** — shapes the session around your workout: warm-up, climb, plateau, cool-down.
- **Energy curve** — Steady, Build, Peak & Coast or Wind Down presets for the session's energy arc.

### Personalisation
- **Dynamic themes** — the UI accent follows your album artwork's colours (toggle in Settings).
- **Liquid glass, buttery edition** — faster, smoother glass nav with a proper on/off toggle; animations stay on either way.
- **Your logo everywhere** — the white Sonariq waveform mark replaces every leftover placeholder.
- **Ambient Now Playing backdrop** — behind the player and the lyrics, a strong, darkened blur of the cover's own colours fills the screen — processed once and cached, crossfading between tracks; the landscape artwork card itself stays sharp; toggle it in Settings (on by default).

### Widgets & sharing
- **Share songs** — story cards, text share and Sonariq links. Tap "Share with lyrics" to pick your favourite lines (up to 5) and share the 1080×1920 lyrics card — the same theme as the lyrics screen, everywhere. Copy link gives a Sonariq link that opens the song straight in the app.
- **Sonariq links** — every song and party invite gets its own Sonariq link. With the app installed it opens straight into the song or party; without it, a page offers the download.
- **Now-playing + lyrics widget** — artwork, controls and a live lyric-line peek on your home screen.
- **Shareable Replay cards** — beautiful 1080×1350 stats cards rendered from your real listening, ready to share.
- **Annual Wrapped** — year-in-review story with shareable cards.

### Design
- **New green-gradient waveform logo** — on the launcher, in-app and in notifications.
- **Custom theme colour picker** — hue/saturation/hex picker for the app accent.
- **Living Sound design identity** — Sonariq's own visual language: the six-bar soundline from the logo runs through the interface as header glyphs, section eyebrows and the selected-tab marker; glass comes in four material tiers (navigation, panel, playback, sheet) with a lit top edge; and hero artwork carries an "echo" — an offset, emerald-washed copy of itself layered behind the artwork for depth without blur.
- **Emerald Liquid Glass design system** — deep green-black surfaces, emerald primary, bright green and mint accents, and translucent glass panels with fine light borders throughout.
- **Recomposed layout** — an asymmetric Home led by a full-bleed spotlight with a glass caption panel, mixed-scale shelves and wide discovery bands; a card-based Library with category chips and spine-accented feature collections; eyebrow-and-title headers on every screen; and one clean sans type hierarchy, set large and tight at display sizes.
- **Reorganised Settings** — seven clear categories (Playback & audio, Lyrics, Appearance, Library & downloads, Accounts & services, Your data & privacy, App & updates), each with its own page, on phone and desktop alike; search still finds every setting across all of them.
- **Redesigned Now Playing** — a clean header of artwork thumbnail, title, favourite star and menu over an ambient artwork backdrop; the player view floats blurred lyric lines behind the controls with a waveform seek bar whose bars are a stable fingerprint of each track, big transport, volume row and a bottom utility row; the lyrics view fills the screen with the playing line sharp and the lines around it softly blurred by distance, in portrait and landscape alike.
- **Mini-player progress** — a slim progress line along the mini player card's bottom edge follows the song.
- **Animated album canvas** — motion artwork on the now-playing screen.

### In-app updates
- **Automatic update checks** every time the app opens — toggleable in Settings.
- **Manual check** and current-version display.
- **Update card** with version and changelog — download and install without leaving the app.

### Smart downloads
- **Smart Downloads 2.0** — time/daypart pattern prediction learns when you listen.
- **True offline mode** — a single Settings toggle for downloads-only playback.
- **Automatic daily downloads** of your favourites — Wi-Fi and charging aware.
- **Offline lyrics prefetch** so lyrics work without a connection.
- Storage estimates and 25/50/100 track limits.

### Sound & stats
- **DJ mix mode** — tempo- and energy-aware crossfade transitions.
- **7-band EQ** with user presets and tone trims.
- **Per-device EQ profiles** — your EQ follows the output: one setting for earbuds, another for the speaker.
- **Mono audio mix** — downmix to mono for single-ear listening.
- **Vocal Remover** — on-device AI separation lifts the lead vocal out of a song and leaves the music intact; the slider then fades the real vocal stem from full down to gone, prepared in the background and cached per song, with a standard centre-channel mode covering songs that are not ready yet.
- **Admin Control Center** — a one-time owner claim turns on a private control center for your installation: real listening totals and analytics from your own device, autoplay and lyrics-cache management with measured timing quality, vocal remover status, system health and storage, announcements, feature switches and a full audit log of every administrative action.
- **EQ preset sharing** — send your sound to friends as a Sonariq link.
- **Playback speed control** (0.5×–2.0×) and **skip silence**.
- **Sleep timer** — fixed presets or "stop after this track", with gradual fade-out over the last 60 seconds.
- **Weekly Replay** — your listening stats, milestones and smart playlists.
- **Stats 2.0** — listening streaks, genre breakdown, top tracks and artists, all shareable as cards.
- **Stats for nerds** — codec, bit depth, sample rate on the now-playing screen.

### Connectivity
- **Sign in with your Google account** for personalized content.
- **Streaming integration** — link your streaming account to play your playlists and Liked Songs.
- **Discord Rich Presence** — live track/artist/album and progress.
- **Scrobbling** to Last.fm and ListenBrainz.
- **Cast to TV** — audio casting plus synced lyrics on a custom receiver.
- **Pluggable sources** — add, edit, test and health-check module sources.

---

<div align="center">

## Sonariq TV

*A separate app for the big screen — same music engine, 10-foot UI.*

Sonariq TV (`com.sonariq.tv`) plays music **on the TV itself** with a remote-friendly interface: big search, D-pad navigation, and full-screen lyrics that follow the song. **Listen Together on TV** (host and join parties from the couch) and **ambient mode 2.0** — a fullscreen now-playing screensaver with live lyrics when idle.

> [!NOTE]
> Sonariq TV ships on the same [Releases](https://github.com/sonariq-source/Sonariq/releases) page as the phone app — one release carries both APKs, and each app's in-app updater automatically picks the right one.

</div>

---

<div align="center">

## Download

<a href="https://github.com/sonariq-source/Sonariq/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/android/cozy.svg" alt="Download for Android" height="55"/></a>
<a href="https://github.com/sonariq-source/Sonariq/releases" target="_blank" rel="noopener noreferrer"><img src="https://cdn.jsdelivr.net/gh/JMcrafter26/badges@main/src/assets/supported/windows/cozy.svg" alt="Download for Windows" height="55"/></a>

Grab the latest signed APK from the [Releases](https://github.com/sonariq-source/Sonariq/releases) page. Sideloading requires enabling "Install unknown apps" for whichever app you download it with.

Prefer the cutting edge? Every push builds debug APKs automatically — find them under [Actions](https://github.com/sonariq-source/Sonariq/actions).

### Windows

Sonariq for Windows is the full desktop app — the same player, synced lyrics (including AI word sync), 7-band EQ, downloads, Listen Together and smart autoplay as the phone, laid out for a window with system media controls and a tray icon. Grab it from the [Sonariq for Windows release](https://github.com/sonariq-source/Sonariq/releases/tag/windows-2.28):

- **Sonariq-2.28-windows-x64-setup.exe** — the installer. Installs for the current user, so it never asks for an administrator.
- **Sonariq-2.28-windows-x64.msi** — the same app for anyone who deploys by MSI.
- **Sonariq-2.28-windows-x64-portable.zip** — unzip anywhere and run Sonariq.exe. Writes nothing outside the folder.

Every package carries its own Java runtime and codecs — nothing else has to be installed first. For the Linux desktop app and building the packages yourself, see [DESKTOP.md](DESKTOP.md).

### iOS (Beta)

Sonariq for iPhone is in beta — a native Swift/SwiftUI app with a Liquid Glass interface, true word-by-word synced lyrics, a real 7-band equalizer, and Live Activity. Grab the beta IPA and install guide at [Sonariq-iOS](https://github.com/sonariq-source/Sonariq-iOS).

</div>

---

<div align="center">

## Support Sonariq

Sonariq is free and made by an independent developer. If it soundtracks your days, consider [buying a coffee](https://ko-fi.com/sonariqstudio) — every bit goes back into new features, design polish, and keeping the servers running.

<a href="https://ko-fi.com/sonariqstudio" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white" alt="Support Sonariq on Ko-fi" /></a>

</div>

---

<div align="center">

## Contributing

We welcome contributions to Sonariq! Please review our [Contributing Guide](CONTRIBUTING.md) and [Code of Conduct](CODE_OF_CONDUCT.md) before submitting a Pull Request.

[**Contributing Guide**](CONTRIBUTING.md) · [**Code of Conduct**](CODE_OF_CONDUCT.md) · [**Maintainers**](MAINTAINERS.md) · [**Additional Docs**](ADDITIONAL.md)

</div>

---

<div align="center">

## Disclaimer & Legal Notice

Sonariq is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, Telegram, or any of their parent companies.

* **No Media Hosting:** Sonariq does not host, upload, or store copyrighted music files. It operates strictly as an interface to scan local device storage or stream media directly from public, public-facing, or user-authenticated APIs.
* **Fair Use & API Usage:** This software is created solely for personal research, educational, and fair-use purposes. The user is entirely responsible for ensuring their usage aligns with their local copyright laws and YouTube Terms of Service.
* **No Ad-Blocking Guarantee:** While Sonariq focuses on providing a clean listening environment, it does not guarantee permanent bypasses or modifications to commercial third-party platform conditions.
* **Copyleft:** Sonariq is free software under the GPLv3. The license does not let anyone forbid others from selling or redistributing copies, but any distribution must come with the Corresponding Source under the same license.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.

</div>
