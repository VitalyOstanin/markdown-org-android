package io.github.vitalyostanin.markdownorg.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Saying a sentence instead of typing it.
 *
 * The listening is not this app's to do: the phone already has an activity
 * that records and returns text, the one the keyboard's microphone key opens,
 * and it holds the permission for the microphone. Asking it costs an intent
 * and no permission of ours — a recogniser of our own would mean RECORD_AUDIO,
 * a runtime request for it, and a second answer to "which language is being
 * spoken" beside the one the phone is already set to.
 *
 * A phone with nothing to answer that intent is answered here rather than
 * crashing: the caller is told so and says the same thing by typing it.
 */
fun interface Dictation {

    /**
     * Ask for a sentence, showing [prompt] while listening.
     *
     * Returns `false` when there is nothing on the phone to listen — nothing
     * opens and [onSpoken] is never called. A `true` says the recogniser was
     * opened, not that anything was said: a cancelled or silent attempt leaves
     * [onSpoken] uncalled just the same.
     */
    fun listen(prompt: String, onSpoken: (String) -> Unit): Boolean
}

/**
 * The phone's own recogniser, wired to whatever asks for it.
 *
 * The result arrives at the launcher registered here rather than at the call
 * that started it, so what to do with the text is held aside and replaced on
 * every ask. One recogniser is open at a time, which is what makes a single
 * slot enough.
 *
 * The language is left unsaid: the recogniser then uses the one the phone is
 * set to, which is the language its owner speaks. Naming the screen's language
 * instead would refuse Russian on a phone kept in English — and the rules that
 * read the phrase consult both grammars precisely so that it need not be.
 */
@Composable
internal fun rememberSystemDictation(): Dictation {
    val context = LocalContext.current
    val packages = context.packageManager
    // Written to from the launch and read from the result, both on the main
    // thread; an array of one rather than state, because a change to it is
    // nothing the screen redraws for.
    val pending = remember { arrayOfNulls<(String) -> Unit>(1) }

    val recogniser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        // The first of the guesses is the likeliest one. Nothing is done with
        // an empty result: a cancelled attempt would otherwise clear a field
        // that was typed into before the microphone was pressed.
        if (result.resultCode == Activity.RESULT_OK && heard.isNotBlank()) {
            pending[0]?.invoke(heard)
        }
        pending[0] = null
    }

    return remember(recogniser, packages) {
        Dictation { prompt, onSpoken ->
            val ask = speechIntent(prompt)
            // Asked of the package manager first, with the query declared in
            // the manifest: a phone whose recogniser was uninstalled is a
            // message rather than the crash a bare launch would give.
            val answered = ask.resolveActivity(packages) != null
            if (answered) {
                pending[0] = onSpoken
                try {
                    recogniser.launch(ask)
                } catch (_: ActivityNotFoundException) {
                    // Between the question and the launch the recogniser could
                    // have gone; the screen hears the same "nothing to listen".
                    pending[0] = null
                    return@Dictation false
                }
            }
            answered
        }
    }
}

/** What the recogniser is asked for: free speech, with a line saying what for. */
private fun speechIntent(prompt: String): Intent {
    val model = RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, model)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
}
