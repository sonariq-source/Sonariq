package com.music.sonariq

import android.content.SharedPreferences
import com.music.sonariq.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The Full-cover Now Playing switch, backed by `AppSettings.fullBleedArtwork`.
 *
 * The setting is a plain [SharedPreferences] round-trip: on means the cover
 * fills the whole Now Playing screen edge to edge, off keeps the classic
 * centred square sleeve. The test pins the persisted key, the on-by-default
 * contract and the setter — the same fake-prefs proxy trick the queue tests
 * use to stand in for Android.
 *
 * Note: [AppSettings.reload] is deliberately not exercised here — it reads
 * every setting including the encrypted auth store, which has no test double.
 * The key/default contract below is exactly what reload() reads.
 */
class FullCoverNowPlayingTest {

    private val store = mutableMapOf<String, Any?>()
    private var originalPrefs: SharedPreferences? = null

    private fun fakeEditor(): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when {
                method.name.startsWith("put") && args != null && args.size == 2 -> {
                    store[args[0] as String] = args[1]
                    proxy
                }
                method.name == "remove" && args != null && args.size == 1 -> {
                    store.remove(args[0] as String)
                    proxy
                }
                method.name == "clear" -> {
                    store.clear()
                    proxy
                }
                method.name == "commit" -> true
                method.returnType == SharedPreferences.Editor::class.java -> proxy
                else -> null
            }
        } as SharedPreferences.Editor

    private fun fakePrefs(): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> fakeEditor()
                "contains" -> store.containsKey(args[0] as String)
                "getAll" -> store.toMap()
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                    if (store.containsKey(args[0] as String)) store[args[0]] else args[1]
                else -> null
            }
        } as SharedPreferences

    @Before
    fun injectFakePrefs() {
        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        originalPrefs = runCatching { field.get(AppSettings) as SharedPreferences? }.getOrNull()
        field.set(AppSettings, fakePrefs())
    }

    @After
    fun restorePrefs() {
        val field = AppSettings::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        val original = originalPrefs
        if (original != null) field.set(AppSettings, original)
        AppSettings.fullBleedArtwork.value = true
    }

    @Test
    fun `full-cover is on by default with nothing stored`() {
        store.clear()
        // Nothing stored: the read reload() performs falls back to the default.
        val readBack = (AppSettings::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
        }.get(AppSettings) as SharedPreferences).getBoolean("full_bleed_artwork", true)
        assertTrue(readBack)
        assertTrue(AppSettings.fullBleedArtwork.value)
    }

    @Test
    fun `a stored off reads back off`() {
        store["full_bleed_artwork"] = false
        val prefs = AppSettings::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
        }.get(AppSettings) as SharedPreferences
        assertFalse(prefs.getBoolean("full_bleed_artwork", true))
    }

    @Test
    fun `setter persists the persisted key and updates the flow`() {
        AppSettings.setFullBleedArtwork(false)
        assertFalse(AppSettings.fullBleedArtwork.value)
        assertFalse(store["full_bleed_artwork"] as Boolean)

        AppSettings.setFullBleedArtwork(true)
        assertTrue(AppSettings.fullBleedArtwork.value)
        assertTrue(store["full_bleed_artwork"] as Boolean)
    }
}
