package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.model.DeviceKeys
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.feature.p2psync.roster.identityHash
import kotlin.random.Random
import kotlin.uuid.Uuid

/** Returns a random UUID string drawn from [random]. */
fun Random.nextUuid(): String = Uuid.fromByteArray(nextBytes(16)).toString()

/** The keys of one test device: its device key, its authority key and its Noise key. */
class TestDeviceKeys(
    val id: String,
    val signPrivate: ByteArray,
    val signPublic: String,
    val noisePublic: String,
    val noisePublicBytes: ByteArray,
    val authorityPrivate: ByteArray,
    val authorityPublic: String,
) {
    /** The same keys under another [id], as a forger would sign them. */
    fun withId(id: String): TestDeviceKeys =
        TestDeviceKeys(id, signPrivate, signPublic, noisePublic, noisePublicBytes, authorityPrivate, authorityPublic)
}

/**
 * A sync group of [size] devices: device 0 founds it and admits every other one, all with
 * valid (fake) signatures. The id of the group derives from the founder, see
 * [derivedGroupId].
 */
class TestGroup(
    val crypto: TestCrypto = TestCrypto(),
    size: Int = 2,
    random: Random = Random(1),
) {
    val keys: List<TestDeviceKeys> = List(size) { newKeys(random) }
    val founder: TestDeviceKeys get() = keys.first()
    val groupId: String = unsigned(founder, by = founder, at = addedAt(0)).derivedGroupId(crypto::sha256)
    val signer = RosterSigner(crypto, groupId)
    val validator = RosterValidator(crypto, groupId)

    val roster: Roster = Roster(
        founder = founder.id,
        devices = keys.mapIndexed { index, device ->
            admit(device, by = founder, at = addedAt(index))
        },
    )

    /** Which devices of [roster] are active. */
    fun activity(roster: Roster): RosterActivity = RosterActivity(roster, crypto::sha256)

    /** The identity hash of [device], as a revocation seals it. */
    fun identityHash(device: RosterDevice): String = device.identityHash(crypto::sha256)

    fun id(index: Int): String = keys[index].id

    /** A simulated device for each device of the group, all on the clock [physicalNow]. */
    fun simDevices(physicalNow: () -> Long): List<SimDevice> = keys.map { SimDevice(it.id, this, physicalNow) }

    /** The signer of the writes of [device] to the entries of the group. */
    fun entrySigner(device: TestDeviceKeys): EntrySigner = EntrySigner(crypto, groupId, device.signPrivate)

    /** A new device, not yet in the roster. */
    fun newKeys(random: Random): TestDeviceKeys {
        val signPrivate = random.nextBytes(32)
        val noise = random.nextBytes(32)
        val authorityPrivate = random.nextBytes(32)
        return TestDeviceKeys(
            id = random.nextUuid(),
            signPrivate = signPrivate,
            signPublic = P2pEncoding.base64Url(crypto.fakeEd25519PublicKey(signPrivate)),
            noisePublic = P2pEncoding.base64Url(noise),
            noisePublicBytes = noise,
            authorityPrivate = authorityPrivate,
            authorityPublic = P2pEncoding.base64Url(crypto.fakeEd25519PublicKey(authorityPrivate)),
        )
    }

    /** The self-signed record of [device], not yet admitted. */
    fun record(device: TestDeviceKeys, by: TestDeviceKeys, at: Hlc): RosterDevice =
        signer.selfSign(unsigned(device, by, at), device.signPrivate)

    /** The record of [device], neither signed nor admitted. */
    fun unsigned(device: TestDeviceKeys, by: TestDeviceKeys, at: Hlc): RosterDevice = RosterDevice(
        id = device.id,
        label = "Device ${device.id.take(4)}",
        platform = "test",
        noisePub = device.noisePublic,
        keys = DeviceKeys.of(mlKem1024 = fakeKemPublicKey(device.noisePublicBytes)),
        signPub = device.signPublic,
        authorityPub = device.authorityPublic,
        addedAt = at,
        addedBy = by.id,
    )

    /** The record of [device], self-signed and admitted by the authority key of [by]. */
    fun admit(device: TestDeviceKeys, by: TestDeviceKeys, at: Hlc): RosterDevice =
        signer.admit(record(device, by, at), by.authorityPrivate)

    /**
     * Returns [roster] with [device] revoked by [by] with its authority key, sealing the
     * devices [roster] says it admitted.
     */
    fun revoke(roster: Roster, device: TestDeviceKeys, by: TestDeviceKeys, at: Hlc): Roster {
        val revocation = if (device.id == by.id) {
            signer.leave(roster, device.id, at, by.authorityPrivate)
        } else {
            signer.revoke(roster, device.id, at, by.id, by.authorityPrivate)
        }
        return roster.copy(revoked = roster.revoked + revocation)
    }

    private fun addedAt(index: Int): Hlc = Hlc(ADDED_AT + index, 0, founder.id)

    private companion object {
        const val ADDED_AT = 1_000L
    }
}

/** The size of an ML-KEM-1024 public key. */
private const val KEM_PUBLIC_KEY_SIZE = 1568

/**
 * A stand-in ML-KEM-1024 public key of the size of a real one, so records weigh what they do
 * in the app; derived from [noise], so it takes nothing from the random source of a test.
 */
fun fakeKemPublicKey(noise: ByteArray): String =
    P2pEncoding.base64Url(ByteArray(KEM_PUBLIC_KEY_SIZE) { noise[it % noise.size] })
