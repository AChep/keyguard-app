package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockRepositoryExposed
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class AppLinkUrlBlocksTest : AppSyncRowContractTest(UrlBlockTestRows) {
    @Test
    fun `a local edit is recorded and only an exposed rule is copied to the exposed database`() = runTest {
        val (a) = linkedPair {}
        a.addUrlBlock("Shown", exposed = true)
        a.addUrlBlock("Hidden", exposed = false)

        assertEquals(
            setOf(a.urlBlockSyncId("Shown"), a.urlBlockSyncId("Hidden")),
            a.entriesOf(EntryKind.URL_BLOCK).map { it.id }.toSet(),
        )
        assertEquals(mapOf(a.urlBlockId("Shown") to "Shown"), a.exposedUrlBlocks())

        // The copy follows the switch.
        a.time += 1_000
        a.editUrlBlock(a.urlBlock("Shown"), exposed = false)
        a.editUrlBlock(a.urlBlock("Hidden"), exposed = true)
        assertEquals(mapOf(a.urlBlockId("Hidden") to "Hidden"), a.exposedUrlBlocks())

        a.time += 1_000
        a.removeUrlBlock(a.urlBlock("Hidden"))
        assertEquals(emptyMap(), a.exposedUrlBlocks())
    }

    @Test
    fun `a rule that blocks only saving syncs with its scope and one that blocks everything without one`() = runTest {
        val (a, b) = linkedPair {}
        a.addUrlBlock("Bank", scope = UrlBlockScope.SAVE)
        a.addUrlBlock("Shop", uri = "shop.example")
        a.exchangeWith(b)

        assertEquals(JsonPrimitive("save"), a.urlBlockBody("Bank")["scope"])
        // The body of the builds before scopes.
        assertEquals(
            setOf("name", "description", "uri", "mode", "enabled", "exposed"),
            a.urlBlockBody("Shop").keys,
        )
        assertEquals(UrlBlockScope.SAVE, b.urlBlock("Bank").scope)
        assertEquals(UrlBlockScope.ALL, b.urlBlock("Shop").scope)
    }

    @Test
    fun `a scope that is missing, unknown or not a string blocks everything`() = runTest {
        val (a, b) = linkedPair {}
        val scopes = mapOf<String, JsonElement?>(
            "Missing" to null,
            "Unknown" to JsonPrimitive("fill"),
            "Number" to JsonPrimitive(1),
            "Object" to buildJsonObject { put("key", "save") },
            "Save" to JsonPrimitive("save"),
        )
        val entries = scopes.map { (name, scope) -> b.urlBlockEntry(urlBlockBody(name, scope)) }
        a.receive(b, b.envelope().copy(entries = entries))

        assertEquals(
            mapOf(
                "Missing" to UrlBlockScope.ALL,
                "Unknown" to UrlBlockScope.ALL,
                "Number" to UrlBlockScope.ALL,
                "Object" to UrlBlockScope.ALL,
                "Save" to UrlBlockScope.SAVE,
            ),
            a.urlBlockList().associate { it.name to it.scope },
        )
        assertEquals(emptyList(), a.logs.filter { it.startsWith(NOT_WRITTEN) })
        // Kept as they came, also for the devices that know the scope.
        entries.forEach { entry -> assertEquals(entry, a.entries()[entry.key]) }
    }

    @Test
    fun `a change of the scope keeps the id and updates the rule in place`() = runTest {
        val (a, b) = linkedPair {}
        a.addUrlBlock("Bank")
        a.exchangeWith(b)
        val syncId = a.urlBlockSyncId("Bank")
        val rowId = b.urlBlockId("Bank")

        a.time += 1_000
        a.editUrlBlock(a.urlBlock("Bank"), scope = UrlBlockScope.SAVE)
        assertEquals(syncId, a.urlBlockSyncId("Bank"))
        assertEquals(listOf(syncId), a.entriesOf(EntryKind.URL_BLOCK).map { it.id })
        a.exchangeWith(b)

        assertEquals(UrlBlockScope.SAVE, b.urlBlock("Bank").scope)
        assertEquals(rowId, b.urlBlockId("Bank"))
        assertEquals(syncId, b.urlBlockSyncId("Bank"))

        // And back: the body loses its scope.
        a.time += 1_000
        a.editUrlBlock(a.urlBlock("Bank"), scope = UrlBlockScope.ALL)
        assertFalse("scope" in a.urlBlockBody("Bank"))
        a.exchangeWith(b)

        assertEquals(UrlBlockScope.ALL, b.urlBlock("Bank").scope)
        assertEquals(rowId, b.urlBlockId("Bank"))
    }

    @Test
    fun `a local change of the scope of a synced rule stays`() = runTest {
        val (a, b) = linkedPair {}
        a.addUrlBlock("Bank", scope = UrlBlockScope.SAVE)
        a.exchangeWith(b)

        b.time += 1_000
        b.editUrlBlock(b.urlBlock("Bank"), scope = UrlBlockScope.ALL)
        b.materialize()
        assertEquals(UrlBlockScope.ALL, b.urlBlock("Bank").scope)

        a.time = b.time + 1_000
        a.exchangeWith(b)
        assertEquals(UrlBlockScope.ALL, a.urlBlock("Bank").scope)
        assertEquals(UrlBlockScope.ALL, b.urlBlock("Bank").scope)

        a.time += 1_000
        a.editUrlBlock(a.urlBlock("Bank"), scope = UrlBlockScope.SAVE)
        a.materialize()
        assertEquals(UrlBlockScope.SAVE, a.urlBlock("Bank").scope)
    }

    @Test
    fun `the exposed copy keeps the scope`() = runTest {
        AppLinkTestDevice("A").use { a ->
            val exposed = UrlBlockRepositoryExposed(a.exposedDbManager, Dispatchers.Unconfined)
            suspend fun scopes() = exposed.get().first().associate { it.name to it.scope }
            a.addUrlBlock("Full")
            a.addUrlBlock("Save", uri = "shop.example", scope = UrlBlockScope.SAVE)
            assertEquals(mapOf("Full" to UrlBlockScope.ALL, "Save" to UrlBlockScope.SAVE), scopes())

            a.editUrlBlock(a.urlBlock("Full"), scope = UrlBlockScope.SAVE)
            a.editUrlBlock(a.urlBlock("Save"), scope = UrlBlockScope.ALL)
            assertEquals(mapOf("Full" to UrlBlockScope.SAVE, "Save" to UrlBlockScope.ALL), scopes())
            assertEquals(mapOf("Full" to "save", "Save" to "all"), a.exposedUrlBlockScopes())
        }
    }

    private suspend fun AppLinkTestDevice.urlBlockId(name: String): String = requireNotNull(urlBlock(name).id)

    /** The body of the entry of the rule [name]. */
    private suspend fun AppLinkTestDevice.urlBlockBody(name: String): JsonObject =
        requireNotNull(entries()[EntryKey(EntryKind.URL_BLOCK, urlBlockSyncId(name))]?.body)

    /** A new rule with the [body], written by this device. */
    private fun AppLinkTestDevice.urlBlockEntry(body: JsonObject): SyncEntry {
        val membership = membership()
        val id = Uuid.random().toString()
        val kind = EntryKind.URL_BLOCK
        val stamp = Hlc(wall = time, counter = 0, node = membership.device.deviceId)
        val parts = (kind.schema.parts.map { it.key } + EntryVersion.REMAINDER).associateWith { stamp }
        val signer = EntrySigner(crypto, membership.groupId, membership.device.signPrivateKey())
        val version = signer.version(id, kind, stamp, VersionVector.of(listOf(stamp)), body, parts)
        return SyncEntry(id = id, kind = kind, versions = listOf(version))
    }
}

/** The body of a blocked URI with the [scope], or without one. */
private fun urlBlockBody(name: String, scope: JsonElement?): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "")
    put("uri", "$name.example".lowercase())
    put("mode", "domain")
    put("enabled", true)
    put("exposed", true)
    if (scope != null) put("scope", scope)
}

/** How the log of a pass starts the count of the records it left out, by kind. */
private const val NOT_WRITTEN = "Not written, this build can not read them: "
