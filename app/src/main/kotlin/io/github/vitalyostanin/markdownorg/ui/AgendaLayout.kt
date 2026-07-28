package io.github.vitalyostanin.markdownorg.ui

/**
 * How the same agenda is drawn. Both layouts show every task with the same
 * wording: what differs is the visual language, not how much is on screen.
 */
enum class AgendaLayout {
    /**
     * An hour axis with a filled tile per entry. Shows how full the day is and
     * where the free stretches are, at the cost of vertical room.
     */
    TIME,

    /** One line per task. Denser, but says nothing about free time. */
    LIST,
    ;

    fun toggled(): AgendaLayout = if (this == TIME) LIST else TIME
}
