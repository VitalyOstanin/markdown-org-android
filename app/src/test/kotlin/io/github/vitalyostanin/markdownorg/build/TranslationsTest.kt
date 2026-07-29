package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the two languages the application ships stay in step.
 *
 * Read off the resource files rather than off a running screen, for the same
 * reason as [SpacingScaleTest]: a string that exists in one language and not
 * in the other draws fine — in the other language — and only a comparison of
 * the two files says so.
 */
class TranslationsTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val res = root.resolve("app/src/main/res")

    /** The product name reads the same everywhere and is marked untranslatable. */
    private val exempt = setOf("app_name")

    @Test
    fun everyStringIsTranslatedIntoEveryLanguage() {
        val base = names(res.resolve("values/strings.xml")) - exempt

        for (language in languages()) {
            val translated = names(res.resolve("values-$language/strings.xml"))

            assertEquals("missing in $language", emptySet<String>(), base - translated)
            assertEquals("not in the base language", emptySet<String>(), translated - base)
        }
    }

    /**
     * Without a locale config, the per-app language of Android 13 and later
     * does not list the application at all: its language follows the system
     * and cannot be set apart from it.
     */
    @Test
    fun theLocaleConfigListsEveryLanguageThatShips() {
        val declared = Regex("""android:name="([^"]+)"""")
            .findAll(res.resolve("xml/locales_config.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(languages() + BASE_LANGUAGE, declared)
    }

    @Test
    fun theManifestPointsAtTheLocaleConfig() {
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(
            "no android:localeConfig in the manifest",
            manifest.contains("android:localeConfig=\"@xml/locales_config\""),
        )
    }

    /** Every `values-<language>` directory that holds strings. */
    private fun languages(): Set<String> = res.listFiles().orEmpty()
        .mapNotNull { dir -> dir.name.removePrefix("values-").takeIf { it != dir.name } }
        .filter { language -> res.resolve("values-$language/strings.xml").isFile }
        .toSet()

    private fun names(file: File): Set<String> = Regex("""<(?:string|plurals) name="([^"]+)"""")
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

    private companion object {
        /** What `values/` itself is written in. */
        const val BASE_LANGUAGE = "en"
    }
}
