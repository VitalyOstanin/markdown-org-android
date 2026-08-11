package io.github.vitalyostanin.markdownorg.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a tag selects once the collections have had their say.
 *
 * The same rules the editor extension follows, because the file they are read
 * from travels between the two: a tag that selected different notes on the
 * phone would make the shared file a liability rather than a convenience.
 */
class TagDictionaryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val work = TagDeclaration(
        collection = "work",
        tags = listOf(
            DeclaredTag(name = "ALL", pattern = ""),
            DeclaredTag(name = "TASKS", pattern = "task"),
            DeclaredTag(name = "OTHER", pattern = "!"),
        ),
    )

    private val home = TagDeclaration(
        collection = "home",
        tags = listOf(DeclaredTag(name = "BILLS", pattern = "bill")),
    )

    private fun List<MergedTag>.named(name: String): MergedTag =
        firstOrNull { it.name == name } ?: error("no tag named $name in ${map(MergedTag::name)}")

    @Test
    fun `a tag declared by one collection reaches the notes of the others`() {
        val dictionary = mergeTagDictionaries(listOf(work, home))
        val tasks = dictionary.named("TASKS")

        assertTrue(fileMatchesTag("task-repair.md", tasks, dictionary))
        assertFalse(fileMatchesTag("bill-water.md", tasks, dictionary))
    }

    @Test
    fun `two collections disagreeing about a name keep both patterns`() {
        val dictionary = mergeTagDictionaries(
            listOf(
                TagDeclaration("work", listOf(DeclaredTag(name = "DRAFT", pattern = "wip"))),
                TagDeclaration("home", listOf(DeclaredTag(name = "DRAFT", pattern = "draft"))),
            ),
        )
        val draft = dictionary.named("DRAFT")

        assertEquals(listOf("wip", "draft"), draft.include)
        assertTrue(fileMatchesTag("wip-letter.md", draft, dictionary))
        assertTrue(fileMatchesTag("draft-letter.md", draft, dictionary))
        assertFalse(fileMatchesTag("letter.md", draft, dictionary))
    }

    @Test
    fun `the order of the collections does not change what a tag selects`() {
        val one = mergeTagDictionaries(listOf(work, home))
        val other = mergeTagDictionaries(listOf(home, work))
        val files = listOf("task-repair.md", "bill-water.md", "letter.md")

        for (name in listOf("TASKS", "BILLS", "OTHER")) {
            assertEquals(
                "tag $name selects differently after reordering",
                files.filter { fileMatchesTag(it, one.named(name), one) },
                files.filter { fileMatchesTag(it, other.named(name), other) },
            )
        }
    }

    @Test
    fun `an exclusion keeps a note out of a tag that includes it`() {
        val dictionary = mergeTagDictionaries(
            listOf(
                TagDeclaration(
                    "work",
                    listOf(
                        DeclaredTag(
                            name = "WORK",
                            include = listOf("work"),
                            exclude = listOf("archive"),
                        ),
                    ),
                ),
            ),
        )
        val tag = dictionary.named("WORK")

        assertTrue(fileMatchesTag("work-plan.md", tag, dictionary))
        assertFalse(fileMatchesTag("work-archive.md", tag, dictionary))
    }

    @Test
    fun `an exclusion declared by one collection holds against the others`() {
        val dictionary = mergeTagDictionaries(
            listOf(
                TagDeclaration(
                    "work",
                    listOf(DeclaredTag(name = "WORK", include = listOf("work"))),
                ),
                TagDeclaration(
                    "home",
                    listOf(DeclaredTag(name = "WORK", exclude = listOf("work-archive"))),
                ),
            ),
        )
        val tag = dictionary.named("WORK")

        assertTrue(fileMatchesTag("work-plan.md", tag, dictionary))
        assertFalse(fileMatchesTag("work-archive.md", tag, dictionary))
    }

    @Test
    fun `a note refused by every tag falls to the tag taking the rest`() {
        val dictionary = mergeTagDictionaries(
            listOf(
                TagDeclaration(
                    "work",
                    listOf(
                        DeclaredTag(
                            name = "WORK",
                            include = listOf("work"),
                            exclude = listOf("archive"),
                        ),
                        DeclaredTag(name = "OTHER", pattern = "!"),
                    ),
                ),
            ),
        )
        val rest = dictionary.named("OTHER")

        // Read as "matches no including pattern", this note would be in nothing
        // at all and invisible under every filter.
        assertTrue(fileMatchesTag("work-archive.md", rest, dictionary))
        assertFalse(fileMatchesTag("work-plan.md", rest, dictionary))
    }

    @Test
    fun `a tag taking every note does not empty the rest`() {
        val dictionary = mergeTagDictionaries(listOf(work, home))

        assertTrue(fileMatchesTag("letter.md", dictionary.named("OTHER"), dictionary))
    }

    @Test
    fun `a pattern is looked for anywhere in the file name`() {
        val dictionary = mergeTagDictionaries(
            listOf(TagDeclaration("work", listOf(DeclaredTag(name = "WORK", pattern = "work")))),
        )
        val tag = dictionary.named("WORK")

        assertTrue(fileMatchesTag("work-plan.md", tag, dictionary))
        assertTrue(fileMatchesTag("2026-work.md", tag, dictionary))
        // No word boundaries: this is what the exclusions are for.
        assertTrue(fileMatchesTag("homework-2026.md", tag, dictionary))
        // The case is part of the pattern.
        assertFalse(fileMatchesTag("Work-plan.md", tag, dictionary))
    }

    @Test
    fun `a declaration a file could hold but a tag cannot use is dropped`() {
        val dictionary = mergeTagDictionaries(
            listOf(
                TagDeclaration(
                    "work",
                    listOf(
                        DeclaredTag(name = "", pattern = "nameless"),
                        DeclaredTag(name = "NO_PATTERNS"),
                        DeclaredTag(name = "GOOD", pattern = "good"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("GOOD"), dictionary.map(MergedTag::name))
    }

    @Test
    fun `the file a collection carries is read into a declaration`() = runTest {
        val directory = folder.newFolder("notes")
        File(directory, TAGS_FILE).apply {
            parentFile?.mkdirs()
            writeText("""[{"name":"WORK","include":["work"],"exclude":["old"]}]""")
        }

        val declaration = readDeclaredTags("work", directory)

        assertEquals(
            listOf(DeclaredTag(name = "WORK", include = listOf("work"), exclude = listOf("old"))),
            declaration?.tags,
        )
    }

    @Test
    fun `a collection without the file declares nothing, which is not a failure`() = runTest {
        assertNull(readDeclaredTags("work", folder.newFolder("bare")))
    }

    @Test
    fun `a file that will not parse is skipped rather than thrown`() = runTest {
        val directory = folder.newFolder("broken")
        File(directory, TAGS_FILE).apply {
            parentFile?.mkdirs()
            writeText("{ this is not json")
        }

        assertNull(readDeclaredTags("broken", directory))
    }

    @Test
    fun `a key this version has not heard of does not drop the tag`() = runTest {
        val directory = folder.newFolder("newer")
        File(directory, TAGS_FILE).apply {
            parentFile?.mkdirs()
            writeText("""[{"name":"WORK","pattern":"work","colour":"blue"}]""")
        }

        assertEquals(
            listOf("WORK"),
            readDeclaredTags("newer", directory)?.tags?.map(DeclaredTag::name),
        )
    }
}
