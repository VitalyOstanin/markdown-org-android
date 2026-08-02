package io.github.vitalyostanin.markdownorg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules a set of notes collections has to keep.
 *
 * Notes live in more than one place — a work repository and a private one —
 * and the agenda over them is one agenda. What the rules are for is the two
 * ways that goes wrong before anything is scanned: the same directory entered
 * twice, and one collection sitting inside another. Both put every task in it
 * on the screen twice, because the walk that merges them drops only roots that
 * are equal, and an edit then names a task there is more than one of.
 */
class NotesCollectionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Stands in for the directory inside the application's own storage. */
    private val own: File by lazy { folder.newFolder("files", "notes") }

    private fun collection(id: String, path: File, name: String = "Notes") =
        NotesCollection(id = id, name = name, path = path.absolutePath)

    @Test
    fun aCollectionOverADirectoryOfItsOwnIsAccepted() {
        val work = folder.newFolder("work")
        val home = folder.newFolder("home")

        assertNull(collectionProblem("Home", home.absolutePath, listOf(collection("1", work))))
    }

    @Test
    fun aCollectionWithoutANameIsRefused() {
        val home = folder.newFolder("home")

        assertEquals(
            CollectionProblem.NAME_EMPTY,
            collectionProblem("   ", home.absolutePath, emptyList()),
        )
    }

    /**
     * The same directory twice is not two collections: the walk merges roots
     * that are equal, so the second one adds nothing but a name the screen
     * would then offer to filter by.
     */
    @Test
    fun aDirectoryAnotherCollectionAlreadyHoldsIsRefused() {
        val work = folder.newFolder("work")

        assertEquals(
            CollectionProblem.PATH_TAKEN,
            collectionProblem("Second", work.absolutePath, listOf(collection("1", work))),
        )
    }

    /**
     * A trailing separator is the same directory typed differently, and the
     * walk would find that out only after both had been scanned.
     */
    @Test
    fun theSameDirectoryWrittenDifferentlyIsStillTaken() {
        val work = folder.newFolder("work")

        assertEquals(
            CollectionProblem.PATH_TAKEN,
            collectionProblem("Second", work.absolutePath + "/", listOf(collection("1", work))),
        )
    }

    /**
     * Nesting is the case the walk cannot see: the roots are not equal, so
     * both are scanned, and every note under the inner one is read twice.
     */
    @Test
    fun aDirectoryInsideAnotherCollectionIsRefused() {
        val work = folder.newFolder("work")
        val inner = folder.newFolder("work", "inner")

        assertEquals(
            CollectionProblem.PATH_NESTED,
            collectionProblem("Inner", inner.absolutePath, listOf(collection("1", work))),
        )
    }

    @Test
    fun aDirectoryHoldingAnotherCollectionIsRefusedAsWell() {
        val work = folder.newFolder("work")
        val inner = folder.newFolder("work", "inner")

        assertEquals(
            CollectionProblem.PATH_NESTED,
            collectionProblem("Outer", work.absolutePath, listOf(collection("1", inner))),
        )
    }

    /**
     * A sibling whose name merely starts the same way is not nested, and a
     * prefix comparison without the separator would have refused it.
     */
    @Test
    fun aSiblingWhoseNameStartsTheSameWayIsNotNested() {
        val work = folder.newFolder("work")
        val sibling = folder.newFolder("work-old")

        assertNull(collectionProblem("Old", sibling.absolutePath, listOf(collection("1", work))))
    }

    /**
     * Identifiers are handed out once and never again: the settings of a
     * collection are stored under its identifier, and one given back out
     * would hand a new collection the remote and token of a deleted one.
     */
    @Test
    fun anIdentifierIsNotHandedOutASecondTime() {
        val first = nextCollectionId(emptyList())
        val second = nextCollectionId(listOf(NotesCollection(first, "Notes", own.absolutePath)))

        assertEquals("1", first)
        assertEquals("2", second)
    }

    @Test
    fun anIdentifierClearsEveryOneAlreadyGivenOutRatherThanTheLast() {
        val kept = NotesCollection("7", "Notes", own.absolutePath)
        val earlier = NotesCollection("3", "Work", folder.newFolder("work").absolutePath)

        assertEquals("8", nextCollectionId(listOf(kept, earlier)))
    }

    /**
     * The state a device upgrades from: one directory, remembered where it
     * was, and no collections at all. It becomes the first collection —
     * nothing moves, nothing is cloned again, and the settings that named
     * that directory keep naming it.
     */
    @Test
    fun theSingleDirectoryOfAnEarlierVersionBecomesTheFirstCollection() {
        val chosen = folder.newFolder("shared", "notes")

        val migrated = migratedCollections(
            stored = emptyList(),
            legacyPath = chosen.absolutePath,
            own = own,
            defaultName = "Notes",
        )

        assertEquals(
            listOf(NotesCollection(id = "1", name = "Notes", path = chosen.absolutePath)),
            migrated,
        )
    }

    /** A fresh install has no chosen directory: the first collection is the own one. */
    @Test
    fun aDeviceWithNoChosenDirectoryStartsOnTheOwnStorage() {
        val migrated = migratedCollections(
            stored = emptyList(),
            legacyPath = null,
            own = own,
            defaultName = "Notes",
        )

        assertEquals(listOf(NotesCollection("1", "Notes", own.absolutePath)), migrated)
    }

    /** Once there are collections the earlier setting is history, not an input. */
    @Test
    fun aStoredSetOfCollectionsIsLeftAloneWhateverTheEarlierSettingSays() {
        val stored = listOf(NotesCollection("4", "Work", folder.newFolder("work").absolutePath))

        assertEquals(
            stored,
            migratedCollections(
                stored,
                legacyPath = own.absolutePath,
                own = own,
                defaultName = "N",
            ),
        )
    }
}
