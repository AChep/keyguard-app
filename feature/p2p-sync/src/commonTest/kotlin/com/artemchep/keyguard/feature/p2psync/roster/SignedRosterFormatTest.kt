package com.artemchep.keyguard.feature.p2psync.roster

import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.signToBase64Url
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.DeviceKeys
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.session.decodePayloadOrNull
import com.artemchep.keyguard.feature.p2psync.session.encodePayload
import com.artemchep.keyguard.feature.p2psync.testing.TestCrypto
import com.artemchep.keyguard.feature.p2psync.testing.TestDeviceKeys
import com.artemchep.keyguard.feature.p2psync.testing.TestGroup
import com.artemchep.keyguard.feature.p2psync.testing.nextUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed format of device records and revocations: what a newer build adds survives
 * older builds and verifies everywhere, the group binds every signature, and the validator
 * enforces the same limits on every device.
 */
class SignedRosterFormatTest {
    private val crypto = TestCrypto(51)
    private val random = Random(51)
    private val group = TestGroup(crypto, size = 3, random = random)
    private val validator = group.validator
    private val founder = group.keys[0]
    private val second = group.keys[1]
    private val third = group.keys[2]

    private fun at(wall: Long, node: String = founder.id) = Hlc(wall, 0, node)

    private fun record(roster: Roster, id: String) = roster.devices.single { it.id == id }

    /** The roster of the group with the record [original] replaced by [record]. */
    private fun replaced(original: RosterDevice, record: RosterDevice): Roster =
        group.roster.copy(devices = group.roster.devices - original + record)

    /** A new device of [keys] with the field [name] a newer build added, admitted by the founder. */
    private fun futureRecord(keys: TestDeviceKeys, name: String = "future"): RosterDevice {
        val unsigned = group.unsigned(keys, by = founder, at = at(4_000))
        val future = RosterDevice.fromRaw(JsonObject(unsigned.raw + (name to JsonPrimitive("value"))))
        return group.signer.admit(group.signer.selfSign(future, keys.signPrivate), founder.authorityPrivate)
    }

    /** [revocation] with the field [name] a newer build added, signed again by the authority key of [by]. */
    private fun withField(revocation: Revocation, by: TestDeviceKeys, name: String = "future"): Revocation {
        val unsigned = Revocation.fromRaw(JsonObject(revocation.raw - "sig" + (name to JsonPrimitive("value"))))
        val bytes = RosterSignatures.revocationBytes(group.groupId, unsigned)
        return unsigned.copy(sig = crypto.signToBase64Url(by.authorityPrivate, bytes))
    }

    /** [revocation] with the seal [admitted], signed again by the authority key of [by]. */
    private fun withSeal(revocation: Revocation, by: TestDeviceKeys, admitted: List<String>): Revocation {
        val unsigned = revocation.copy(admitted = admitted, sig = "")
        val bytes = RosterSignatures.revocationBytes(group.groupId, unsigned)
        return unsigned.copy(sig = crypto.signToBase64Url(by.authorityPrivate, bytes))
    }

    @Test
    fun `a record with a field of a newer build verifies and survives every round trip`() {
        val keys = group.newKeys(random)
        val future = futureRecord(keys)
        val roster = group.roster.copy(devices = group.roster.devices + future)
        assertEquals(future, record(validator.validate(roster), keys.id))
        assertTrue(group.activity(validator.validate(roster)).isActive(keys.id))

        // Storage, as the app stores records.
        val text = P2pJson.encodeToString(RosterDevice.serializer(), future)
        val stored = P2pJson.decodeFromString(RosterDevice.serializer(), text)
        assertEquals(future, stored)
        // The wire, in a ROSTER message, and merged into another roster.
        val payload = Roster.serializer().encodePayload(roster)
        val decoded = assertNotNull(Roster.serializer().decodePayloadOrNull(payload))
        val merged = validator.merge(group.roster, decoded)
        assertEquals(JsonPrimitive("value"), record(merged, keys.id).raw["future"])
        assertEquals(merged, validator.merge(merged, merged))
    }

    @Test
    fun `a field of a newer build is part of the identity`() {
        val keys = group.newKeys(random)
        val future = futureRecord(keys)
        val plain = group.admit(keys, by = founder, at = at(4_000))
        assertNotEquals(group.identityHash(plain), group.identityHash(future))
        assertFalse(RosterSignatures.sameIdentity(plain, future))
        // The admission covers it: a record that changes it, even self-signed, is not admitted.
        val changed = RosterDevice.fromRaw(JsonObject(future.raw + ("future" to JsonPrimitive("other"))))
        val resigned = group.signer.selfSign(changed.copy(updatedAt = at(5_000, keys.id)), keys.signPrivate)
        assertTrue(validator.isSelfSigned(resigned))
        val founderRecord = record(group.roster, founder.id)
        assertFalse(validator.isAdmitted(resigned, founderRecord))
        assertTrue(validator.isAdmitted(future, founderRecord))
    }

    @Test
    fun `a device changes its caps with its own key`() {
        val original = record(group.roster, second.id)
        val caps = DeviceCaps(rules = 2, kinds = original.caps.kinds + "card-reader")
        val updated = group.signer.selfSign(
            original.copy(caps = caps, updatedAt = at(7_000, second.id)),
            second.signPrivate,
        )
        // The admission covers the identity only, so it stays valid.
        assertEquals(original.admission, updated.admission)
        val roster = validator.merge(group.roster, replaced(original, updated))
        assertEquals(updated, record(roster, second.id))
        // The caps are self-signed: another device can not change them.
        val unsigned = original.copy(caps = caps, updatedAt = at(7_000, second.id))
        val forged = group.signer.selfSign(unsigned, third.signPrivate)
        assertFalse(validator.isSelfSigned(forged))
    }

    @Test
    fun `a device adds or replaces its encryption keys under the same identity`() {
        val original = record(group.roster, second.id)
        assertNotNull(original.keys.mlKem1024)
        // A newer build adds a key of an algorithm this build does not know, in a form it
        // does not know, and replaces its ML-KEM key.
        val keys = DeviceKeys(
            JsonObject(
                mapOf(
                    DeviceKeys.ML_KEM_1024 to JsonPrimitive("bmV3LWtleQ"),
                    "future-kem" to JsonObject(mapOf("pub" to JsonPrimitive("Zm9v"), "level" to JsonPrimitive(5))),
                ),
            ),
        )
        val updated = group.signer.selfSign(
            original.copy(keys = keys, updatedAt = at(7_000, second.id)),
            second.signPrivate,
        )
        // The keys are not part of the identity: the admission stays valid, and so does
        // the seal of a revocation.
        assertEquals(original.admission, updated.admission)
        assertEquals(group.identityHash(original), group.identityHash(updated))
        val roster = validator.merge(group.roster, replaced(original, updated))
        val merged = record(roster, second.id)
        assertEquals(updated, merged)
        assertEquals("bmV3LWtleQ", merged.keys.mlKem1024)
        // The unknown key survives a round trip and a rename.
        val renamed = merged.copy(label = "Renamed")
        assertEquals(keys, renamed.keys)
        // The keys are self-signed: another device can not change them.
        val unsigned = original.copy(keys = keys, updatedAt = at(7_000, second.id))
        assertFalse(validator.isSelfSigned(group.signer.selfSign(unsigned, third.signPrivate)))
    }

    @Test
    fun `the group supports what every active device supports`() {
        val current = GroupCaps(DeviceCaps.RULES, DeviceCaps.CURRENT.kinds.toSet())
        assertEquals(current, group.activity(group.roster).caps)
        assertTrue(group.activity(group.roster).caps.supports(EntryKind.WORDLIST))

        // The second device runs a newer build; the third an older one without wordlists.
        fun recaps(keys: TestDeviceKeys, caps: DeviceCaps, roster: Roster): Roster {
            val original = record(roster, keys.id)
            val unsigned = original.copy(caps = caps, updatedAt = at(8_000, keys.id))
            val updated = group.signer.selfSign(unsigned, keys.signPrivate)
            return roster.copy(devices = roster.devices - original + updated)
        }
        val newer = DeviceCaps(rules = 2, kinds = listOf("card-reader", "emailForwarder", "wordlist"))
        var roster = recaps(second, newer, group.roster)
        roster = recaps(third, DeviceCaps(rules = 1, kinds = listOf("emailForwarder")), roster)
        val caps = group.activity(validator.validate(roster)).caps
        assertEquals(GroupCaps(rules = 1, kinds = setOf("emailForwarder")), caps)
        assertFalse(caps.supports(EntryKind.WORDLIST))
        assertTrue(caps.supports(EntryKind.EMAIL_FORWARDER))
        // Once the older device is removed, every device supports wordlists again.
        val removed = validator.validate(group.revoke(roster, third, founder, at(9_000)))
        assertTrue(group.activity(removed).caps.supports(EntryKind.WORDLIST))
    }

    @Test
    fun `a revocation with a field of a newer build verifies and survives a round trip`() {
        val revoked = group.revoke(group.roster, third, founder, at(5_000))
        val future = withField(revoked.revoked.single(), by = founder)
        val roster = revoked.copy(revoked = listOf(future))
        assertEquals(listOf(future), validator.validate(roster).revoked)
        assertFalse(group.activity(validator.validate(roster)).isActive(third.id))
        val text = P2pJson.encodeToString(Roster.serializer(), roster)
        val decoded = P2pJson.decodeFromString(Roster.serializer(), text)
        assertEquals(future, decoded.revoked.single())
        assertEquals(roster.contentHash(crypto::sha256), decoded.contentHash(crypto::sha256))
        // The field is signed.
        val tampered = Revocation.fromRaw(JsonObject(future.raw + ("future" to JsonPrimitive("other"))))
        assertEquals(emptyList(), validator.validate(roster.copy(revoked = listOf(tampered))).revoked)
    }

    @Test
    fun `a revocation must be canonical`() {
        val known = group.newKeys(random)
        val other = group.newKeys(random)
        var roster = group.roster.copy(
            devices = group.roster.devices +
                group.admit(known, by = second, at = at(4_000, second.id)) +
                group.admit(other, by = second, at = at(4_001, second.id)),
        )
        roster = group.revoke(roster, second, founder, at(5_000))
        val canonical = roster.revoked.single()
        assertEquals(2, canonical.admitted.size)
        assertEquals(canonical.admitted.sorted(), canonical.admitted)
        assertEquals(listOf(canonical), validator.validate(roster).revoked)
        assertEquals(52, canonical.admitted.first().length)

        // A seal out of order, or with an identity twice, is invalid even if signed.
        val unsorted = withSeal(canonical, founder, canonical.admitted.reversed())
        val twice = withSeal(canonical, founder, listOf(canonical.admitted[0], canonical.admitted[0]))
        // So is a signature in another base64url form.
        val padded = canonical.copy(sig = canonical.sig + "=")
        listOf(unsorted, twice, padded).forEach { revocation ->
            assertEquals(emptyList(), validator.validate(roster.copy(revoked = listOf(revocation))).revoked)
        }
        // Exact copies of one revocation collapse.
        val copies = roster.copy(revoked = listOf(canonical, Revocation.fromRaw(canonical.raw)))
        assertEquals(listOf(canonical), copies.normalized().revoked)
    }

    @Test
    fun `the label and the platform of a record follow the rules every device checks`() {
        val valid = listOf(
            "Phone",
            "x",
            "x".repeat(DeviceRecordRules.MAX_LABEL_LENGTH),
            // 64 code points, 128 UTF-16 units.
            "😀".repeat(DeviceRecordRules.MAX_LABEL_LENGTH),
            // Emoji sequences: joiners and variation selectors.
            "👩‍💻 Laptop",
            "❤️",
            "Téléphone ﷽",
        )
        valid.forEach { assertTrue(DeviceRecordRules.isValidLabel(it), it) }
        val invalid = listOf(
            "",
            "x".repeat(DeviceRecordRules.MAX_LABEL_LENGTH + 1),
            "😀".repeat(DeviceRecordRules.MAX_LABEL_LENGTH + 1),
            "line\nbreak",
            "tab\t",
            "nul\u0000",
            "del\u007F",
            "c1\u0085",
            "line separator",
            "paragraph separator",
            "rtl‮override",
            "isolate⁦x⁩",
            "mark‏",
            "arabic؜",
            "lone\uD800surrogate",
        )
        invalid.forEach { assertFalse(DeviceRecordRules.isValidLabel(it), it) }

        assertTrue(DeviceRecordRules.isValidPlatform("android"))
        assertTrue(DeviceRecordRules.isValidPlatform("x".repeat(DeviceRecordRules.MAX_PLATFORM_LENGTH)))
        assertFalse(DeviceRecordRules.isValidPlatform("x".repeat(DeviceRecordRules.MAX_PLATFORM_LENGTH + 1)))
        assertFalse(DeviceRecordRules.isValidPlatform("andröid"))
        assertFalse(DeviceRecordRules.isValidPlatform("and\nroid"))
    }

    @Test
    fun `a signed record that breaks the rules is dropped`() {
        val original = record(group.roster, second.id)
        val newer = at(7_000, second.id)
        val renamed = group.signer.update(original, newer, second.signPrivate, label = "evil‮gnp.exe")
        assertTrue(validator.isSelfSigned(renamed))
        assertFalse(validator.isWellFormed(renamed))
        val roster = validator.merge(group.roster, replaced(original, renamed))
        // The older valid record stays.
        assertEquals(original, record(roster, second.id))

        val keys = group.newKeys(random)
        val unsigned = group.unsigned(keys, by = founder, at = at(4_000)).copy(platform = "x".repeat(33))
        val signed = group.signer.selfSign(unsigned, keys.signPrivate)
        val longPlatform = group.signer.admit(signed, founder.authorityPrivate)
        val validated = validator.validate(group.roster.copy(devices = group.roster.devices + longPlatform))
        assertFalse(keys.id in validated.devices.map { it.id })
    }

    @Test
    fun `a record or a revocation larger than the limits is dropped`() {
        val original = record(group.roster, second.id)
        val kinds = List(DeviceRecordRules.MAX_RECORD_BYTES / 8) { "kind-$it" }
        val bloated = group.signer.selfSign(
            original.copy(caps = DeviceCaps(rules = 1, kinds = kinds), updatedAt = at(7_000, second.id)),
            second.signPrivate,
        )
        assertTrue(validator.isSelfSigned(bloated))
        assertFalse(validator.isWellFormed(bloated))
        // The older valid record stays.
        assertEquals(original, record(validator.merge(group.roster, replaced(original, bloated)), second.id))

        val revoked = group.revoke(group.roster, third, founder, at(8_000))
        val seal = List(Roster.MAX_DEVICES + 1) { index -> index.toString().padStart(52, 'A') }
        val oversealed = withSeal(revoked.revoked.single(), founder, seal)
        val merged = validator.merge(group.roster, group.roster.copy(revoked = listOf(oversealed)))
        assertTrue(group.activity(merged).isActive(third.id))
    }

    @Test
    fun `a record nested deeper than the limit is dropped`() {
        val original = record(group.roster, second.id)
        val nested = (1..DeviceRecordRules.MAX_RECORD_DEPTH).fold<Int, JsonElement>(JsonPrimitive(0)) { inner, _ ->
            JsonArray(listOf(inner))
        }
        // The caps are the device's own to sign; a field a newer build adds survives in them.
        val caps = JsonObject(original.raw.getValue("caps").jsonObject + ("future" to nested))
        val unsigned = RosterDevice.fromRaw(JsonObject(original.raw + ("caps" to caps)))
            .copy(updatedAt = at(7_000, second.id))
        val deep = group.signer.selfSign(unsigned, second.signPrivate)
        assertTrue(validator.isSelfSigned(deep))
        assertTrue(CanonicalJson.encode(deep.raw).length < DeviceRecordRules.MAX_RECORD_BYTES)
        assertFalse(validator.isWellFormed(deep))
        assertEquals(original, record(validator.merge(group.roster, replaced(original, deep)), second.id))
    }

    @Test
    fun `a merge into a roster that no message carries is refused whole`() {
        val padding = "x".repeat(DeviceRecordRules.MAX_REVOCATION_BYTES - 1_024)
        fun revocations(count: Int) = List(count) { index ->
            // An authority key may revoke a device no roster holds.
            val target = random.nextUuid()
            val revocation = group.signer
                .revoke(group.roster, target, at(8_000L + index), founder.id, founder.authorityPrivate)
            val padded = revocation.raw - "sig" + ("padding" to JsonPrimitive(padding))
            val unsigned = Revocation.fromRaw(JsonObject(padded))
            val bytes = RosterSignatures.revocationBytes(group.groupId, unsigned)
            unsigned.copy(sig = crypto.signToBase64Url(founder.authorityPrivate, bytes))
        }
        val few = group.roster.copy(revoked = revocations(8))
        assertEquals(8, validator.merge(group.roster, few).revoked.size)
        assertEquals(validator.merge(group.roster, few), validator.mergeOrNull(group.roster, few))

        val many = group.roster.copy(revoked = revocations(Roster.MAX_BYTES / padding.length + 1))
        assertEquals(validator.validate(group.roster), validator.merge(group.roster, many))
        assertNull(validator.mergeOrNull(group.roster, many))
    }

    @Test
    fun `a device without an authority key leaves once`() {
        val keys = group.newKeys(random)
        val unsigned = group.unsigned(keys, by = founder, at = at(4_000)).copy(authorityPub = "")
        val watch = group.signer.admit(group.signer.selfSign(unsigned, keys.signPrivate), founder.authorityPrivate)
        val roster = group.roster.copy(devices = group.roster.devices + watch)
        // A stolen device signs leave after leave with its device key.
        val leaves = List(16) { index ->
            group.signer.leave(roster, keys.id, at(9_000L - index, keys.id), keys.signPrivate)
        }
        val validated = validator.validate(roster.copy(revoked = leaves))
        assertEquals(listOf(leaves.last()), validated.revoked)
        assertEquals(at(9_000L - 15, keys.id), group.activity(validated).revokedAt(keys.id))

        // A device with an authority key signs its leave with it, and every one counts.
        val founderLeaves = List(2) { index ->
            group.signer.leave(roster, second.id, at(9_000L + index), second.authorityPrivate)
        }
        assertEquals(2, validator.validate(roster.copy(revoked = founderLeaves)).revoked.size)
    }

    @Test
    fun `a roster holds at most 256 devices and a merge past it is refused whole`() {
        val full = Roster(
            founder = founder.id,
            devices = group.roster.devices + List(Roster.MAX_DEVICES - group.roster.devices.size) { index ->
                group.admit(group.newKeys(random), by = founder, at = at(2_000L + index))
            },
        )
        assertEquals(Roster.MAX_DEVICES, validator.validate(full).devices.size)
        val newcomer = group.newKeys(random)
        val revocation = group.revoke(full, third, founder, at(9_000)).revoked
        val incoming = full.copy(
            devices = full.devices + group.admit(newcomer, by = founder, at = at(9_000)),
            revoked = revocation,
        )
        // Not even the revocation that came with it counts: nothing is evicted.
        val merged = validator.merge(full, incoming)
        assertEquals(validator.validate(full), merged)
        assertTrue(group.activity(merged).isActive(third.id))
        // The refusal shows, so the entries that came with the roster can be dropped too.
        assertNull(validator.mergeOrNull(full, incoming))
        // A roster past the cap is not a valid roster at all.
        assertEquals(emptyList(), validator.validate(incoming).devices)
        // Without the newcomer, the revocation merges.
        val revoked = assertNotNull(validator.mergeOrNull(full, full.copy(revoked = revocation)))
        assertFalse(group.activity(revoked).isActive(third.id))
    }

    @Test
    fun `the group id derives from the founder and binds every signature`() {
        val founderRecord = record(group.roster, founder.id)
        assertEquals(group.groupId, founderRecord.derivedGroupId(crypto::sha256))
        // A UUID, as the ids of groups always were.
        assertNotNull(kotlin.uuid.Uuid.parseHexDashOrNull(group.groupId))
        // The derivation ignores what the founder may change, and its signatures.
        val renamed = group.signer.update(founderRecord, at(7_000), founder.signPrivate, label = "Renamed")
        assertEquals(group.groupId, renamed.derivedGroupId(crypto::sha256))

        // A validator of another group keeps nothing: the founder does not derive its id.
        val other = RosterValidator(crypto, random.nextUuid())
        assertEquals(emptyList(), other.validate(group.roster).devices)
        // Nor do the signatures verify there.
        assertFalse(other.isSelfSigned(founderRecord))
        assertFalse(other.isAdmitted(founderRecord, founderRecord))
        val revocation = group.revoke(group.roster, third, founder, at(5_000)).revoked.single()
        assertFalse(other.isValidRevocation(revocation, founderRecord))
        assertTrue(validator.isValidRevocation(revocation, founderRecord))
    }

    @Test
    fun `a record with a number without a canonical form can not be read`() {
        val original = record(group.roster, second.id)
        val float = JsonObject(original.raw + ("caps" to P2pJson.parseToJsonElement("""{"rules":1.0,"kinds":[]}""")))
        assertFailsWith<IllegalArgumentException> { RosterDevice.fromRaw(float) }
        val large = JsonObject(original.raw + ("future" to P2pJson.parseToJsonElement("9007199254740992")))
        assertFailsWith<IllegalArgumentException> { RosterDevice.fromRaw(large) }
        val text = P2pJson.encodeToString(Roster.serializer(), group.roster).replace("\"rules\":1", "\"rules\":1e0")
        assertFailsWith<IllegalArgumentException> { P2pJson.decodeFromString(Roster.serializer(), text) }
        assertEquals(CanonicalJson.encode(original.raw), CanonicalJson.encode(RosterDevice.fromRaw(original.raw).raw))
    }
}
