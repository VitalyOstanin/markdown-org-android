package io.github.vitalyostanin.markdownorg.core

/**
 * The sample notes as a test spells them.
 *
 * Written here rather than read from the resources: these tests run on the
 * JVM, where there is no context to read a string with. What the shipped
 * strings produce is checked on a device, in `NotesStoreTest`.
 */
internal val testWording = SampleWording(
    heading = "Sample notes",
    certificate = "Renew the TLS certificate",
    releaseNotes = "Review the release notes",
    teamSync = "Team sync",
    dependencyPins = "Update the dependency pins",
    quarterlyReport = "Quarterly report",
    archivedBranch = "Archive the old branch",
    stagingHost = "Migrate the staging host",
)
