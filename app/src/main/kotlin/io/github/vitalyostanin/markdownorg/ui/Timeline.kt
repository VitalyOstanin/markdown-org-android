package io.github.vitalyostanin.markdownorg.ui

import java.time.LocalTime

/**
 * The day as the "Time" layout draws it: what has slipped, what has no hour of
 * its own, and the hour axis in between.
 */
data class Timeline(
    val overdue: List<AgendaRow>,
    val allDay: List<AgendaRow>,
    val axis: List<AxisEntry>,
)

/** One row of the hour axis. */
sealed interface AxisEntry {

    data class Hour(val hour: Int, val entries: List<AgendaRow>) : AxisEntry

    /**
     * An empty stretch shown as a single line instead of blank rows. [until]
     * is exclusive: `Gap(13, 16)` covers 13:00 through 15:59.
     */
    data class Gap(val from: Int, val until: Int) : AxisEntry

    /** The current moment, drawn as a line across the axis. */
    data object Now : AxisEntry
}

/**
 * Empty hours are only worth collapsing once there are enough of them to cost
 * a screenful. Below the threshold the blank rows themselves say "nothing
 * here" faster than a sentence does, and they keep the axis evenly spaced.
 */
private const val COLLAPSE_FROM_HOURS = 3

/**
 * Lays the sections out on an hour axis.
 *
 * [now] is the current time when the agenda is for today, and `null`
 * otherwise: the marker line has no meaning on another day. It is passed in
 * rather than read here so the projection stays a pure function of its input.
 *
 * A timed task whose time cannot be read falls back to the untimed band
 * instead of being dropped — both layouts have to show the same tasks.
 */
fun AgendaSections.toTimeline(now: LocalTime?): Timeline {
    val byHour = sortedMapOf<Int, MutableList<AgendaRow>>()
    val unplaceable = mutableListOf<AgendaRow>()
    for (row in timed) {
        val hour = row.startHour()
        if (hour == null) {
            unplaceable += row
        } else {
            byHour.getOrPut(hour) { mutableListOf() } += row
        }
    }

    return Timeline(
        overdue = overdue,
        allDay = unplaceable + untimed,
        axis = byHour.toAxis().withNow(now),
    )
}

private fun Map<Int, List<AgendaRow>>.toAxis(): List<AxisEntry> {
    if (isEmpty()) {
        return emptyList()
    }
    val first = keys.min()
    val last = keys.max()

    return buildList {
        var emptySince = -1
        for (hour in first..last) {
            val entries = this@toAxis[hour]
            if (entries == null) {
                if (emptySince < 0) {
                    emptySince = hour
                }
                continue
            }
            if (emptySince >= 0) {
                fillEmpty(emptySince, hour)
                emptySince = -1
            }
            add(AxisEntry.Hour(hour, entries))
        }
        // No trailing run to flush: the loop ends on an occupied hour by
        // construction.
    }
}

private fun MutableList<AxisEntry>.fillEmpty(from: Int, until: Int) {
    if (until - from >= COLLAPSE_FROM_HOURS) {
        add(AxisEntry.Gap(from, until))
    } else {
        for (hour in from until until) {
            add(AxisEntry.Hour(hour, emptyList()))
        }
    }
}

/**
 * Places the marker before the first row that starts after the current hour,
 * or at the end when the whole axis is behind. The line therefore sits on an
 * hour boundary rather than at the exact minute: within an hour the entries
 * are already in order, and a line cutting through a tile would cost more
 * than it says.
 */
private fun List<AxisEntry>.withNow(now: LocalTime?): List<AxisEntry> {
    if (now == null || isEmpty()) {
        return this
    }
    val index = indexOfFirst { entry ->
        when (entry) {
            is AxisEntry.Hour -> entry.hour > now.hour
            is AxisEntry.Gap -> entry.from > now.hour
            AxisEntry.Now -> false
        }
    }
    return toMutableList().apply { add(if (index < 0) size else index, AxisEntry.Now) }
}

/** Hour of the row's timestamp, which the core writes as `HH:MM`. */
private fun AgendaRow.startHour(): Int? =
    task.timestampTime?.substringBefore(':')?.toIntOrNull()
