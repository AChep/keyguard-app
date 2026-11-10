package com.artemchep.keyguard.feature.p2psync.model

import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The names of the kinds and scopes travel between builds and stay in storage: a rename
 * breaks both. Adding one adds it here.
 */
class SyncKindsTest {
    @Test
    fun `the wire names of the kinds never change`() {
        assertEquals(
            mapOf(
                EntryKind.EMAIL_FORWARDER to "emailForwarder",
                EntryKind.WORDLIST to "wordlist",
                EntryKind.URL_OVERRIDE to "urlOverride",
                EntryKind.URL_BLOCK to "urlBlock",
                EntryKind.OBJECT_KEY to "objectKey",
            ),
            EntryKind.entries.associateWith { it.wireName },
        )
    }

    @Test
    fun `the wire names of the scopes never change`() {
        assertEquals(
            mapOf(SyncScope.APP to "app"),
            SyncScope.entries.associateWith { it.wireName },
        )
    }

    @Test
    fun `the room labels and SAS infos of the scopes never change`() {
        assertEquals("keyguard-app-room", SyncScope.APP.roomLabel)
        assertEquals("keyguard-sas/app/v1", SyncScope.APP.sasInfo)
        assertEquals(SyncScope.entries.size, SyncScope.entries.map { it.roomLabel }.toSet().size)
        assertEquals(SyncScope.entries.size, SyncScope.entries.map { it.sasInfo }.toSet().size)
    }

    @Test
    fun `a wire name is the serial name`() {
        EntryKind.entries.forEach { kind ->
            assertEquals(JsonPrimitive(kind.wireName), P2pJson.encodeToJsonElement(EntryKind.serializer(), kind))
            assertEquals(kind, EntryKind.ofWireName(kind.wireName))
        }
        SyncScope.entries.forEach { scope ->
            assertEquals(JsonPrimitive(scope.wireName), P2pJson.encodeToJsonElement(SyncScope.serializer(), scope))
            assertEquals(scope, SyncScope.ofWireName(scope.wireName))
        }
        assertNull(EntryKind.ofWireName("EMAIL_FORWARDER"))
        assertNull(SyncScope.ofWireName("unknown"))
    }

    @Test
    fun `only the object key is internal to the group`() {
        assertEquals(setOf(EntryKind.OBJECT_KEY), EntryKind.entries.filter { it.internal }.toSet())
    }
}
