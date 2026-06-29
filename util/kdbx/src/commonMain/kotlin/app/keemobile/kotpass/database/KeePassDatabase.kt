@file:Suppress("unused")

package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.cryptography.KeyTransform
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.DatabaseInnerHeader
import app.keemobile.kotpass.extensions.clear
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.DatabaseElement
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.cryptography.SecureRandom
import kotlin.uuid.Uuid
import kotlin.time.measureTime

/**
 * Main class which describes Keepass database.
 */
sealed class KeePassDatabase {
    abstract val credentials: Credentials
    abstract val header: DatabaseHeader
    abstract val content: DatabaseContent

    data class Ver3x(
        override val credentials: Credentials,
        override val header: DatabaseHeader.Ver3x,
        override val content: DatabaseContent
    ) : KeePassDatabase() {
        companion object {
            /**
             * Creates blank database with default settings.
             *
             * @param rootName Required name of the top group.
             * @param meta Database metadata.
             * @param credentials Database credentials.
             * @param random optional custom random generator.
             */
            fun create(
                rootName: String,
                meta: Meta,
                credentials: Credentials,
                random: SecureRandom = SecureRandom()
            ) = Ver3x(
                credentials = credentials,
                header = DatabaseHeader.Ver3x.create(random),
                content = DatabaseContent(
                    meta = meta,
                    group = Group(
                        uuid = Uuid.random(),
                        name = rootName,
                        enableAutoType = GroupOverride.Enabled,
                        enableSearching = GroupOverride.Enabled
                    ),
                    deletedObjects = listOf()
                )
            )
        }
    }

    data class Ver4x(
        override val credentials: Credentials,
        override val header: DatabaseHeader.Ver4x,
        override val content: DatabaseContent,
        val innerHeader: DatabaseInnerHeader
    ) : KeePassDatabase() {
        companion object {
            /**
             * Creates blank database with default settings.
             *
             * @param rootName Required name of the top group.
             * @param meta Database metadata.
             * @param credentials Database credentials.
             * @param random optional custom random generator.
             */
            fun create(
                rootName: String,
                meta: Meta,
                credentials: Credentials,
                random: SecureRandom = SecureRandom()
            ) = Ver4x(
                credentials = credentials,
                header = DatabaseHeader.Ver4x.create(random),
                content = DatabaseContent(
                    meta = meta,
                    group = Group(
                        uuid = Uuid.random(),
                        name = rootName,
                        enableAutoType = GroupOverride.Enabled,
                        enableSearching = GroupOverride.Enabled
                    ),
                    deletedObjects = listOf()
                ),
                innerHeader = DatabaseInnerHeader.create(random)
            )
        }
    }

    companion object {
        const val MinSupportedVersion = 3
        const val MaxSupportedVersion = 4
    }
}

/**
 * Traverses [KeePassDatabase] invoking [block] on each [DatabaseElement].
 */
fun KeePassDatabase.traverse(
    block: (DatabaseElement) -> Unit
) = content.group.traverse(block)

/**
 * Retrieves a single [Group] which matches a given [predicate].
 *
 * @return Found [Group] paired with it’s parent [Group] or null.
 */
fun KeePassDatabase.getGroup(
    predicate: (Group) -> Boolean
): Pair<Group?, Group>? {
    return if (predicate(content.group)) {
        null to content.group
    } else {
        content.group.findChildGroup(null, predicate)
    }
}

/**
 * Retrieves a single [Group] which matches a given [predicate].
 */
fun KeePassDatabase.getGroupBy(
    predicate: Group.() -> Boolean
): Group? {
    return if (predicate(content.group)) {
        content.group
    } else {
        content.group
            .findChildGroup(null, predicate)
            ?.let { (_, group) -> group }
    }
}

/**
 * Retrieves a single [Entry] which matches a given [predicate].
 *
 * @return Found [Entry] paired with it’s parent [Group] or null.
 */
fun KeePassDatabase.getEntry(
    predicate: (Entry) -> Boolean
): Pair<Group, Entry>? {
    return content
        .group
        .findChildEntry(false, null, predicate)
}

/**
 * Searches for single [Entry] which matches a given [predicate] while
 * respecting [GroupOverride] and ignoring items in Recycle Bin.
 *
 * @return Found [Entry] paired with it’s parent [Group] or null.
 */
fun KeePassDatabase.findEntry(
    predicate: (Entry) -> Boolean
): Pair<Group, Entry>? {
    return content
        .group
        .findChildEntry(true, content.meta.recycleBinUuid, predicate)
}

/**
 * Retrieves a single [Entry] which matches a given [predicate].
 */
fun KeePassDatabase.getEntryBy(
    predicate: Entry.() -> Boolean
): Entry? {
    return content
        .group
        .findChildEntry(false, null, predicate)
        ?.let { (_, entry) -> entry }
}

/**
 * Searches for single [Entry] which matches a given [predicate] while
 * respecting [GroupOverride] and ignoring items in Recycle Bin.
 */
fun KeePassDatabase.findEntryBy(
    predicate: Entry.() -> Boolean
): Entry? {
    return content
        .group
        .findChildEntry(true, content.meta.recycleBinUuid, predicate)
        ?.let { (_, entry) -> entry }
}

/**
 * Retrieves entries which match a given [predicate].
 *
 * @return [List] of found [Entry] items paired with corresponding parent [Group].
 */
fun KeePassDatabase.getEntries(
    predicate: (Entry) -> Boolean
): List<Pair<Group, List<Entry>>> {
    return content
        .group
        .findChildEntries(false, null, predicate)
}

/**
 * Searches for entries which match a given [predicate] while
 * respecting [GroupOverride] and ignoring items in Recycle Bin.
 *
 * @return [List] of found [Entry] items paired with corresponding parent [Group].
 */
fun KeePassDatabase.findEntries(
    predicate: (Entry) -> Boolean
): List<Pair<Group, List<Entry>>> {
    return content
        .group
        .findChildEntries(true, content.meta.recycleBinUuid, predicate)
}

/**
 * Measures KDF transform rounds performance based on
 * [header][KeePassDatabase.header] parameters.
 */
fun KeePassDatabase.measureKeyTransformMillis(
    kdfProvider: KdfProvider = BaseKdfProvider
): Long = measureTime {
    KeyTransform
        .transformedKey(kdfProvider, header, credentials)
        .clear()
}.inWholeMilliseconds
