package io.github.vitalyostanin.markdownorg.ui

import android.content.Context
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.core.SampleWording

/**
 * The sample notes in the language of the device.
 *
 * Read here rather than inside the working copy: the directory writes files
 * and knows the format, and the resources belong to the screens. Taken once,
 * when the model is built, which is enough for what this is: the sample is
 * written on the first run into a directory that has no notes, and a language
 * changed after that leaves it alone anyway — the file is the user's from the
 * moment it is written, and rewriting it in another language would throw away
 * whatever they had added to it.
 */
internal fun sampleWording(context: Context): SampleWording = SampleWording(
    heading = context.getString(R.string.sample_heading),
    certificate = context.getString(R.string.sample_certificate),
    releaseNotes = context.getString(R.string.sample_release_notes),
    teamSync = context.getString(R.string.sample_team_sync),
    dependencyPins = context.getString(R.string.sample_dependency_pins),
    quarterlyReport = context.getString(R.string.sample_quarterly_report),
    archivedBranch = context.getString(R.string.sample_archived_branch),
    stagingHost = context.getString(R.string.sample_staging_host),
)
