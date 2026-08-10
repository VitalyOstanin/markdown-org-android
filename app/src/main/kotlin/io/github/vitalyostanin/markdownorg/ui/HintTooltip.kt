package io.github.vitalyostanin.markdownorg.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A long press says what a control is for. The icons of the header, the chips
// of the filter, the lines of the sync banner and the notices above the agenda
// all carry a word or a glyph and nothing that explains it; a screen reader is
// told through `contentDescription`, which a sighted user never hears. The
// wording follows the VS Code extension where the two show the same thing.

/**
 * Anything on the screen, with a line behind a long press.
 *
 * The plain sibling of [TaskTooltip], which words a task; this one takes the
 * text already worded. Persistent for the same reason: the line is read, not
 * glanced at, and a tooltip that leaves on its own takes the answer with it.
 *
 * [modifier] goes on the anchor, and a `testTag` belongs on it rather than on
 * the content inside. The box merges the semantics of what it wraps, and a tag
 * is not carried up by that merge: a tag left on a bare `Text` inside stops
 * existing in the merged tree, and the tests that look for it stop finding it.
 * A control that builds its own semantics node — a button, a chip — keeps its
 * tag either way. What must not be passed through is a size or a weight: those
 * reach the box and not the node the caller's own layout measures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HintTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below,
        ),
        tooltip = {
            PlainTooltip { Text(text, style = MaterialTheme.typography.bodySmall) }
        },
        state = rememberTooltipState(isPersistent = true),
        content = content,
    )
}
