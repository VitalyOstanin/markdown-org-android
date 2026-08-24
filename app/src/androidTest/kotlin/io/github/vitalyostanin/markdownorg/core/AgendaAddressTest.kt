package io.github.vitalyostanin.markdownorg.core

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** That what a notification packs is what the screen reads back. */
@RunWith(AndroidJUnit4::class)
class AgendaAddressTest {

    @Test
    fun anEntryComesBackWhole() {
        val target = AgendaTarget(day = DAY, entry = ENTRY)

        assertEquals(target, AgendaAddress.unpack(AgendaAddress.pack(Intent(), target)))
    }

    @Test
    fun aDayWithoutAnEntryComesBackWithoutOne() {
        val target = AgendaTarget(day = DAY, entry = null)

        assertEquals(target, AgendaAddress.unpack(AgendaAddress.pack(Intent(), target)))
    }

    @Test
    fun anIntentSayingNothingNamesNoDay() {
        assertNull(AgendaAddress.unpack(Intent()))
        assertNull(AgendaAddress.unpack(null))
    }

    /**
     * The address is gone once taken out, so a screen handed the same intent
     * again — after a rotation, or on the way back from the recents list —
     * does not reopen what the reader has closed.
     */
    @Test
    fun clearingLeavesNothingToReadBack() {
        val intent = AgendaAddress.pack(Intent(), AgendaTarget(day = DAY, entry = ENTRY))

        AgendaAddress.clear(intent)

        assertNull(AgendaAddress.unpack(intent))
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 8, 24)

        val ENTRY = ReminderEntry(
            root = "/notes/personal",
            file = "inbox.md",
            line = 12u,
            heading = "Call the notary",
        )
    }
}
