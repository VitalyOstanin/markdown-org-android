package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The certificate authorities are read off the main thread.
 *
 * 186 kB out of the assets, and the parsing of 119 certificates on the core
 * side, on the way to the first sync — which is asked for from the frame loop,
 * on the very frame that puts "syncing" on screen. What keeps it off there is
 * the declaration: a suspend function that hands the read to the IO pool.
 *
 * Read off the source rather than measured, because the thread a call ends up
 * on is not something a JVM test can observe — [io.github.vitalyostanin.markdownorg.core.NotesSync]
 * itself only runs on a device. The property being kept is that the signature
 * makes the wrong call impossible to write.
 */
class StorageReadsTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val source =
        root.resolve("app/src/main/kotlin/io/github/vitalyostanin/markdownorg/core/NotesSync.kt")

    @Test
    fun fillingTheCertificateStoreIsSuspend() {
        val line = source.readLines().firstOrNull { it.contains("fun fill(") }

        assertNotNull("NotesSync.kt no longer declares `fun fill(`", line)
        assertTrue(
            "NotesSync.kt: `fill` reads the assets, so it has to be suspend — otherwise " +
                "nothing stops a caller reaching it from the main thread:\n  ${line?.trim()}",
            line.orEmpty().contains("suspend fun"),
        )
    }

    @Test
    fun theReadOfTheCertificatesGoesToTheIoPool() {
        assertTrue(
            "NotesSync.kt: filling the store suspends but never leaves the caller's thread " +
                "— a suspend function without withContext(Dispatchers.IO) reads the assets " +
                "wherever it was called from",
            source.readText().contains("withContext(Dispatchers.IO)"),
        )
    }
}
