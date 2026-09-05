package io.github.vitalyostanin.markdownorg.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What keeps a reminder at the minute it names.
 *
 * The minute rests on a line of the manifest and on nothing observable at
 * runtime: an alarm placed without the permission is delivered within an hour
 * of the time asked for, and neither the log nor the settings screen says so
 * while it happens. Before `USE_EXACT_ALARM` was declared the exactness came
 * from the phone's exemption from battery optimisation, which the platform
 * withdraws on its own terms — the alarms of one plan were exact, then an hour
 * wide, then exact again, with nothing touched in between. See ADR-0045.
 *
 * Read off the manifest, because that is where the decision lives: no test on
 * the JVM can ask the platform which alarms it would honour.
 */
class ExactAlarmsTest {

    private val root = File(System.getProperty("repo.root") ?: "..")

    private val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()

    @Test
    fun theMinuteIsAskedForWherePermissionIsGrantedAtInstall() {
        assertTrue(
            "USE_EXACT_ALARM is not declared — from Android 13 the reminders are then as exact " +
                "as the battery-optimisation allowlist happens to be, which is a grant that is " +
                "taken back without the application being told",
            manifest.contains("android.permission.USE_EXACT_ALARM"),
        )
    }

    @Test
    fun theOlderPermissionIsAskedForOnlyWhereTheNewerOneIsMissing() {
        val declaration = DECLARATION.find(manifest)

        assertTrue(
            "SCHEDULE_EXACT_ALARM is not declared — Android 12 has no USE_EXACT_ALARM, and " +
                "without this one its reminders lose the minute",
            declaration != null,
        )
        assertTrue(
            "SCHEDULE_EXACT_ALARM is declared without maxSdkVersion=\"32\" — it is superseded " +
                "from Android 13, and asking for both leaves the store listing carrying an " +
                "access the reader is never asked about:\n  ${declaration?.value?.trim()}",
            declaration?.value?.contains("android:maxSdkVersion=\"32\"") == true,
        )
    }

    private companion object {
        val DECLARATION = Regex(
            """<uses-permission[^>]*android\.permission\.SCHEDULE_EXACT_ALARM[^>]*/>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
