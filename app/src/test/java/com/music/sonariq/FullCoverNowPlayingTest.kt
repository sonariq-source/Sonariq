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
 * centred square sleeve. The persisted key and the on-by-default contract are
 * what matters to the updater and to fresh installs, so the test pins both —
 * the same fake-prefs proxy trick the queue tests use to stand in for Android.
 */
class FullCoverNowPlayingTest {

    private val store = mutableMapOf<String, Any?>()
    private var originalPrefs: SharedPreferences? = null
    private var originalAuthStore: Any? = null

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
        val prefsField = AppSettings::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        originalPrefs = runCatching { prefsField.get(AppSettings) as SharedPreferences? }.getOrNull()
        prefsField.set(AppSettings, fakePrefs())
        // reload() also reads the encrypted auth store (webdav/smb/discord secrets),
        // which is a separate lateinit var — give it an empty instance so the
        // round-trip under test does not touch the real one.
        val authStoreField = AppSettings::class.java.getDeclaredField("authStore")
        authStoreField.isAccessible = true
        originalAuthStore = runCatching { authStoreField.get(AppSettings) }.getOrNull()
        authStoreField.set(AppSettings, fakeAuthStore())
    }

    @After
    fun restorePrefs() {
        val prefsField = AppSettings::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        val original = originalPrefs
        if (original != null) prefsField.set(AppSettings, original)
        val authStoreField = AppSettings::class.java.getDeclaredField("authStore")
        authStoreField.isAccessible = true
        val originalStore = originalAuthStore
        if (originalStore != null) authStoreField.set(AppSettings, originalStore)
        AppSettings.fullBleedArtwork.value = true
    }

    /**
     * An AuthStore with empty secrets, allocated without running its
     * Context-requiring constructor — only the three secret getters that
     * readAll() touches are needed, and they read from an empty prefs.
     */
    private fun fakeAuthStore(): Any {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val authStoreClass = Class.forName("com.music.sonariq.auth.AuthStore")
        val instance = unsafe.allocateInstance(authStoreClass)
        val prefsField = authStoreClass.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(instance, fakePrefs())
        return instance
    }

    @Test
    fun `full-cover is on by default with nothing stored`() {
        store.clear()
        AppSettings.fullBleedArtwork.value = false

        AppSettings.reload()

        assertTrue(AppSettings.fullBleedArtwork.value)
    }

    @Test
    fun `a stored off stays off after reload`() {
        store["full_bleed_artwork"] = false

        AppSettings.reload()

        assertFalse(AppSettings.fullBleedArtwork.value)
    }

    @Test
    fun `setter persists the persisted key and updates the flow`() {
        AppSettings.setFullBleedArtwork(false)
        assertFalse(AppSettings.fullBleedArtwork.value)
        assertFalse(store["full_bleed_artwork"] as Boolean)
        AppSettings.reload()
        assertFalse(AppSettings.fullBleedArtwork.value)

        AppSettings.setFullBleedArtwork(true)
        assertTrue(AppSettings.fullBleedArtwork.value)
        assertTrue(store["full_bleed_artwork"] as Boolean)
        AppSettings.reload()
        assertTrue(AppSettings.fullBleedArtwork.value)
    }
}
