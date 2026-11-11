package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.RosterCrdt
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The signed format as the app stores and relays it: fields and kinds of newer builds pass
 * through, the link id derives from its founder, and an edit the peers would refuse stays
 * on its device.
 */
class AppLinkSignedFormatTest {
    /** Three linked apps: A founds the link, B and C join it. */
    private suspend fun linkedTrio(): Triple<AppLinkTestDevice, AppLinkTestDevice, AppLinkTestDevice> {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        val c = AppLinkTestDevice("C")
        a.link()
        b.time = a.time + 1
        b.join(a)
        a.time = b.time + 1
        c.time = a.time + 1
        c.join(a)
        a.time = c.time + 1
        b.time = a.time
        b.receive(a)
        return Triple(a, b, c)
    }

    /** A version of the entry [id] by this device, signed as a build that adds [extra] would sign it. */
    private fun AppLinkTestDevice.version(
        id: String,
        kind: String,
        body: JsonObject,
        extra: Map<String, JsonPrimitive> = emptyMap(),
    ): EntryVersion {
        val membership = membership()
        val stamp = Hlc(wall = time, counter = 0, node = membership.device.deviceId)
        val parts = EntryKind.ofWireName(kind)
            ?.let { known -> known.schema.parts.map { it.key } + EntryVersion.REMAINDER }
            .orEmpty()
            .associateWith { stamp }
        val history = VersionVector.of(listOf(stamp))
        val unsigned = EntryVersion(stamp = stamp, history = history, body = body, parts = parts)
        if (extra.isEmpty()) {
            val signer = EntrySigner(crypto, membership.groupId, membership.device.signPrivateKey())
            return signer.version(id, kind, stamp, history, body, parts)
        }
        // The exact bytes a version signature covers, see EntrySignatures.
        val raw = JsonObject(unsigned.raw - "sig" + extra)
        val signed = buildJsonObject {
            put("context", buildJsonObject {
                put("entry", id)
                put("group", membership.groupId)
                put("kind", kind)
            })
            put("object", raw)
        }
        val bytes = "keyguard-entry-version/v1\n${CanonicalJson.encode(signed)}".encodeToByteArray()
        val sig = P2pEncoding.base64Url(crypto.ed25519Sign(membership.device.signPrivateKey(), bytes))
        return EntryVersion.fromRaw(JsonObject(raw + ("sig" to JsonPrimitive(sig))))
    }

    @Test
    fun `the id of a link derives from its founder, on every device`() = runTest {
        val (a, b) = linkedTrio()
        val roster = KeyguardSyncStore(a.db, P2pGroupId.APP_LINK_ID).loadRoster(a.membership().founderId)
        val founder = roster.devices.single { it.id == roster.founder }
        assertEquals(a.membership().groupId, founder.derivedGroupId(a.crypto::sha256))
        assertEquals(a.membership().groupId, b.membership().groupId)
        assertNotNull(Uuid.parseHexDashOrNull(a.membership().groupId))
    }

    @Test
    fun `a bundle whose link id does not derive from its founder is refused`() = runTest {
        val a = AppLinkTestDevice("A")
        val b = AppLinkTestDevice("B")
        a.link()
        b.time = a.time + 1
        val device = b.newDevice()
        val bundle = a.admit(device)
        val other = bundle.copy(group = Uuid.random().toString())
        assertFailsWith<IllegalArgumentException> {
            b.service.import(other, device, P2pNetwork())
        }
        assertNull(b.service.membership())
        // The bundle as sent joins.
        b.service.import(bundle, device, P2pNetwork())
        assertEquals(a.membership().groupId, b.membership().groupId)
    }

    @Test
    fun `a field of a newer build is verified, stored and relayed`() = runTest {
        val (a, b, c) = linkedTrio()
        val id = Uuid.random().toString()
        val body = buildJsonObject {
            put("name", "Future")
            put("type", "duckduckgo")
            put("data", buildJsonObject { put("apiKey", "key") })
        }
        val extra = mapOf("future" to JsonPrimitive("x"))
        val version = b.version(id, EntryKind.EMAIL_FORWARDER.wireName, body, extra)
        val entry = SyncEntry(id = id, kind = EntryKind.EMAIL_FORWARDER, versions = listOf(version))
        a.receive(b, b.envelope().copy(entries = listOf(entry)))

        val stored = a.entries().getValue(EntryKey(EntryKind.EMAIL_FORWARDER, id))
        assertEquals(JsonPrimitive("x"), stored.versions.single().raw["future"])
        assertEquals("Future", a.forwarder("Future").name)
        // A device that hears of it only from A takes it too: the signature still verifies.
        c.time = a.time
        c.receive(a)
        assertEquals(stored, c.entries().getValue(EntryKey(EntryKind.EMAIL_FORWARDER, id)))
        assertEquals("Future", c.forwarder("Future").name)
    }

    @Test
    fun `an entry of a kind this build does not know is kept and relayed, never written to rows`() = runTest {
        val (a, b, c) = linkedTrio()
        val id = Uuid.random().toString()
        val body = buildJsonObject { put("name", "Card") }
        val entry = SyncEntry(id = id, kind = "card-reader", versions = listOf(b.version(id, "card-reader", body)))
        val forwarders = a.forwarderList()
        a.receive(b, b.envelope().copy(entries = listOf(entry)))

        assertEquals(entry, a.entries()[EntryKey("card-reader", id)])
        assertEquals(forwarders, a.forwarderList())
        assertTrue(entry in a.envelope().entries)
        c.time = a.time
        c.receive(a)
        assertEquals(entry, c.entries()[EntryKey("card-reader", id)])
        assertEquals(emptyList(), c.wordlistList())
    }

    @Test
    fun `a body the category can not use is kept but not written`() = runTest {
        val (a, b) = linkedTrio()
        val bodies = listOf(
            EntryKind.EMAIL_FORWARDER to buildJsonObject {
                put("name", "No type")
                put("data", buildJsonObject { put("apiKey", "key") })
            },
            EntryKind.EMAIL_FORWARDER to buildJsonObject {
                put("name", "Numbers")
                put("type", "duckduckgo")
                put("data", buildJsonObject { put("apiKey", 1) })
            },
            EntryKind.WORDLIST to buildJsonObject {
                put("name", "Not a blob")
                put("words", "words")
            },
            EntryKind.WORDLIST to buildJsonObject {
                put("name", "x".repeat(WordlistsSyncCategory.MAX_NAME_LENGTH + 1))
                put("words", "a".repeat(52))
            },
            // Base32 with stray bits: no blob has this id.
            EntryKind.WORDLIST to buildJsonObject {
                put("name", "Not canonical")
                put("words", NONCANONICAL_BLOB_ID)
            },
            EntryKind.URL_OVERRIDE to buildJsonObject {
                put("name", "Bad regex")
                put("regex", "(")
                put("command", "https://{url:rmvscm}")
                put("enabled", true)
            },
            EntryKind.URL_OVERRIDE to buildJsonObject {
                put("name", "Text switch")
                put("regex", "^http:.*")
                put("command", "https://{url:rmvscm}")
                put("enabled", "true")
            },
            EntryKind.URL_BLOCK to urlBlockBody(name = "Bad regex", uri = "(", mode = "regex"),
            EntryKind.URL_BLOCK to urlBlockBody(name = "Unknown mode", mode = "sometimes"),
            EntryKind.URL_BLOCK to urlBlockBody(name = "Blank", uri = " "),
            EntryKind.URL_BLOCK to urlBlockBody(name = "Text switch", exposed = JsonPrimitive("true")),
            EntryKind.PRIVILEGED_APP to buildJsonObject { put("packageName", "com.example.browser") },
            EntryKind.PRIVILEGED_APP to buildJsonObject { put("certFingerprintSha256", TEST_CERT_FINGERPRINT) },
            EntryKind.PRIVILEGED_APP to privilegedAppBody(JsonPrimitive(1)),
            EntryKind.PRIVILEGED_APP to privilegedAppBody(JsonPrimitive(TEST_CERT_FINGERPRINT.lowercase())),
        )
        val entries = bodies.map { (kind, body) ->
            val id = Uuid.random().toString()
            SyncEntry(id = id, kind = kind, versions = listOf(b.version(id, kind.wireName, body)))
        }
        a.receive(b, b.envelope().copy(entries = entries))

        entries.forEach { entry -> assertEquals(entry, a.entries()[entry.key]) }
        assertEquals(emptyList(), a.forwarderList())
        assertEquals(emptyList(), a.wordlistList())
        assertEquals(listOf("HTTPS-ify"), a.urlOverrideList().map { it.name })
        assertEquals(emptyList(), a.urlBlockList())
        assertEquals(emptyList(), a.privilegedAppList())
        val refused = NOT_WRITTEN + "emailForwarder 2, privilegedApp 4, urlBlock 4, urlOverride 2, wordlist 3"
        assertEquals(listOf(refused), a.logs.filter { it.startsWith(NOT_WRITTEN) })
        // Nor wanted: the relay storage would refuse a state that names it missing.
        assertFalse(NONCANONICAL_BLOB_ID in a.components.blobs.wanted())
    }

    @Test
    fun `an edit the peers would refuse stays on its device`() = runTest {
        val (a) = linkedTrio()
        val huge = "x".repeat(256 * 1024)
        a.addForwarder("Huge", "simplelogin", "apiKey" to huge)
        a.addForwarder("Small", "simplelogin", "apiKey" to "key")

        assertEquals(setOf("Huge", "Small"), a.forwarderList().map { it.name }.toSet())
        val ids = a.entries().keys.map { it.id }
        assertTrue(a.forwarderSyncId("Small") in ids)
        assertTrue(a.forwarderSyncId("Huge") !in ids)
        // A pass does not publish it either.
        a.materialize()
        assertTrue(a.forwarderSyncId("Huge") !in a.entries().keys.map { it.id })
    }

    @Test
    fun `a kind that an active device does not support is not published`() = runTest {
        val (a, b) = linkedTrio()
        // B runs a build without wordlists.
        val store = KeyguardSyncStore(b.db, P2pGroupId.APP_LINK_ID)
        val local = store.loadValidated(b.membership(), b.crypto)
        val self = requireNotNull(local.self)
        val older = RosterSigner(b.crypto, b.membership().groupId).selfSign(
            self.copy(
                caps = DeviceCaps(rules = DeviceCaps.RULES, kinds = listOf(EntryKind.EMAIL_FORWARDER.wireName)),
                updatedAt = Hlc(wall = self.updatedAt.wall + 1, counter = 0, node = self.id),
            ),
            local.signKey,
        )
        val renewed = Roster(founder = local.stored.founder, devices = listOf(older))
        store.saveRoster(RosterCrdt.merge(local.stored, renewed))
        a.receive(b)

        a.addWordlist("Colors", listOf("red", "green"))
        a.addForwarder("Work")
        // B wrote the object key while it ran the current build.
        val kinds = a.entries().keys.map { it.kind }.toSet() - EntryKind.OBJECT_KEY.wireName
        assertEquals(setOf(EntryKind.EMAIL_FORWARDER.wireName), kinds)
        assertEquals(listOf("Colors"), a.wordlistList().map { it.name })
    }

    @Test
    fun `the store keeps the fields and kinds of newer builds as they are`() = runTest {
        val (a) = linkedTrio()
        val store = KeyguardSyncStore(a.db, P2pGroupId.APP_LINK_ID)
        val roster = store.loadRoster(a.membership().founderId)
        val record = roster.devices.first()
        val futureRecord = RosterDevice.fromRaw(JsonObject(record.raw + ("future" to JsonPrimitive("x"))))
        val revocation = Revocation.fromRaw(
            JsonObject(
                Revocation(deviceId = "gone", at = Hlc(1L, 0, record.id), by = record.id).raw +
                    ("future" to JsonPrimitive("x")),
            ),
        )
        val future = Roster(founder = roster.founder, devices = listOf(futureRecord), revoked = listOf(revocation))
        store.saveRoster(future)
        assertEquals(future.normalized(), store.loadRoster(roster.founder))

        val id = Uuid.random().toString()
        val version = a.version(id, "card-reader", JsonObject(emptyMap()), mapOf("future" to JsonPrimitive("x")))
        val entry = SyncEntry(id = id, kind = "card-reader", versions = listOf(version))
        store.saveEntry(entry)
        assertEquals(entry, store.loadEntry(entry.key))
        assertEquals(entry, store.loadEntries()[entry.key])
    }
}

/** 52 base32 characters whose last one carries bits past the 256 of a SHA-256. */
private val NONCANONICAL_BLOB_ID = "b".repeat(52)

/** The body of a blocked URI; valid, unless a test passes a field the category refuses. */
private fun urlBlockBody(
    name: String,
    uri: String = "example.com",
    mode: String = "domain",
    exposed: JsonPrimitive = JsonPrimitive(true),
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", "")
    put("uri", uri)
    put("mode", mode)
    put("enabled", true)
    put("exposed", exposed)
}

/** How the log of a pass starts the count of the records it left out, by kind. */
private const val NOT_WRITTEN = "Not written, this build can not read them: "

private fun privilegedAppBody(
    fingerprint: JsonPrimitive,
): JsonObject = buildJsonObject {
    put("packageName", "com.example.browser")
    put("certFingerprintSha256", fingerprint)
}
