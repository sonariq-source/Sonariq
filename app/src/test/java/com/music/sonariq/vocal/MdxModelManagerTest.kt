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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [MdxModelManager]'s state machine, through its injected
 * bundled-source/verify seams: a bundled copy that fails verification
 * deletes its temp file and lands in Failed; one that verifies is
 * renamed into place and lands in Ready; and a second
 * [MdxModelManager.ensureModel] does not copy again.
 *
 * The model now ships inside the app package, so the "source" the seam
 * stands in for is the bundled asset, not a download. The hash itself is
 * not re-tested here — that is `MdxModelVerifyTest`'s coverage of the
 * shared `MdxModel.verify`, which is why the seams exist: the manager's
 * contract is what happens around the check. (One consequence: the fake
 * "model" below is a few bytes, so [MdxModelManager.modelFile]'s
 * published-size gate does not see it; the assertions are on the
 * manager's state and the directory's contents.)
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
        MdxModelManager.bundledSourceOverride = null
        MdxModelManager.verifyOverride = null
        MdxModelManager.initForTest(filesDir)
    }

    @Test
    fun `a bundled copy that fails verification is deleted and reported failed`() = runBlocking {
        MdxModelManager.bundledSourceOverride = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        MdxModelManager.verifyOverride = { false }

        MdxModelManager.ensureModel()

        val state = MdxModelManager.state.value
        assertTrue("expected Failed, was $state", state is MdxModelManager.ModelState.Failed)
        // Neither the temp file nor a placed model survives a failed verify.
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
        assertFalse(File(modelsDir, MdxModel.FILE_NAME).exists())
    }

    @Test
    fun `a verified bundled copy is renamed into place and reported ready`() = runBlocking {
        MdxModelManager.bundledSourceOverride = { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }
        MdxModelManager.verifyOverride = { true }

        MdxModelManager.ensureModel()

        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)
        assertTrue(File(modelsDir, MdxModel.FILE_NAME).isFile)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
    }

    @Test
    fun `ensure model is idempotent once the model is in place`() = runBlocking {
        val copies = AtomicInteger(0)
        MdxModelManager.bundledSourceOverride = {
            copies.incrementAndGet()
            ByteArrayInputStream(byteArrayOf(7, 8, 9))
        }
        MdxModelManager.verifyOverride = { true }

        MdxModelManager.ensureModel()
        MdxModelManager.ensureModel()

        assertEquals(1, copies.get())
        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)
    }

    @Test
    fun `a missing bundled model lands in failed and cleans up`() = runBlocking {
        // No override and no Context: the bundled asset cannot be opened.
        MdxModelManager.ensureModel()

        assertTrue(MdxModelManager.state.value is MdxModelManager.ModelState.Failed)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME + ".part").exists())
    }

    @Test
    fun `delete model returns to absent and the next ensure copies again`() = runBlocking {
        val copies = AtomicInteger(0)
        MdxModelManager.bundledSourceOverride = {
            copies.incrementAndGet()
            ByteArrayInputStream(byteArrayOf(1, 2))
        }
        MdxModelManager.verifyOverride = { true }
        MdxModelManager.ensureModel()
        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)

        MdxModelManager.deleteModel()

        assertEquals(MdxModelManager.ModelState.Absent, MdxModelManager.state.value)
        assertFalse(File(modelsDir, MdxModel.FILE_NAME).exists())

        // Deleting only ever removed the prepared copy: the bundled file
        // is still in the package, so preparing again works — no network.
        MdxModelManager.ensureModel()
        assertEquals(MdxModelManager.ModelState.Ready, MdxModelManager.state.value)
        assertEquals(2, copies.get())
    }
}
