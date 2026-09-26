package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.database.getGroup
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class KeePassDbMutatorTest {
    private val originalTime = Instant.parse("2024-01-01T00:00:00Z")
    private val deadline = Instant.parse("2040-05-06T07:08:09Z")

    @Test
    fun invalidGroupMovesPreserveDatabase() {
        databases().forEach { database ->
            val deepChild = Group(Uuid.random(), "Deep child", entries = listOf(Entry(Uuid.random())))
            val child = Group(Uuid.random(), "Child", groups = listOf(deepChild))
            val source = Group(Uuid.random(), "Source", groups = listOf(child), entries = listOf(Entry(Uuid.random())))
            val initial = database.withGroups(listOf(source))

            listOf(source.uuid, child.uuid, deepChild.uuid, Uuid.random()).forEach { target ->
                val mutator = KeePassDbMutator(initial)
                assertFalse(mutator.moveGroup(source.uuid, target))
                assertEquals(initial, mutator.database)
                assertEquals(0, mutator.mutationCount)
                assertFalse(mutator.hasMutations)
            }
            listOf(initial.content.group.uuid, Uuid.random()).forEach { sourceUuid ->
                val mutator = KeePassDbMutator(initial)
                assertFalse(mutator.moveGroup(sourceUuid, child.uuid))
                assertEquals(initial, mutator.database)
                assertEquals(0, mutator.mutationCount)
            }
        }
    }

    @Test
    fun validGroupMovesPreserveSubtreeAndDeletionRecords() {
        databases().forEach { database ->
            val source = Group(
                Uuid.random(), "Source",
                groups = listOf(Group(Uuid.random(), "Child", entries = listOf(Entry(Uuid.random())))),
                entries = listOf(Entry(Uuid.random())),
                times = TimeData.create(originalTime),
            )
            val oldParent = Group(Uuid.random(), "Old parent", groups = listOf(source))
            val target = Group(Uuid.random(), "Target")
            val initial = database.withGroups(listOf(oldParent, target))

            listOf(target.uuid, null).forEach { destination ->
                val mutator = KeePassDbMutator(initial)
                assertTrue(mutator.moveGroup(source.uuid, destination))
                val (parent, moved) = mutator.database.getGroup { it.uuid == source.uuid }!!
                assertEquals(destination ?: initial.content.group.uuid, parent?.uuid)
                assertEquals(source, moved)
                assertEquals(emptyList(), mutator.database.getGroup { it.uuid == oldParent.uuid }!!.second.groups)
                assertEquals(initial.content.deletedObjects, mutator.database.content.deletedObjects)
                assertEquals(1, mutator.mutationCount)

                val afterMove = mutator.database
                assertFalse(mutator.moveGroup(source.uuid, destination))
                assertEquals(afterMove, mutator.database)
                assertEquals(1, mutator.mutationCount)
            }
        }
    }

    @Test
    fun entryUpdatesPreserveRequestedExpiryAndTimestamps() {
        val disabled = TimeData.create(originalTime)
        val enabled = disabled.copy(expires = true, expiryTime = deadline)
        databases().forEach { database ->
            listOf(disabled to enabled, enabled to disabled, null to enabled).forEach { (before, after) ->
                val entry = Entry(Uuid.random(), times = before)
                val nested = Group(Uuid.random(), "Nested", entries = listOf(entry))
                val initial = database.withGroups(listOf(Group(Uuid.random(), "Parent", groups = listOf(nested))))
                val requested = entry.copy(
                    times = after.copy(
                        lastAccessTime = deadline,
                        lastModificationTime = originalTime,
                        locationChanged = deadline,
                        usageCount = 7,
                    ),
                    overrideUrl = "https://example.com",
                )
                val mutator = KeePassDbMutator(initial)
                assertTrue(mutator.modifyEntry(entry.uuid) { requested })
                assertEquals(requested, mutator.database.getEntry { it.uuid == entry.uuid }!!.second)
                assertEquals(initial.content.deletedObjects, mutator.database.content.deletedObjects)
                assertEquals(1, mutator.mutationCount)
            }
        }
    }

    @Test
    fun missingEntryDoesNotInvokeTransformOrCountMutation() {
        databases().forEach { database ->
            val mutator = KeePassDbMutator(database)
            assertFalse(mutator.modifyEntry(Uuid.random()) { error("Missing entry must not be transformed") })
            assertEquals(database, mutator.database)
            assertEquals(0, mutator.mutationCount)
        }
    }

    private fun KeePassDatabase.withGroups(groups: List<Group>) = modifyContent {
        copy(
            group = group.copy(groups = groups),
            deletedObjects = listOf(DeletedObject(Uuid.random(), originalTime)),
        )
    }

    private fun databases(): List<KeePassDatabase> = listOf(
        KeePassDatabase.Ver3x.create("Root", Meta(), Credentials.from(EncryptedValue.fromString("password"))),
        KeePassDatabase.Ver4x.create("Root", Meta(), Credentials.from(EncryptedValue.fromString("password"))),
    )
}
