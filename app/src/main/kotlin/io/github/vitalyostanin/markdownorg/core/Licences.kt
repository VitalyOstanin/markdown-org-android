package io.github.vitalyostanin.markdownorg.core

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** One thing the APK carries that was not written here. */
data class Component(val name: String, val version: String, val url: String)

/**
 * A licence as it applies to a set of components.
 *
 * Grouped by the text rather than by the identifier: two crates under MIT
 * carry different copyright lines, and those lines are the attribution the
 * licence asks to keep.
 */
data class LicenceGroup(
    val id: String,
    val name: String,
    val text: String,
    val url: String,
    val usedBy: List<Component>,
)

/**
 * What the APK carries, from the two lists the build collects.
 *
 * [core] is written by `tools/licenses.sh` out of the crate graph and the
 * vendored sources; [gradle] is written by the licensee plugin out of the
 * Gradle graph while the APK is being assembled. Neither knows about the
 * other, and only the first carries licence texts — which is why an artifact
 * takes the text of the same licence gathered from a crate.
 *
 * A list that cannot be read yields nothing rather than throwing: a screen
 * saying the notices are unavailable is recoverable, an application that dies
 * on opening it is not.
 */
fun licenceCatalog(core: String, gradle: String): List<LicenceGroup> {
    val collected = runCatching {
        JSON.decodeFromString<List<CollectedLicence>>(core) to
            JSON.decodeFromString<List<GradleArtifact>>(gradle)
    }.getOrNull() ?: return emptyList()

    val (crates, artifacts) = collected
    val groups = crates.map { licence ->
        LicenceGroup(
            id = licence.id,
            name = licence.name,
            text = licence.text,
            url = "",
            usedBy = licence.usedBy.map { Component(it.name, it.version, it.url) },
        )
    }

    return artifacts
        .fold(groups) { known, artifact -> known.with(artifact) }
        .map { group -> group.copy(usedBy = group.usedBy.sortedBy(Component::name)) }
        .sortedWith(compareBy(LicenceGroup::id, LicenceGroup::text))
}

/**
 * The catalogue as the packaged application carries it.
 *
 * Reads both assets; one that is absent counts as an empty list rather than
 * as a failure, since a debug build assembled without running the collectors
 * still has to start.
 */
fun licenceCatalog(context: Context): List<LicenceGroup> = licenceCatalog(
    context.asset(CORE_LIST),
    context.asset(GRADLE_LIST),
)

private fun Context.asset(name: String): String = try {
    assets.open(name).use { it.readBytes().decodeToString() }
} catch (_: IOException) {
    "[]"
}

/** Written by `tools/licenses.sh`, committed. */
private const val CORE_LIST = "licenses-core.json"

/** Written by the licensee plugin while the APK is assembled. */
private const val GRADLE_LIST = "licenses.json"

/**
 * The same list with [artifact] placed under its licence.
 *
 * It joins the first group of that licence that has a text — the text is what
 * a reader needs, and an artifact carries none of its own. Where no such group
 * exists the artifact opens one, holding the name and the link it came with.
 */
private fun List<LicenceGroup>.with(artifact: GradleArtifact): List<LicenceGroup> {
    val stated = artifact.licence() ?: return this
    val component = Component(artifact.name(), artifact.version, artifact.scm?.url ?: stated.url)
    val under = indexOfFirst { it.id == stated.id && it.text.isNotEmpty() }

    if (under < 0) {
        return this + LicenceGroup(
            id = stated.id,
            name = stated.name,
            text = "",
            url = stated.url,
            usedBy = listOf(component),
        )
    }

    return mapIndexed { index, group ->
        if (index == under) group.copy(usedBy = group.usedBy + component) else group
    }
}

/** What an artifact is under: the first identified licence, else the first named one. */
private fun GradleArtifact.licence(): StatedLicence? = spdxLicenses
    .firstOrNull()
    ?.let { StatedLicence(it.identifier, it.name, it.url) }
    ?: unknownLicenses.firstOrNull()?.let { StatedLicence(it.name, it.name, it.url) }

private fun GradleArtifact.name(): String = "$groupId:$artifactId"

private data class StatedLicence(val id: String, val name: String, val url: String)

@Serializable
private data class CollectedLicence(
    val id: String,
    val name: String,
    val text: String,
    val usedBy: List<CollectedComponent>,
)

@Serializable
private data class CollectedComponent(val name: String, val version: String, val url: String = "")

@Serializable
private data class GradleArtifact(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val spdxLicenses: List<SpdxLicence> = emptyList(),
    val unknownLicenses: List<NamedLicence> = emptyList(),
    val scm: Scm? = null,
)

@Serializable
private data class SpdxLicence(
    @SerialName("identifier") val identifier: String,
    val name: String,
    val url: String = "",
)

@Serializable
private data class NamedLicence(val name: String, val url: String = "")

@Serializable
private data class Scm(val url: String)

/**
 * Lenient about fields it does not know: both collectors are third-party
 * tools, and an added field in a report is not a reason for the screen to go
 * blank.
 */
private val JSON = Json { ignoreUnknownKeys = true }
