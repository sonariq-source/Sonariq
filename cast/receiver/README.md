# Sonariq Cast Receiver (TV lyrics)

A custom Chromecast receiver that shows synced karaoke lyrics on the TV while
Sonariq casts. The phone sends the lyric lines (with word timings); the
receiver syncs them to its own media clock, so the highlight is exact.

## What you need

1. **HTTPS hosting** for `sonariq-cast-receiver.html` — any static host works
   (GitHub Pages, Netlify, Vercel, your own server). Note the final URL.
2. **A Google Cast Developer Console account** ($5 one-time registration fee)
   at https://cast.google.com/publish/.

## Register the receiver

1. In the Cast Developer Console, add a new **Custom Receiver** application.
2. Set the receiver URL to your hosted `sonariq-cast-receiver.html`.
3. Publish the app (or keep it unpublished for testing — unpublished apps
   work on devices registered in the console).
4. Copy the **Application ID** (looks like `ABCD1234`).

## Point Sonariq at it

1. On the phone: Settings > Advanced > **Cast receiver app ID**.
2. Paste the Application ID and save.
3. **Restart the app** — the Cast framework only reads the ID at startup.
4. Cast as usual. The TV now shows lyrics, synced word-by-word.

Leave the field blank to go back to the stock Default Media Receiver
(audio-only casting, exactly as before).

## Notes

- The receiver never contacts Sonariq's servers; the phone pushes lyrics over
  the Cast custom-message channel (`urn:x-cast:com.sonariq.lyrics`).
- Tracks without synced lyrics show title, artist and artwork instead.
- The phone's Settings > Advanced > **TV lyrics** toggle turns the feature
  off without unregistering the receiver.
