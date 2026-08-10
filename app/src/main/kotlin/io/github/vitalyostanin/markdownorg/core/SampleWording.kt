package io.github.vitalyostanin.markdownorg.core

/**
 * The words the sample notes are written in.
 *
 * Passed in rather than held here, because the sample is the first thing a
 * fresh install shows and it has to be in the language of the device: the
 * strings come from the resources, and this carries them into the directory
 * where the file is written. Only the wording travels — the keywords, the
 * brackets and the dates around it are the format the extractor reads back,
 * and translating those would produce a file the agenda cannot show.
 *
 * A field per line rather than a list, so that a translation cannot silently
 * reorder them into the wrong dates.
 */
data class SampleWording(
    /** The title of the file, and the only heading that is not a task. */
    val heading: String,
    /** Overdue, so a fresh agenda shows what an overdue band looks like. */
    val certificate: String,
    /** Today at a time, which is what the time layout is built around. */
    val releaseNotes: String,
    /** Today, and repeating: the only example of a repeater the sample gives. */
    val teamSync: String,
    /** Today without a time, so both halves of the day are populated. */
    val dependencyPins: String,
    /** Days away, so the agenda is not only about today. */
    val quarterlyReport: String,
    /** Done, with the closing date the inactive brackets are for. */
    val archivedBranch: String,
    /** Cancelled, the third state a task can be in. */
    val stagingHost: String,
)
