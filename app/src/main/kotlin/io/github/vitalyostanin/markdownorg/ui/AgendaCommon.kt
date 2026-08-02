package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.AgendaRole
import io.github.vitalyostanin.markdownorg.ui.theme.LocalAgendaColors
import io.github.vitalyostanin.markdownorg.ui.theme.Sizes
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing
import io.github.vitalyostanin.markdownorg.ui.theme.collectionTone
import uniffi.markdown_org_ffi.BulkAction
import uniffi.markdown_org_ffi.Task
import uniffi.markdown_org_ffi.TaskType
import uniffi.markdown_org_ffi.TimestampType

// Everything both layouts draw with, and nothing either of them draws alone.
// The two of them promise to differ in visual language rather than in how much
// they show, and that promise is only as good as the number of places a row is
// described in: what lives here is described once.

/**
 * What an entry is, as far as the agenda is concerned.
 *
 * The glyph rides along with the kind on purpose: colour alone must not carry
 * the distinction (WCAG 1.4.1), so wherever a role is picked the mark that
 * survives without colour is picked with it.
 */
enum class AgendaKind(val glyph: String) {
    DEADLINE("⚑"),
    SCHEDULED("◷"),
    REPEAT("↻"),
    DONE("✓"),
    CANCELLED("⊘"),
}

/**
 * Keyword first, then the repeater, then the timestamp kind: a cancelled task
 * is cancelled whatever its date says, and a repeating one reads as repeating
 * before it reads as scheduled.
 */
fun Task.kind(): AgendaKind = when {
    taskType == TaskType.CANCELLED -> AgendaKind.CANCELLED
    taskType == TaskType.DONE -> AgendaKind.DONE
    timestampRepeater != null -> AgendaKind.REPEAT
    timestampType == TimestampType.DEADLINE -> AgendaKind.DEADLINE
    else -> AgendaKind.SCHEDULED
}

fun AgendaColors.role(kind: AgendaKind): AgendaRole = when (kind) {
    AgendaKind.DEADLINE -> deadline
    AgendaKind.SCHEDULED -> scheduled
    AgendaKind.REPEAT -> repeat
    AgendaKind.DONE -> done
    AgendaKind.CANCELLED -> cancelled
}

/** A finished or dropped task keeps its heading struck through in both layouts. */
fun AgendaKind.decoration(): TextDecoration? = if (this == AgendaKind.DONE ||
    this == AgendaKind.CANCELLED
) {
    TextDecoration.LineThrough
} else {
    null
}

/** How far off the date is, as text: `2 days`, `in 5 days`, or nothing for today. */
@Composable
fun daysLabel(daysOffset: Long): String = when {
    daysOffset < 0 -> pluralStringResource(
        R.plurals.agenda_days_overdue,
        (-daysOffset).toInt(),
        (-daysOffset).toInt(),
    )

    daysOffset > 0 -> pluralStringResource(
        R.plurals.agenda_days_ahead,
        daysOffset.toInt(),
        daysOffset.toInt(),
    )

    else -> ""
}

/**
 * The same count as a sentence, for a screen reader.
 *
 * The visible label counts days and leaves the word "late" to the heading
 * above it. A reader moving row by row never hears that heading, so the row
 * says it in full — `null` where there is nothing to say.
 */
@Composable
fun daysSpoken(daysOffset: Long): String? = if (daysOffset < 0) {
    pluralStringResource(
        R.plurals.agenda_days_overdue_spoken,
        (-daysOffset).toInt(),
        (-daysOffset).toInt(),
    )
} else {
    null
}

/**
 * What a row says about its task: the glyph for its kind, the priority cookie
 * if it carries one, and the heading.
 *
 * Written once for every kind of row — tile, overdue line, band, list row —
 * because this is where the promise the layouts make is kept: they differ in
 * visual language, not in how much they show. As four copies they had already
 * drifted apart, and the overdue row had lost the strike-through of a
 * cancelled task. What is left to the caller is the visual language itself:
 * which colours the row is drawn in and how heavy the heading sits.
 */
@Composable
fun RowScope.TaskRowHead(
    task: Task,
    glyph: Color,
    heading: Color = glyph,
    bold: Boolean = false,
    onDenseFill: Boolean = false,
    /** Which collection the row came from, when there is more than one. */
    collection: CollectionLabel? = null,
) {
    val kind = task.kind()

    Text(
        text = kind.glyph,
        style = MaterialTheme.typography.labelLarge,
        color = glyph,
    )
    Spacer(Modifier.width(Spacing.sm))
    task.priority?.let { priority ->
        PriorityBadge(priority, onDenseFill = onDenseFill)
        Spacer(Modifier.width(Spacing.xs))
    }
    Text(
        text = task.heading,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        color = heading,
        textDecoration = kind.decoration(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    collection?.let { label ->
        Spacer(Modifier.width(Spacing.xs))
        CollectionMark(label, onDenseFill = onDenseFill)
    }
}

/**
 * Which collection a row came from: a dot in the collection's colour and its
 * name.
 *
 * A mark rather than a section heading, because the agenda is one timeline
 * over every collection — grouping the rows by where they live would break the
 * axis the whole layout is built on. It sits at the end of the row, after the
 * heading has had its width: what the row is about comes first.
 *
 * [onDenseFill] is for a row already filled with a solid tone, where the
 * collection's own colour would not read; there the dot takes the row's text
 * colour and the name carries the difference.
 */
@Composable
fun CollectionMark(
    label: CollectionLabel,
    modifier: Modifier = Modifier,
    onDenseFill: Boolean = false,
) {
    val colors = LocalAgendaColors.current
    val tone = if (onDenseFill) colors.onSolid else colors.collectionTone(label.tone)

    Row(
        modifier = modifier.semantics {
            contentDescription = label.name
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.collectionDot)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label.name,
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = Sizes.collectionName),
        )
    }
}

/**
 * The priority cookie, coloured by the priority rather than by the kind of
 * entry: it has to stay legible on top of a tile already filled with the
 * kind's own colour, and A/B/C is its own scale.
 *
 * [onDenseFill] flips it for a row that is already filled with a dense tone —
 * an overdue task. There the badge's own fill would be the same red as the
 * row, leaving the letter floating with no badge around it.
 */
@Composable
fun PriorityBadge(priority: String, modifier: Modifier = Modifier, onDenseFill: Boolean = false) {
    val colors = LocalAgendaColors.current
    val tone: Color = when (priority.uppercase()) {
        "A" -> colors.deadline.tone
        "B" -> colors.repeat.tone
        "C" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Box(
        modifier = modifier
            .size(Sizes.badge)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (onDenseFill) colors.onSolid else tone),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = priority,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            // Each of the three fills is a dense tone, so the label on top
            // inverts with the theme exactly as an overdue row does.
            color = if (onDenseFill) tone else colors.onSolid,
        )
    }
}

/**
 * Where the task is, which is what makes a row unique across a rebuild.
 *
 * The collection is part of it: the same relative path occurs in more than one
 * of them, and two rows sharing a key is not a cosmetic problem — a lazy list
 * refuses to draw a list whose keys repeat.
 */
internal val AgendaRow.key: String get() = "${task.root}:${task.file}:${task.line}"

/**
 * Heading over a group of entries, with how many are in it. The overdue group
 * is the one that gets the tone: the rest are neutral.
 *
 * [folded] turns the heading into the control that folds the group: `null`
 * leaves it a plain label. The count stays visible either way — a folded group
 * still has to say how much is behind it, or folding it hides the fact that it
 * is there at all.
 *
 * [trailing] goes after the count, outside the area that folds the group: what
 * is drawn there is a control of its own, and putting it inside would make
 * every press on it fold the group as well.
 */
@Composable
internal fun SectionLabel(
    text: String,
    count: Int,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    folded: Boolean? = null,
    onFold: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val label = if (folded == true) {
        stringResource(R.string.agenda_section_expand, text)
    } else {
        stringResource(R.string.agenda_section_collapse, text)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (folded == null) {
                        Modifier
                    } else {
                        Modifier.clickable(onClickLabel = label, onClick = onFold)
                    },
                )
                .padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (folded != null) {
                    Text(
                        // A glyph rather than an icon: the row is monospace
                        // type already, and a vector here would be the one
                        // drawable on a screen that has none.
                        text = if (folded) FOLDED_MARK else UNFOLDED_MARK,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                Text(
                    text = text.uppercase(LocalLocale.current.platformLocale),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
                    color = if (warn) {
                        LocalAgendaColors.current.deadline.tone
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        trailing()
    }
}

private const val FOLDED_MARK = "▸"
private const val UNFOLDED_MARK = "▾"

/** What the band is called on screen. */
@get:StringRes
internal val OverdueBand.label: Int
    get() = when (this) {
        OverdueBand.MISSED_REPEAT -> R.string.agenda_section_overdue_repeat
        OverdueBand.RECENT -> R.string.agenda_section_overdue_recent
        OverdueBand.EARLIER -> R.string.agenda_section_overdue_earlier
        OverdueBand.LONG_AGO -> R.string.agenda_section_overdue_long
    }

/**
 * The overdue bands, each under a heading that folds it.
 *
 * Written once for both layouts: they differ in how a row is drawn — [row] is
 * what each passes in — and not in which groups there are or what folds them.
 */
internal fun LazyListScope.overdueBands(
    groups: List<OverdueGroup>,
    collapse: OverdueCollapse,
    onGroupAction: (OverdueGroup, BulkAction) -> Unit = { _, _ -> },
    row: @Composable (AgendaRow) -> Unit,
) {
    for (group in groups) {
        val folded = collapse.isCollapsed(group.band)

        item(key = "band:${group.band.name}") {
            SectionLabel(
                text = stringResource(group.band.label),
                count = group.rows.size,
                warn = group.band != OverdueBand.LONG_AGO,
                folded = folded,
                onFold = { collapse.toggle(group.band) },
                // On the heading whether the band is folded or not: a band
                // folded away is exactly the one answered without reading.
                trailing = { GroupMenu(group.band) { action -> onGroupAction(group, action) } },
            )
        }
        if (!folded) {
            items(group.rows, key = AgendaRow::key) { entry -> row(entry) }
        }
    }
}

/**
 * The small label at the end of a row — a time, a duration, how many days off
 * the date is.
 *
 * On a dense fill it inverts rather than sitting on a translucent veil: a
 * white veil over a dense tone lands around 2.6 against white text, well
 * under the 4.5 the rest of the palette clears.
 */
@Composable
internal fun TrailingTag(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    spoken: String? = null,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .then(
                if (spoken == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = spoken }
                },
            )
            .padding(horizontal = Spacing.sm),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = foreground,
        )
    }
}

/** Placeholder for a column that has nothing to show on this row. */
internal const val EMPTY_CELL = "—"

@Composable
internal fun EmptyAgenda(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.agenda_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = Spacing.md),
    )
}

/**
 * Width the time column starts at, so headings line up down the list.
 *
 * A minimum rather than a fixed size: `09:30` fits in it and `9:30 AM` does
 * not, and a locale that writes the second one would have its times cut off
 * mid-label.
 */
internal val TimeColumnWidth = Sizes.timeColumn

@Composable
internal fun TimeCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.ifEmpty { EMPTY_CELL },
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        modifier = modifier.widthIn(min = TimeColumnWidth),
    )
}
