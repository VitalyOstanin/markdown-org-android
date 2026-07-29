package io.github.vitalyostanin.markdownorg.ui

import io.github.vitalyostanin.markdownorg.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the agenda says about the files behind it.
 *
 * A note the walk skipped is otherwise indistinguishable from a note that is
 * not there: the tasks are simply missing, with no reason and no sign.
 */
class ScanNoticeTest {

    @Test
    fun `a clean walk has nothing to say`() {
        assertTrue(agenda(day()).notices().isEmpty())
    }

    @Test
    fun `a file in another encoding is named as such`() {
        val notices = agenda(day(), stats = cleanScan(filesNotUtf8 = 2u)).notices()

        assertEquals(
            listOf(ScanNotice.Counted(R.plurals.agenda_skipped_encoding, 2)),
            notices,
        )
    }

    @Test
    fun `an unreadable file and one in another encoding are reported apart`() {
        val notices = agenda(
            day(),
            stats = cleanScan(filesFailed = 1u, filesNotUtf8 = 1u),
        ).notices()

        assertEquals(
            listOf(
                ScanNotice.Counted(R.plurals.agenda_skipped_encoding, 1),
                ScanNotice.Counted(R.plurals.agenda_unreadable, 1),
            ),
            notices,
        )
    }

    @Test
    fun `a file too large to read is its own reason`() {
        val notices = agenda(day(), stats = cleanScan(filesTooLarge = 3u)).notices()

        assertEquals(listOf(ScanNotice.Counted(R.plurals.agenda_skipped_size, 3)), notices)
    }

    @Test
    fun `a path that is not utf8 is reported because its tasks cannot be edited`() {
        val notices = agenda(day(), stats = cleanScan(nonutf8Paths = 1u)).notices()

        assertEquals(listOf(ScanNotice.Counted(R.plurals.agenda_unnamed_paths, 1)), notices)
    }

    @Test
    fun `a truncated list says so without a count`() {
        val notices = agenda(day(), stats = cleanScan(truncated = true)).notices()

        assertEquals(listOf(ScanNotice.Flag(R.string.agenda_truncated)), notices)
    }

    @Test
    fun `a task whose path carries the replacement character cannot be edited`() {
        assertTrue(task(file = "bad�name.md").isEditable().not())
        assertTrue(task(file = "notes.md").isEditable())
    }
}
