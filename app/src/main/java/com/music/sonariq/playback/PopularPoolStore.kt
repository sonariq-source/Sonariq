package com.music.sonariq.playback

import android.content.Context
import android.telephony.TelephonyManager
import com.music.sonariq.data.YtMusicRepository
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device's [PopularPool], fetched from the charts and cached.
 *
 * Charts move slowly, so one fetch serves for hours; the cache is keyed
 * by the resolved country, so travelling (or a SIM swap) re-resolves
 * rather than serving the old country's chart. A failed fetch caches
 * nothing and the next batch tries again — AutoPlay simply runs on
 * discovery alone until one succeeds, exactly as it did before the
 * pool existed.
 */
object PopularPoolStore {

    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val mutex = Mutex()

    @Volatile
    private var cachedCountry: String? = null

    @Volatile
    private var cachedPool: PopularPool = PopularPool.EMPTY

    @Volatile
    private var cachedAtMs: Long = 0L

    /**
     * The listener's country from the device itself: the network's
     * country first, then the SIM's, then the locale's. No lookup
     * leaves the device. Null when the device cannot name one, which
     * the charts read as the global charts.
     */
    fun deviceCountry(context: Context): String? {
        val telephony = runCatching {
            context.getSystemService(TelephonyManager::class.java)
        }.getOrNull()
        return ContentCountry.resolve(
            networkIso = runCatching { telephony?.networkCountryIso }.getOrNull(),
            simIso = runCatching { telephony?.simCountryIso }.getOrNull(),
            localeCountry = Locale.getDefault().country,
        )
    }

    /** The popular pool for this device's country, global charts behind it. */
    suspend fun load(context: Context): PopularPool {
        val country = deviceCountry(context)
        val now = System.currentTimeMillis()
        if (cachedAtMs > 0 && cachedCountry == country && now - cachedAtMs < TTL_MS) {
            return cachedPool
        }
        return mutex.withLock {
            // A concurrent load may have filled the cache while waiting.
            val recheckAt = System.currentTimeMillis()
            if (cachedAtMs > 0 && cachedCountry == country &&
                recheckAt - cachedAtMs < TTL_MS
            ) {
                return@withLock cachedPool
            }
            val countrySongs = if (country != null) {
                YtMusicRepository.charts(country).getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val globalSongs = YtMusicRepository.charts(null)
                .getOrDefault(emptyList())
            val pool = mergePopularPools(countrySongs, globalSongs)
            if (pool.songs.isNotEmpty()) {
                cachedCountry = country
                cachedPool = pool
                cachedAtMs = System.currentTimeMillis()
            }
            pool
        }
    }
}
