package io.github.vitalyostanin.markdownorg.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.vitalyostanin.markdownorg.R
import io.github.vitalyostanin.markdownorg.ui.theme.Spacing

/**
 * What one phrase field is worded and addressed by.
 *
 * The creation screen and the actions sheet ask for the same thing in
 * different words -- one writes a task, the other changes the one on the
 * screen -- and their tests reach the same three controls under different
 * tags. Both are the caller's to state; everything else about the field is
 * the same and is drawn once, below.
 */
data class PhraseFieldLabels(
    /** Label over the field. */
    @param:StringRes val label: Int,
    /** What the phone shows while it listens. */
    @param:StringRes val prompt: Int,
    /** The button that listens, and what it explains about itself. */
    @param:StringRes val speak: Int,
    @param:StringRes val speakHint: Int,
    /** The button that hands the sentence over, and its explanation. */
    @param:StringRes val apply: Int,
    @param:StringRes val applyHint: Int,
    /** Test tags of the field and of the two buttons. */
    val fieldTag: String,
    val speakTag: String,
    val applyTag: String,
)

/**
 * A sentence, typed or spoken, and the two buttons that act on it.
 *
 * The field takes the width and the buttons stand under it. Beside it they
 * leave a column narrow enough to set its own label over three lines -- one
 * button did fit, and the second is what turned the invitation into a stack of
 * words on a phone held upright.
 *
 * Speaking only fills this field: what the phone heard is read by the same
 * button, so a word it got wrong is corrected in one line rather than across
 * the fields it would otherwise have been scattered into. What was heard joins
 * what is already there rather than replacing it, because a sentence may be
 * said in two goes and a word corrected by hand is not worth losing.
 *
 * The field is emptied once [onApply] has taken the sentence: what is said
 * next is a new sentence rather than an edit of the last one.
 */
@Composable
fun PhraseField(labels: PhraseFieldLabels, dictation: Dictation, onApply: (String) -> Unit) {
    var phrase by rememberSaveable { mutableStateOf("") }
    // Said once the phone has answered that it cannot listen, and kept until
    // the next attempt: a line under the field rather than a message that goes
    // away on its own, because what to do instead is to type here.
    var unheard by rememberSaveable { mutableStateOf(false) }
    val prompt = stringResource(labels.prompt)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            label = { Text(stringResource(labels.label)) },
            supportingText = if (unheard) {
                { Text(stringResource(R.string.create_phrase_unheard)) }
            } else {
                null
            },
            isError = unheard,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .withoutAutofill()
                .testTag(labels.fieldTag),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HintTooltip(stringResource(labels.speakHint)) {
                TextButton(
                    onClick = {
                        unheard = !dictation.listen(prompt) { heard ->
                            phrase = listOf(phrase.trim(), heard.trim())
                                .filter { it.isNotEmpty() }
                                .joinToString(" ")
                        }
                    },
                    modifier = Modifier.testTag(labels.speakTag),
                ) {
                    Text(stringResource(labels.speak))
                }
            }
            HintTooltip(stringResource(labels.applyHint)) {
                TextButton(
                    onClick = {
                        onApply(phrase)
                        phrase = ""
                        unheard = false
                    },
                    enabled = phrase.isNotBlank(),
                    modifier = Modifier.testTag(labels.applyTag),
                ) {
                    Text(stringResource(labels.apply))
                }
            }
        }
    }
}
