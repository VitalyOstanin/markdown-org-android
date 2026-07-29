package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two lists the application shows come from two collectors that know
 * nothing about each other — licensee for the Gradle graph, cargo-about for
 * the crates the core links. These are the tests of putting them together.
 */
class LicenceCatalogTest {

    private val core = """
        [
          {
            "id": "MIT",
            "name": "MIT License",
            "text": "MIT, as one crate states it",
            "usedBy": [
              { "name": "nom", "version": "8.0.0", "url": "https://github.com/rust-bakery/nom" }
            ]
          },
          {
            "id": "Apache-2.0",
            "name": "Apache License 2.0",
            "text": "Apache, in full",
            "usedBy": [
              { "name": "openssl", "version": "3.6.3", "url": "https://github.com/openssl/openssl" }
            ]
          }
        ]
    """.trimIndent()

    private val gradle = """
        [
          {
            "groupId": "androidx.compose.ui",
            "artifactId": "ui",
            "version": "1.11.4",
            "spdxLicenses": [
              { "identifier": "Apache-2.0", "name": "Apache License 2.0", "url": "https://apache.org" }
            ],
            "scm": { "url": "https://cs.android.com" }
          }
        ]
    """.trimIndent()

    @Test
    fun bothCollectorsEndUpInOneList() {
        val catalog = licenceCatalog(core, gradle)

        assertEquals(listOf("Apache-2.0", "MIT"), catalog.map(LicenceGroup::id))
    }

    /**
     * A Gradle artifact carries no licence text of its own; the text of the
     * same licence gathered from a crate is the one shown. Without this the
     * Apache-2.0 half of the list — which is most of it — would have no text
     * at all, and Apache-2.0 §4(a) asks for exactly that text.
     */
    @Test
    fun anArtifactWithoutATextTakesTheOneAlreadyCollected() {
        val catalog = licenceCatalog(core, gradle)
        val apache = catalog.single { it.id == "Apache-2.0" }

        assertEquals("Apache, in full", apache.text)
        assertEquals(
            listOf("androidx.compose.ui:ui", "openssl"),
            apache.usedBy.map(Component::name),
        )
    }

    /** A component states what it is and where it came from, or it names nothing. */
    @Test
    fun aComponentCarriesItsVersionAndOrigin() {
        val catalog = licenceCatalog(core, gradle)
        val compose = catalog.flatMap(LicenceGroup::usedBy).single {
            it.name.startsWith("androidx")
        }

        assertEquals("1.11.4", compose.version)
        assertEquals("https://cs.android.com", compose.url)
    }

    /**
     * Two crates under the same licence with different copyright lines are two
     * texts, and both have to be shown: dropping one drops an attribution.
     */
    @Test
    fun thesameLicenceWithTwoTextsStaysTwoEntries() {
        val twice = """
            [
              {
                "id": "MIT",
                "name": "MIT License",
                "text": "Copyright one",
                "usedBy": [{ "name": "a", "version": "1", "url": "" }]
              },
              {
                "id": "MIT",
                "name": "MIT License",
                "text": "Copyright two",
                "usedBy": [{ "name": "b", "version": "2", "url": "" }]
              }
            ]
        """.trimIndent()

        val catalog = licenceCatalog(twice, "[]")

        assertEquals(2, catalog.size)
        assertEquals(listOf("Copyright one", "Copyright two"), catalog.map(LicenceGroup::text))
    }

    /**
     * An asset that is missing or was written by a version of the collector
     * this code does not know is not worth a crash: the screen says the list
     * is unavailable and the rest of the application carries on.
     */
    @Test
    fun anUnreadableListIsEmptyRatherThanAFailure() {
        assertTrue(licenceCatalog("not json at all", "[]").isEmpty())
        assertTrue(licenceCatalog("[]", "{\"unexpected\": true}").isEmpty())
    }

    /** A licence nobody could identify is still shown, under the name it came with. */
    @Test
    fun anArtifactWithNoSpdxIdentifierKeepsItsName() {
        val unknown = """
            [
              {
                "groupId": "com.example",
                "artifactId": "thing",
                "version": "1.0",
                "unknownLicenses": [
                  { "name": "The Thing Licence", "url": "https://example.com/licence" }
                ]
              }
            ]
        """.trimIndent()

        val catalog = licenceCatalog("[]", unknown)

        assertEquals(listOf("The Thing Licence"), catalog.map(LicenceGroup::id))
        assertEquals("https://example.com/licence", catalog.single().url)
    }
}
