package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.database.modifiers.removeGroup
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RemoveGroupDeletedObjectsTest {
    private val loose = Entry(uuid = Uuid.random())

    private val nested = Entry(uuid = Uuid.random())

    private val child = Group(uuid = Uuid.random(), name = "Child", entries = listOf(nested))

    private val folder = Group(uuid = Uuid.random(), name = "Folder", groups = listOf(child))

    private val database = KeePassDatabase.Ver4x
        .create(
            rootName = "Root",
            meta = Meta(),
            credentials = Credentials.from(EncryptedValue.fromString("1")),
        )
        .modifyParentGroup {
            copy(
                groups = groups + folder,
                entries = entries + loose,
            )
        }

    @Test
    fun removingGroupKeepsEarlierDeletions() {
        val removed = database
            .removeEntry(loose.uuid)
            .removeGroup(folder.uuid)

        val ids = removed.content.deletedObjects.map { it.id }
        assertEquals(
            setOf(loose.uuid, folder.uuid, child.uuid, nested.uuid),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun removingGroupKeepsTheLatestDeletionOfAnObject() {
        val longAgo = Clock.System.now() - 30.days
        val removed = database
            .modifyContent {
                copy(deletedObjects = listOf(DeletedObject(nested.uuid, longAgo)))
            }
            .removeGroup(folder.uuid)

        val deletion = removed.content.deletedObjects.single { it.id == nested.uuid }
        assertTrue(deletion.deletionTime > longAgo)
    }

    @Test
    fun removingGroupKeepsALaterDeletionOfAnObject() {
        val later = Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2)
        val removed = database
            .modifyContent {
                copy(deletedObjects = listOf(DeletedObject(nested.uuid, later)))
            }
            .removeGroup(folder.uuid)

        assertEquals(
            DeletedObject(nested.uuid, later),
            removed.content.deletedObjects.single { it.id == nested.uuid },
        )
    }
}
