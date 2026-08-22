package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.runtime.Immutable
import io.github.vitalyostanin.markdownorg.core.DEFAULT_DIGEST_TIME
import io.github.vitalyostanin.markdownorg.core.NotesCollection
import java.time.LocalTime

/**
 * What the settings form opens on, as it is stored.
 *
 * Grouped for the reason [SyncFormValues] is: the screen took twenty-eight
 * parameters, most of them strings and booleans, and a call that swapped two of
 * them compiled. These four objects are what the screen is about — what is
 * stored, which collections there are, where the notes may be kept, and what a
 * report about this build would carry — and each is passed as one thing.
 */
@Immutable
data class SettingsInitial(
    val url: String = "",
    val branch: String = "",
    val notesPath: String = "",
    /** What the collection being edited is called, as it is stored. */
    val name: String = "",
    /** The file it receives new tasks in, as it is stored. */
    val inbox: String = "",
    val hasToken: Boolean = false,
    /** A private key for an `ssh://` remote is stored, whatever it is. */
    val hasKey: Boolean = false,
    /** The public half of a key made here, for pasting into a server. */
    val publicKey: String = "",
    /** The server key the remote is known by, empty until one is vouched for. */
    val knownHost: String = "",
    /** The notes are kept on this device on purpose, and no remote is wanted. */
    val storesLocally: Boolean = false,
)

/** Every collection there is, and what the form may do to the set. */
@Immutable
data class CollectionsUi(
    /** In the order the agenda keeps them. */
    val all: List<NotesCollection> = emptyList(),
    /** Which of [all] the rest of the form is about. */
    val editingId: String = "",
    val onEdit: (String) -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
)

/** Where the notes may live, and the two ways of being allowed to keep them there. */
@Immutable
data class StorageUi(
    val ownNotesPath: String = "",
    val granted: Boolean = false,
    val onRequestPermission: () -> Unit = {},
    /** A directory chosen in the system's picker, until it has been taken in. */
    val picked: String? = null,
    val onPick: () -> Unit = {},
    val onPickedTaken: () -> Unit = {},
)

/**
 * How the agenda is drawn, as the form offers it.
 *
 * Together for the reason the objects above are: these are choices about the
 * screen rather than about the checkout, they take effect on the tick, and a
 * fifth loose boolean beside four loose lambdas is what the grouping was
 * introduced to stop.
 */
@Immutable
data class AgendaUi(
    /** Whether a day keeps its section headings. */
    val grouped: Boolean = true,
    val onGroupedChange: (Boolean) -> Unit = {},
    /** Whether a month is a calendar rather than the list of its days. */
    val monthAsGrid: Boolean = true,
    val onMonthAsGridChange: (Boolean) -> Unit = {},
    /** Which weekday a week is read as beginning on; costs a scan to change. */
    val weekStart: WeekStart = WeekStart.AUTO,
    val onWeekStartChange: (WeekStart) -> Unit = {},
)

/**
 * Whether the reader is told what is coming, and on what terms.
 *
 * Grouped for the reason [AgendaUi] is, and kept apart from it because the two
 * are answered at different moments: the agenda choices change what is on
 * screen while it is being looked at, these decide what happens while it is
 * not.
 *
 * The two "needs" flags are the platform's answers rather than the reader's:
 * notifications may be refused and exact alarms may be disallowed, and either
 * leaves the choices above meaning less than they say. They are stated here so
 * the section can say so and offer the screen that grants them.
 */
@Immutable
data class RemindersUi(
    val enabled: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    /** How long before a timed entry it is announced. */
    val lead: ReminderLead = ReminderLead.FIFTEEN,
    val onLeadChange: (ReminderLead) -> Unit = {},
    /** Whether the moment itself is announced as well as the lead time. */
    val alsoAtStart: Boolean = false,
    val onAlsoAtStartChange: (Boolean) -> Unit = {},
    /** The hour the day's digest is raised at. */
    val digestAt: LocalTime = DEFAULT_DIGEST_TIME,
    val onDigestChange: (LocalTime) -> Unit = {},
    /** Notifications are refused, so nothing raised here would be seen. */
    val needsNotifications: Boolean = false,
    val onGrantNotifications: () -> Unit = {},
    /** Exact alarms are disallowed, so a timed entry is announced late. */
    val needsExactAlarms: Boolean = false,
    val onAllowExactAlarms: () -> Unit = {},
)

/** What is left of the last run, and the notices of what the APK carries. */
@Immutable
data class DiagnosticsUi(
    val crash: String? = null,
    val onForgetCrash: () -> Unit = {},
    val onOpenLicences: () -> Unit = {},
)
