package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two replans at once, which is what the four callers of one make possible.
 *
 * The alarms live in the platform and the count of them in a preferences file:
 * if the two come apart, the alarms the count does not cover are announced for
 * entries long since closed, and switching reminders off does not take them
 * away either.
 */
class PlanSlotsTest {

    /** The alarms the platform is holding, by number. */
    private val live = Collections.synchronizedSet(mutableSetOf<Int>())

    private val held = AtomicInteger(0)

    private val counter = object : PlanSlots.HeldCount {
        override fun read(): Int = held.get()
        override fun write(count: Int) = held.set(count)
    }

    @Test
    fun `two replans leave the platform holding exactly what the count says`() {
        // The interleaving the four callers make possible: one replace is
        // between cancelling and placing when the other runs through whole.
        // Without a lock the platform ends up holding twelve alarms under a
        // count of eight, and alarms 8..11 are then beyond every later
        // replace and beyond switching reminders off.
        live.addAll(0 until 5)
        held.set(5)
        val slots = PlanSlots(counter)
        val betweenSteps = CountDownLatch(1)
        val otherDone = CountDownLatch(1)

        val twelve = Thread {
            slots.replace(
                planned = 12,
                cancel = { index -> live.remove(index) },
                place = { index ->
                    if (index == 0) {
                        betweenSteps.countDown()
                        // Times out under a lock, which is the point: the
                        // other replace cannot be inside at the same time.
                        otherDone.await(500, TimeUnit.MILLISECONDS)
                    }
                    live.add(index)
                },
            )
        }
        twelve.start()
        betweenSteps.await()

        val eight = Thread {
            slots.replace(
                planned = 8,
                cancel = { index -> live.remove(index) },
                place = { index -> live.add(index) },
            )
            otherDone.countDown()
        }
        eight.start()
        eight.join()
        twelve.join()

        assertEquals(
            "alarms the count does not cover are left in the platform for good",
            (0 until held.get()).toSet(),
            live.toSet(),
        )
    }

    @Test
    fun `a plan the process dies inside still leaves a count that covers it`() {
        // The count is written before the alarms, so a replace that never
        // finished is still fully cancellable. Written after them, the alarms
        // placed before the kill would be held under numbers nothing knows.
        val slots = PlanSlots(counter)
        val placed = mutableListOf<Int>()

        runCatching {
            slots.replace(
                planned = 4,
                cancel = { index -> live.remove(index) },
                place = { index ->
                    if (index == 2) error("the process went away")
                    placed += index
                    live.add(index)
                },
            )
        }

        assertEquals(listOf(0, 1), placed)
        assertEquals(4, held.get())
    }

    @Test
    fun `clearing drops every alarm the count covers`() {
        live.addAll(0 until 3)
        held.set(3)
        val slots = PlanSlots(counter)

        slots.clear { index -> live.remove(index) }

        assertEquals(emptySet<Int>(), live.toSet())
        assertEquals(0, held.get())
    }
}
