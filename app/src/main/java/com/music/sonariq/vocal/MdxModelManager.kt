package com.music.sonariq.vocal

import android.content.Context
import com.music.sonariq.data.Http
import com.music.sonariq.playback.smart.MdxModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Owns the downloadable MDX-Net pro model: `filesDir/models/<MdxModel.FILE_NAME>`.
 *
 * The model is ~64 MB — far too big to bundle — so it is fetched on demand,
 * verified against [MdxModel]'s published size and SHA-256 before it is
 * allowed into place, and only ever run through a separator once [state] is
 * [ModelState.Ready]. A truncated or substituted download deletes itself and
 * reports [ModelState.Failed]; nothing partial is ever renamed into place.
 *
 * Deleting the model does not invalidate stems already separated with it:
 * they are plain WAVs in [StemStore] and keep playing — see [deleteModel].
 */
object MdxModelManager {

    sealed interface ModelState {
        data object Absent : ModelState
        data class Downloading(val progress: Float) : ModelState
        data object Verifying : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private const val DIRECTORY_NAME = "models"
    private const val TEMP_SUFFIX = ".part"

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Serialises downloads; a second [ensureModel] while one runs waits, then re-checks. */
    private val downloadMutex = Mutex()

    /**
     * Test seams. Production leaves both null: downloads go through
     * [Http.client] (the app's one OkHttp stack) and verification is
     * [MdxModel.verify]. The manager's own state machine — temp file,
     * verify gate, rename, failure cleanup — is what the tests exercise,
     * not the hash itself (that is `MdxModelVerifyTest`'s job).
     */
    internal var downloadOverride: (suspend (url: String, dest: File, onProgress: (Float) -> Unit) -> Unit)? = null
    internal var verifyOverride: ((File) -> Boolean)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        _state.value = if (modelFile() != null) ModelState.Ready else ModelState.Absent
    }

    /** Test seam: a manager rooted at [context]-free state, with both seams replaceable. */
    internal fun initForTest(filesDir: File) {
        appContext = null
        testFilesDir = filesDir
        _state.value = if (modelFile() != null) ModelState.Ready else ModelState.Absent
    }

    @Volatile
    private var testFilesDir: File? = null

    private fun modelsDir(): File? {
        val base = testFilesDir ?: appContext?.filesDir ?: return null
        return File(base, DIRECTORY_NAME).apply { mkdirs() }
    }

    /**
     * The verified model file, or null. After a download this means the file
     * passed [MdxModel.verify]; restored at [init] it means a file of exactly
     * the published size under the model's name — the size check is the cheap
     * gate, and re-hashing 64 MB on every cold start to re-prove a file this
     * manager itself verified before renaming is not a trade worth making.
     */
    fun modelFile(): File? {
        val dir = modelsDir() ?: return null
        val file = File(dir, MdxModel.FILE_NAME)
        return file.takeIf { it.isFile && it.length() == MdxModel.SIZE_BYTES }
    }

    /** Whether the model is in place (same test as [modelFile]). */
    val isReady: Boolean get() = modelFile() != null

    /**
     * Downloads and verifies the model if it is not already in place.
     *
     * Idempotent and deduped: concurrent callers queue on [downloadMutex],
     * and every caller after the first to land finds the model Ready and
     * returns without touching the network. Failures land in
     * [ModelState.Failed] rather than throwing — a background job asking for
     * the pro tier treats a failed download as "stay on the bundled tier",
     * not as an error worth surfacing anywhere but this state.
     */
    /** True when a model this manager verified is sitting in place, whatever a later size probe would say. */
    private fun placedModelExists(): Boolean {
        if (_state.value != ModelState.Ready) return false
        val dir = modelsDir() ?: return false
        return File(dir, MdxModel.FILE_NAME).isFile
    }

    suspend fun ensureModel() {
        if (modelFile() != null || placedModelExists()) {
            _state.value = ModelState.Ready
            return
        }
        downloadMutex.withLock {
            if (modelFile() != null || placedModelExists()) {
                _state.value = ModelState.Ready
                return
            }
            downloadAndVerify()
        }
    }

    private suspend fun downloadAndVerify() {
        val dir = modelsDir() ?: run {
            _state.value = ModelState.Failed("No storage available for the model")
            return
        }
        val temp = File(dir, MdxModel.FILE_NAME + TEMP_SUFFIX)
        val target = File(dir, MdxModel.FILE_NAME)
        try {
            _state.value = ModelState.Downloading(0f)
            val download = downloadOverride
            if (download != null) {
                download(MdxModel.DOWNLOAD_URL, temp) { progress ->
                    _state.value = ModelState.Downloading(progress.coerceIn(0f, 1f))
                }
            } else {
                httpDownload(temp) { progress ->
                    _state.value = ModelState.Downloading(progress)
                }
            }
            _state.value = ModelState.Verifying
            val verified = verifyOverride?.invoke(temp) ?: MdxModel.verify(temp)
            if (!verified) {
                runCatching { temp.delete() }
                _state.value = ModelState.Failed("Downloaded model did not verify")
                return
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            _state.value = ModelState.Ready
        } catch (e: CancellationException) {
            runCatching { temp.delete() }
            if (_state.value !is ModelState.Ready) _state.value = ModelState.Absent
            throw e
        } catch (e: Exception) {
            runCatching { temp.delete() }
            _state.value = ModelState.Failed(e.message ?: "Model download failed")
        }
    }

    /**
     * The app's existing HTTP idiom: the shared [Http.client], a plain GET,
     * streamed to disk with progress against the published size — the same
     * shape as `Downloader.fetchDirect`. Runs on [Dispatchers.IO]; the call
     * itself blocks, as OkHttp's execute does throughout this app.
     */
    private suspend fun httpDownload(dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(MdxModel.DOWNLOAD_URL).build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Model download failed (HTTP ${response.code})")
                val body = response.body ?: error("Model download failed: empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: MdxModel.SIZE_BYTES
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (writtenLength(dest) == 0L) error("Model download failed: nothing was sent")
            }
        }
    }

    private fun writtenLength(file: File): Long = if (file.isFile) file.length() else 0L

    /**
     * Removes the model and drops [state] back to Absent. Stems already
     * separated stay valid — they are finished WAVs, not views over the
     * model — so [StemStore] is deliberately not touched here.
     */
    fun deleteModel() {
        val dir = modelsDir() ?: return
        runCatching { File(dir, MdxModel.FILE_NAME).delete() }
        runCatching { File(dir, MdxModel.FILE_NAME + TEMP_SUFFIX).delete() }
        _state.value = ModelState.Absent
    }
}
