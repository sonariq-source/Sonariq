package com.music.sonariq.vocal

import android.content.Context
import com.music.sonariq.playback.smart.MdxModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Owns the MDX-Net pro model: `filesDir/models/<MdxModel.FILE_NAME>`.
 *
 * The model ships inside the phone app's assets (fetched and verified at
 * build time — see the `fetchVocalModel` task in the app build script),
 * so there is no download on the device: the first time the pro tier is
 * needed, the bundled file is copied into place and verified against
 * [MdxModel]'s published size and SHA-256 before it is allowed to stay.
 * Only then does [state] become [ModelState.Ready] and a separator may
 * run on it. A copy that fails verification deletes itself and reports
 * [ModelState.Failed]; nothing partial is ever renamed into place.
 *
 * Deleting the model does not invalidate stems already separated with it:
 * they are plain WAVs in [StemStore] and keep playing — see [deleteModel].
 * A later [ensureModel] simply copies the bundled file again.
 */
object MdxModelManager {

    sealed interface ModelState {
        data object Absent : ModelState
        data class Preparing(val progress: Float) : ModelState
        data object Verifying : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private const val DIRECTORY_NAME = "models"
    private const val TEMP_SUFFIX = ".part"

    /** Where the bundled model sits inside the phone app's assets. */
    const val ASSET_PATH = "ai_model/" + MdxModel.FILE_NAME

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Serialises preparations; a second [ensureModel] while one runs waits, then re-checks. */
    private val prepareMutex = Mutex()

    /**
     * Test seams. Production leaves both null: the copy source is the
     * bundled asset and verification is [MdxModel.verify]. The manager's
     * own state machine — temp file, verify gate, rename, failure
     * cleanup — is what the tests exercise, not the hash itself (that is
     * `MdxModelVerifyTest`'s job).
     */
    internal var bundledSourceOverride: (() -> InputStream?)? = null
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
     * The verified model file, or null. After a preparation this means the
     * file passed [MdxModel.verify]; restored at [init] it means a file of
     * exactly the published size under the model's name — the size check
     * is the cheap gate, and re-hashing 64 MB on every cold start to
     * re-prove a file this manager itself verified before renaming is not
     * a trade worth making.
     */
    fun modelFile(): File? {
        val dir = modelsDir() ?: return null
        val file = File(dir, MdxModel.FILE_NAME)
        return file.takeIf { it.isFile && it.length() == MdxModel.SIZE_BYTES }
    }

    /** Whether the model is in place (same test as [modelFile]). */
    val isReady: Boolean get() = modelFile() != null

    /** True when a model this manager verified is sitting in place, whatever a later size probe would say. */
    private fun placedModelExists(): Boolean {
        if (_state.value != ModelState.Ready) return false
        val dir = modelsDir() ?: return false
        return File(dir, MdxModel.FILE_NAME).isFile
    }

    /**
     * Copies the bundled model into place and verifies it, if it is not
     * already there. Idempotent and deduped: concurrent callers queue on
     * [prepareMutex], and every caller after the first to land finds the
     * model Ready and returns without touching the asset. Failures land
     * in [ModelState.Failed] rather than throwing — a background job
     * asking for the pro tier treats a failed preparation as "stay on the
     * bundled UMX tier", not as an error worth surfacing anywhere but
     * this state.
     */
    suspend fun ensureModel() {
        if (modelFile() != null || placedModelExists()) {
            _state.value = ModelState.Ready
            return
        }
        prepareMutex.withLock {
            if (modelFile() != null || placedModelExists()) {
                _state.value = ModelState.Ready
                return
            }
            copyBundledAndVerify()
        }
    }

    private fun openBundled(): InputStream? =
        bundledSourceOverride?.invoke()
            ?: appContext?.assets?.open(ASSET_PATH)

    private suspend fun copyBundledAndVerify() {
        val dir = modelsDir() ?: run {
            _state.value = ModelState.Failed("No storage available for the model")
            return
        }
        val temp = File(dir, MdxModel.FILE_NAME + TEMP_SUFFIX)
        val target = File(dir, MdxModel.FILE_NAME)
        try {
            _state.value = ModelState.Preparing(0f)
            withContext(Dispatchers.IO) {
                val source = openBundled()
                    ?: error("Bundled model is missing from the app package")
                source.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read
                            _state.value = ModelState.Preparing(
                                (written.toFloat() / MdxModel.SIZE_BYTES).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            _state.value = ModelState.Verifying
            val verified = verifyOverride?.invoke(temp) ?: MdxModel.verify(temp)
            if (!verified) {
                runCatching { temp.delete() }
                _state.value = ModelState.Failed("Bundled model did not verify")
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
            _state.value = ModelState.Failed(e.message ?: "Model preparation failed")
        }
    }

    /**
     * Removes the model and drops [state] back to Absent. Stems already
     * separated stay valid — they are finished WAVs, not views over the
     * model — so [StemStore] is deliberately not touched here. The model
     * itself is not gone from the app: the next [ensureModel] copies the
     * bundled file again.
     */
    fun deleteModel() {
        val dir = modelsDir() ?: return
        runCatching { File(dir, MdxModel.FILE_NAME).delete() }
        runCatching { File(dir, MdxModel.FILE_NAME + TEMP_SUFFIX).delete() }
        _state.value = ModelState.Absent
    }
}
