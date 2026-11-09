package com.music.sonariq.vocal

import android.content.Context
import com.music.sonariq.playback.smart.MelSpectrogram
import com.music.sonariq.playback.smart.StereoStems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Which separator produced a stem. MDX is the high-quality tier; UMX the bundled one. */
enum class StemTier(val fileSuffix: String) {
    MDX("mdx"),
    UMX("umx"),
}

/** Where one track's separation stands, as published by [StemStore.states]. */
sealed interface StemState {
    data class Preparing(val tier: StemTier, val progress: Float) : StemState
    data class Ready(val tier: StemTier) : StemState
    data object Failed : StemState
}

/**
 * The on-disk name a track key hashes to: SHA-256 hex of the key's UTF-8.
 *
 * Keys are URIs for local tracks (see [StemJobs]), and a URI cannot be a file
 * name — slashes, query strings and all — quite apart from not wanting file
 * names on disk that spell out the listener's library paths. The hash is
 * stable across processes, which is the whole requirement.
 */
fun stemKeyHash(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}

/**
 * Every separated stem on the device: `filesDir/stems_v2/<sha256(key)>.mdx.wav`
 * (or `.umx.wav` for the bundled tier).
 *
 * ## Track keys
 *
 * The caller supplies the key; this store only hashes it. The convention the
 * callers keep (documented in [StemJobs]) is the queue's own identity:
 * `Song.videoId`, which is also the MediaItem's mediaId — a catalogue videoId
 * for streamed and downloaded tracks (a download's Song carries the videoId,
 * with the file under `localUri`), the MediaStore content URI for a local
 * library track (LocalMediaRepository sets `videoId` to that URI unless the
 * file is a recognised download), and the file URI for an unrecorded file in
 * the downloads folder. One identity everywhere means a track downloaded
 * after being streamed keeps the stem it earned while streaming.
 *
 * ## State
 *
 * [states] is keyed by track key for anything this process has prepared, and
 * seeded at [init] by scanning the directory — restored entries are keyed by
 * their file hash, since the key itself is unrecoverable from it. Lookups by
 * key ([stemFile], [tierOf]) go through the hash and are always exact; the
 * seeded map entries exist so "is anything ready" survives a restart.
 *
 * ## Eviction
 *
 * Stems are big (~35 MB a track), so the store caps itself at 1.5 GB and
 * evicts least-recently-modified first in [evictIfNeeded] — except files
 * currently open for playback, which register through [registerOpen] /
 * [unregisterOpen]. Playback opens a stem through `AiStemProcessor`, which is
 * what joins and leaves that registry.
 */
object StemStore {

    /** Default disk budget for all stems together. */
    const val DEFAULT_CAP_BYTES: Long = 1_500_000_000L

    // v2: the MDX tier's stems are level-calibrated from this version
    // on (see MdxModel.COMPENSATE); v1 stems were the raw model output
    // and must be re-separated rather than reused, so the store moved.
    private const val DIRECTORY_NAME = "stems_v2"

    @Volatile
    private var dir: File? = null

    @Volatile
    private var capBytes: Long = DEFAULT_CAP_BYTES

    private val _states = MutableStateFlow<Map<String, StemState>>(emptyMap())
    val states: StateFlow<Map<String, StemState>> = _states.asStateFlow()

    /** Canonical path -> open count, for files playback is reading right now. */
    private val openFiles = ConcurrentHashMap<String, Int>()

    /** File hash -> the track key that produced it, for keys seen this process. */
    private val knownKeys = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        init(File(context.filesDir, DIRECTORY_NAME))
    }

    /** Points the store at [directory] and seeds [states] from what is already there. */
    @Synchronized
    fun init(directory: File, cap: Long = DEFAULT_CAP_BYTES) {
        dir = directory
        capBytes = cap
        directory.mkdirs()
        scan()
    }

    /** Test seam: a fresh store over [directory], with all in-memory state reset. */
    internal fun initForTest(directory: File, cap: Long = DEFAULT_CAP_BYTES) {
        openFiles.clear()
        knownKeys.clear()
        _states.value = emptyMap()
        init(directory, cap)
    }

    private fun scan() {
        val directory = dir ?: return
        val seeded = mutableMapOf<String, StemState>()
        directory.listFiles()?.forEach { file ->
            val (hash, tier) = parseStemFileName(file.name) ?: return@forEach
            val existing = seeded[hash]
            // MDX wins the seed, exactly as it wins stemFile().
            if (existing == null || (existing is StemState.Ready && existing.tier != StemTier.MDX)) {
                seeded[hash] = StemState.Ready(tier)
            }
        }
        _states.update { seeded + it.filterKeys { key -> key !in seeded } }
    }

    private fun parseStemFileName(name: String): Pair<String, StemTier>? {
        if (!name.endsWith(".wav")) return null
        val stem = name.removeSuffix(".wav")
        val tier = StemTier.entries.firstOrNull { stem.endsWith(".${it.fileSuffix}") } ?: return null
        val hash = stem.removeSuffix(".${tier.fileSuffix}")
        if (hash.length != 64 || !hash.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hash to tier
    }

    private fun fileFor(key: String, tier: StemTier): File =
        File(dir, "${stemKeyHash(key)}.${tier.fileSuffix}.wav")

    /** The stem playback should use for [key] — the MDX file when both tiers exist — or null. */
    fun stemFile(key: String): File? {
        tierOf(key) ?: return null
        return fileFor(key, tierOf(key)!!).takeIf { it.isFile }
    }

    /** Which tier [key] has on disk, MDX preferred, or null when it has no stem. */
    fun tierOf(key: String): StemTier? {
        if (dir == null) return null
        return when {
            fileFor(key, StemTier.MDX).isFile -> StemTier.MDX
            fileFor(key, StemTier.UMX).isFile -> StemTier.UMX
            else -> null
        }
    }

    /**
     * Writes [stems] as [key]'s [tier] stem and publishes Ready.
     *
     * Stems arrive at 44.1 kHz from both separators; a separator that ever
     * returns another rate is resampled here rather than stored mislabelled —
     * the WAV header says 44.1 kHz and playback believes it. A rate that
     * cannot be resampled fails the write (false) rather than storing a stem
     * that would play at the wrong speed.
     *
     * The write goes to a temp file in the same directory and is renamed into
     * place, so a reader never sees a half-written stem under its final name;
     * the rename also gives an upgrade (UMX -> MDX) its atomic swap. The one
     * wrinkle is a same-tier rewrite of a file open for playback: on this
     * filesystem the open reader keeps the old inode and finishes the track
     * on the old stem, which is the behaviour wanted anyway.
     */
    fun writeStem(key: String, tier: StemTier, stems: StereoStems): Boolean {
        val directory = dir ?: return false
        directory.mkdirs()
        val atStoreRate = stemsAtStoreRate(stems) ?: return false
        val hash = stemKeyHash(key)
        val target = File(directory, "$hash.${tier.fileSuffix}.wav")
        val temp = File(directory, "$hash.${tier.fileSuffix}.wav.tmp-${System.nanoTime()}")
        val written = runCatching {
            WavStemFile.Writer(temp).use { writer ->
                writer.writeFrames(atStoreRate.left, atStoreRate.right)
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
        }.isSuccess
        if (!written) {
            runCatching { temp.delete() }
            return false
        }
        knownKeys[hash] = key
        val effectiveTier = tierOf(key) ?: tier
        _states.update { it + (key to StemState.Ready(effectiveTier)) }
        evictIfNeeded()
        return true
    }

    private fun stemsAtStoreRate(stems: StereoStems): StereoStems? {
        if (stems.sampleRate == WavStemFile.SAMPLE_RATE.toDouble()) return stems
        val left = MelSpectrogram.resample(stems.left, stems.sampleRate, WavStemFile.SAMPLE_RATE.toDouble())
            ?: return null
        val right = MelSpectrogram.resample(stems.right, stems.sampleRate, WavStemFile.SAMPLE_RATE.toDouble())
            ?: return null
        return StereoStems(left, right, WavStemFile.SAMPLE_RATE.toDouble())
    }

    /** Records that [key]'s separation is under way. A finished MDX stem is never demoted by this. */
    fun notePreparing(key: String, tier: StemTier, progress: Float) {
        knownKeys[stemKeyHash(key)] = key
        _states.update { current ->
            val existing = current[key]
            if (existing is StemState.Ready && existing.tier == StemTier.MDX && tier != StemTier.MDX) {
                current
            } else {
                current + (key to StemState.Preparing(tier, progress.coerceIn(0f, 1f)))
            }
        }
    }

    /** Records that [key]'s separation failed — unless a stem for it already exists and stays valid. */
    fun noteFailed(key: String) {
        if (stemFile(key) != null) return
        _states.update { it + (key to StemState.Failed) }
    }

    /** Total bytes of every stem file on disk. Touches the filesystem; call it off the main thread. */
    fun totalSizeBytes(): Long {
        val directory = dir ?: return 0L
        return directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    /**
     * Deletes every stem not currently open for playback, and drops the
     * Ready states that described them. An in-flight preparation is left
     * alone in the state map — it will publish its own outcome, and if it
     * writes a stem after the clear, that stem is new work, not a survivor.
     */
    fun clearAll() {
        val directory = dir ?: return
        directory.listFiles()?.forEach { file ->
            if (parseStemFileName(file.name) == null) return@forEach
            if (isOpen(file)) return@forEach
            runCatching { file.delete() }
        }
        _states.update { current ->
            current.filterValues { it !is StemState.Ready } +
                current.filter { (key, value) ->
                    value is StemState.Ready && stemFile(key) != null
                }
        }
    }

    /**
     * Brings the store back under its cap, oldest-modified first, skipping
     * files open for playback. Called after every write; also safe to call
     * any time (a store opened over a directory another build filled past
     * the cap settles on its first write).
     */
    fun evictIfNeeded() {
        val directory = dir ?: return
        var total = totalSizeBytes()
        if (total <= capBytes) return
        val candidates = directory.listFiles()
            ?.filter { it.isFile && parseStemFileName(it.name) != null && !isOpen(it) }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (file in candidates) {
            if (total <= capBytes) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
                val (hash, _) = parseStemFileName(file.name) ?: continue
                val key = knownKeys[hash]
                _states.update { current ->
                    var next = current - hash
                    if (key != null && current[key] is StemState.Ready && stemFile(key) == null) {
                        next = next - key
                    }
                    next
                }
            }
        }
    }

    /** Marks [file] as being read by playback, so eviction and clearing leave it alone. */
    fun registerOpen(file: File) {
        openFiles.merge(file.canonicalPathKey(), 1, Int::plus)
    }

    /** The counterpart to [registerOpen]; one call per reader closed. */
    fun unregisterOpen(file: File) {
        val path = file.canonicalPathKey()
        val count = openFiles[path] ?: return
        if (count <= 1) openFiles.remove(path) else openFiles[path] = count - 1
    }

    private fun isOpen(file: File): Boolean = openFiles.containsKey(file.canonicalPathKey())

    private fun File.canonicalPathKey(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
}
