package io.github.vitalyostanin.markdownorg.ui

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What a note looks like on its way to another application.
 *
 * The provider is the part that cannot be checked by reading the code: an
 * authority that does not match the manifest, or a root the paths file does
 * not cover, both fail only when a URI is actually asked for. So the tests
 * ask for one, and then read the note back through it the way the receiving
 * application would.
 */
class ExternalNoteTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "external-note")

    @Before
    @After
    fun clean() {
        scratch.deleteRecursively()
    }

    private fun note(text: String = "# Groceries\n\n- [ ] TODO milk\n"): File {
        scratch.mkdirs()
        return File(scratch, "groceries.md").apply { writeText(text) }
    }

    @Test
    fun theNoteTravelsAsAContentUriOfThisApplicationsProvider() {
        val intent = ExternalNote.intentFor(context, note())

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.notes", intent.data?.authority)
        assertEquals(ExternalNote.MIME, intent.type)
    }

    @Test
    fun theReceivingApplicationMayBothReadAndWriteIt() {
        val intent = ExternalNote.intentFor(context, note())

        // Read alone would make the offer a viewer's: the point of handing the
        // note over is that the other application edits it.
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun whatComesBackThroughTheUriIsTheNoteItself() {
        val text = "# Keys\n\n- one\n- two\n"
        val intent = ExternalNote.intentFor(context, note(text))

        val read = context.contentResolver.openInputStream(intent.data!!)!!
            .use { stream -> stream.readBytes().decodeToString() }

        assertEquals(text, read)
    }

    /**
     * A note outside the application's own storage still resolves.
     *
     * The notes directory is wherever the user put it, which is why the paths
     * file names the filesystem root; a provider configured for the private
     * directory alone would refuse every chosen location, and only at the
     * moment of the tap.
     */
    @Test
    fun aNoteKeptOutsideTheApplicationsOwnStorageIsHandedOverTheSameWay() {
        val outside = File(context.getExternalFilesDir(null), "outside/notes")
        outside.mkdirs()
        val note = File(outside, "elsewhere.md").apply { writeText("# Elsewhere\n") }

        try {
            val intent = ExternalNote.intentFor(context, note)

            assertEquals("content", intent.data?.scheme)
            assertTrue(
                "the URI must name the note",
                intent.data.toString().endsWith("elsewhere.md"),
            )
        } finally {
            outside.parentFile?.deleteRecursively()
        }
    }
}
