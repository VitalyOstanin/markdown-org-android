package io.github.vitalyostanin.markdownorg.ui

import uniffi.markdown_org_ffi.FileRollback

/**
 * What one edit did to one note, and what it takes to put it back.
 *
 * The offer to undo is what makes a tap safe to make: the actions are one tap
 * apart on a sheet, a date is set by a calendar that opens on today, and the
 * note behind them is a file in a repository the user shares between devices.
 * Without the offer, taking a wrong tap back means finding the note, finding
 * the line, and remembering what it said.
 *
 * The directory travels with the rollback for the reason it does for a group:
 * the file is named relative to a collection, and the same relative path in
 * another collection is another note.
 */
data class EditResult(
    /** The collection's directory, as the task carried it. */
    val root: String,
    /** The task the edit was aimed at, for the commit the undo makes. */
    val heading: String,
    val rollback: FileRollback,
)
