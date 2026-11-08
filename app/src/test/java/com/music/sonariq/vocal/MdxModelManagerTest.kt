package com.music.sonariq.vocal

import com.music.sonariq.playback.smart.MdxModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [MdxModelManager]'s state machine, through its injected
 * download/verify seams: a download that fails verification deletes its
 * temp file and lands in Failed; one that verifies is renamed into place
 * and lands in Ready; and a second [MdxModelManager.ensureModel] does not
 * download again.
 *
 * The hash itself is not re-tested here — that is `MdxModelVerifyTest`'s
 * coverage of the shared `MdxModel.verify`, which is why the seams exist:
 * the manager's contract is what happens around the check. (One
 * consequence: the fake "model" below is a few bytes, so
 * [MdxModelManager.modelFile]'s published-size gate does not see it; the
 * assertions are on the manager's state and the directory's contents.)
 */
class MdxModelManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        filesDir = File(folder.root, "files")
        modelsDir = File(filesDir, "models")
        MdxModelManager.downloadOverride = null
        MdxModelManager.verifyOverride = null
        MdxModelManager.initForTest(filesDir)
    }

    @Test
    fun `a download that fails verification is deleted and reported failed`() = runBlocking {
        MdxModelManager.downloadOverride = { _, dest, onProgress ->
            dest.writeBytes(byteArrayOf(1, 2, 3))
            onProgress(1f)
        }
        MdxModelManager.verifyOverride = { false }

        MdxModelManager.ensureModel()

        val state = MdxModelManager.state.value
        assertTrue("expected Failed, was $state", state is MdxModelManager.ModelState.Failed)
        // Neither the temp file nor a placed model survives a failed verify.
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
        assertFalse(File(modelsDir, MdxModel.FILE_NAME).exists())
    }

    @Test
    fun `a verified download is renamed into place and reported ready`() = runBlocking {
        MdxModelManager.downloadOverride = { _, dest, onProgress ->
            dest.writeBytes(byteArrayOf(4, 5, 6))
            onProgress(0.5f)
            onProgress(1f)
        }
        MdxModelManager.verifyOverride = { true }

        MdxModelManager.ensureModel()

        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)
        assertTrue(File(modelsDir, MdxModel.FILE_NAME).isFile)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
    }

    @Test
    fun `ensure model is idempotent once the model is in place`() = runBlocking {
        val downloads = AtomicInteger(0)
        MdxModelManager.downloadOverride = { _, dest, _ ->
            downloads.incrementAndGet()
            dest.writeBytes(byteArrayOf(7, 8, 9))
        }
        MdxModelManager.verifyOverride = { true }

        MdxModelManager.ensureModel()
        MdxModelManager.ensureModel()

        assertEquals(1, downloads.get())
        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)
    }

    @Test
    fun `a throwing download lands in failed and cleans up`() = runBlocking {
        MdxModelManager.downloadOverride = { _, dest, _ ->
            dest.writeBytes(byteArrayOf(1))
            error("network went away")
        }
        MdxModelManager.ensureModel()

        assertTrue(MdxModelManager.state.value is MdxModelManager.ModelState.Failed)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
    }

    @Test
    fun `delete model returns to absent`() = runBlocking {
        MdxModelManager.downloadOverride = { _, dest, _ -> dest.writeBytes(byteArrayOf(1, 2)) }
        MdxModelManager.verifyOverride = { true }
        MdxModelManager.ensureModel()
        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)

        MdxModelManager.deleteModel()

        assertEquals(MdxModelManager.ModelState.Absent, MdxModelManager.state.value)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME).exists())
    }
}
