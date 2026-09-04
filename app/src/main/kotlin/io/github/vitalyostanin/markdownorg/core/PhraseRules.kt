package io.github.vitalyostanin.markdownorg.core

import uniffi.markdown_org_ffi.PhraseDraft
import uniffi.markdown_org_ffi.canonicalRepeater
import uniffi.markdown_org_ffi.refinePhrase
import java.time.LocalDate

/**
 * The rules a phrase is read by, as whatever reads one reaches them.
 *
 * An interface rather than the two functions of the core called where they are
 * wanted: the screen a phrase is typed into and the model a spoken one arrives
 * at both read one, a composable does not call the core, and the JVM the unit
 * tests run on has no core to call at all — the native library is loaded on a
 * device.
 *
 * Which grammars are consulted is settled here rather than at each call site:
 * both of them, whichever language the screen is drawn in. A phone set to
 * English is still spoken to in Russian, and a rule that was not consulted is
 * a phrase left in the heading. The two grammars do not collide — the words
 * one of them reads the other does not know.
 */
interface PhraseRules {

    /** What the rules make of [said] about the fields [draft] holds. */
    fun refine(draft: PhraseDraft, said: String, today: LocalDate): PhraseDraft

    /** How [typed] would be written as a repeater, `null` if it spells none. */
    fun repeater(typed: String): String?
}

/** The rules as the core states them. */
internal object CorePhraseRules : PhraseRules {

    override fun refine(draft: PhraseDraft, said: String, today: LocalDate): PhraseDraft =
        refinePhrase(draft, said, LOCALES, "$today")

    override fun repeater(typed: String): String? = canonicalRepeater(typed)

    private const val LOCALES = "ru,en"
}
