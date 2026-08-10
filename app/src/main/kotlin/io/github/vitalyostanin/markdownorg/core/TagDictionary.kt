package io.github.vitalyostanin.markdownorg.core

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A tag as it is written down, in either of the two spellings.
 *
 * `pattern` is the single string the editor extension has always taken, where a
 * leading `!` marks the tag as "everything no other tag took". `include` and
 * `exclude` are the lists that let one entry say both what a tag takes and what
 * it refuses, which a single string cannot.
 */
@Serializable
data class DeclaredTag(
    val name: String = "",
    val pattern: String? = null,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
)

/** What a pattern does to the files it matches. */
enum class TagRole { INCLUDE, EXCLUDE, REST }

/** Where one pattern of a merged tag came from, and what it was asked to do. */
data class TagOrigin(val pattern: String, val role: TagRole, val collection: String)

/**
 * A tag as the agenda uses it: one name, everything anybody declared for it.
 *
 * The declarations are merged rather than resolved against each other. Two
 * collections that disagree about what `DRAFT` means both keep their say, and a
 * note matching either of them is in `DRAFT`.
 */
data class MergedTag(
    val name: String,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val rest: Boolean = false,
    val origins: List<TagOrigin> = emptyList(),
)

/** One collection's answer about the tags, as it was read. */
data class TagDeclaration(val collection: String, val tags: List<DeclaredTag>)

/**
 * Where a notes directory keeps the tags it declares.
 *
 * The same path the editor extension reads, because the point of the file is
 * that both clients see the same tags: it travels in the git checkout the notes
 * are synced through.
 */
const val TAGS_FILE: String = ".markdown-org/tags.json"

private const val TAG = "TagDictionary"

/**
 * Lenient about fields it does not know: the file is written by hand and by
 * another client, and a key this version has not heard of is not a reason to
 * drop the tags around it.
 */
private val JSON = Json { ignoreUnknownKeys = true }

private fun DeclaredTag.roles(): List<Pair<String, TagRole>> = buildList {
    pattern?.let { spelling ->
        // The text after '!' has never meant anything: '!', '!work' and '!xyz'
        // all say "whatever no tag took". Kept that way -- configurations in
        // the wild spell it every one of those ways.
        add(spelling to if (spelling.startsWith("!")) TagRole.REST else TagRole.INCLUDE)
    }
    include.forEach { add(it to TagRole.INCLUDE) }
    exclude.forEach { add(it to TagRole.EXCLUDE) }
}

/**
 * Merge what every collection declared into one dictionary.
 *
 * The dictionary is global on purpose: a tag means the same thing wherever the
 * note came from, so a collection that never heard of `WORK` is filtered by it
 * like any other. The alternative -- a tag whose meaning depends on where the
 * note lives -- makes the same name select different notes on two screens
 * showing the same agenda, and makes the order of the collections part of the
 * answer.
 *
 * Order is first-seen. A repeated pattern for the same name and role is stored
 * once, but every collection that asked for it is kept in [MergedTag.origins],
 * which is what the tag screen shows.
 */
fun mergeTagDictionaries(declarations: List<TagDeclaration>): List<MergedTag> {
    val include = linkedMapOf<String, MutableList<String>>()
    val exclude = mutableMapOf<String, MutableList<String>>()
    val rest = mutableSetOf<String>()
    val origins = mutableMapOf<String, MutableList<TagOrigin>>()

    for (declaration in declarations) {
        for (tag in declaration.tags) {
            val roles = tag.roles()
            if (tag.name.isEmpty() || roles.isEmpty()) continue
            include.getOrPut(tag.name) { mutableListOf() }
            exclude.getOrPut(tag.name) { mutableListOf() }
            for ((pattern, role) in roles) {
                when (role) {
                    TagRole.REST -> rest += tag.name
                    TagRole.INCLUDE -> include.getValue(tag.name).addUnlessThere(pattern)
                    TagRole.EXCLUDE -> exclude.getValue(tag.name).addUnlessThere(pattern)
                }
                origins.getOrPut(tag.name) { mutableListOf() } +=
                    TagOrigin(pattern, role, declaration.collection)
            }
        }
    }

    return include.keys.map { name ->
        MergedTag(
            name = name,
            include = include.getValue(name).toList(),
            exclude = exclude.getValue(name).orEmpty().toList(),
            rest = name in rest,
            origins = origins[name].orEmpty().toList(),
        )
    }
}

private fun MutableList<String>.addUnlessThere(value: String) {
    if (value !in this) add(value)
}

/**
 * Whether a tag claims a file by a pattern that tells files apart.
 *
 * Two things are not a claim. An exclusion is not: a file the tag refuses is
 * not the tag's, which is what lets the rest pick it up instead of it falling
 * out of every tag at once. And an empty pattern is not: a tag taking every
 * file says "no filtering" rather than "these are mine", so a tag like `ALL`
 * must not empty the rest of everything.
 */
private fun MergedTag.claims(basename: String): Boolean = exclude.none { basename.contains(it) } &&
    include.any { it.isNotEmpty() && basename.contains(it) }

/**
 * Whether a file belongs to a tag.
 *
 * Three rules, in this order:
 *
 *   1. Excluding wins. A pattern that keeps a file out keeps it out however
 *      many collections include it -- otherwise an exclusion could be undone by
 *      a collection that never heard of it, and "everything but the archive"
 *      would be unsayable the moment a second collection joined the agenda.
 *   2. Including patterns are alternatives. An empty one selects every file,
 *      which is how `ALL` keeps working when one collection declares it and
 *      another does not -- and, by rule 1, an exclusion still applies to it.
 *   3. A resting tag takes every file no tag ended up claiming. Measured after
 *      the exclusions: a note thrown out of `WORK` belongs to no tag, and the
 *      rest is where "no tag" lives. Read the other way -- "matches no
 *      including pattern anywhere" -- an excluded note would be in nothing at
 *      all and invisible under every filter.
 */
fun fileMatchesTag(basename: String, tag: MergedTag, dictionary: List<MergedTag>): Boolean {
    if (tag.exclude.any { basename.contains(it) }) return false
    if (tag.include.any { it.isEmpty() || basename.contains(it) }) return true
    return tag.rest && dictionary.none { it.claims(basename) }
}

/**
 * Read one directory's declaration, or nothing when it has none.
 *
 * A missing file is the normal state and is not reported: most directories
 * never declare tags. Everything else -- unreadable file, JSON that will not
 * parse -- goes to the log and the directory is skipped, so the tags of the
 * other collections still describe the agenda.
 */
fun readDeclaredTags(collection: String, directory: File): TagDeclaration? {
    val file = File(directory, TAGS_FILE)
    if (!file.isFile) return null
    return runCatching { JSON.decodeFromString<List<DeclaredTag>>(file.readText()) }
        .fold(
            onSuccess = { TagDeclaration(collection, it) },
            onFailure = { error ->
                Log.w(TAG, "the tags of $collection were not read", error)
                null
            },
        )
}
