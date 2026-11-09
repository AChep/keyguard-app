package com.artemchep.keyguard.provider.keyguard.crypto

import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.feature.p2psync.roster.derivedGroupId
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.provider.keyguard.session.openTestAuthority
import com.artemchep.keyguard.provider.keyguard.session.newTestDevice
import com.artemchep.keyguard.provider.keyguard.founderRecord
import com.artemchep.keyguard.provider.keyguard.toRosterDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeP2pCryptoTest {
    private val crypto = NativeP2pCrypto(NativeCryptoGenerator())

    @Test
    fun `digests match the published test vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            P2pEncoding.hex(crypto.sha256("abc".encodeToByteArray())),
        )
        // RFC 5869, test case 1.
        val okm = crypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            P2pEncoding.hex(okm),
        )
    }

    @Test
    fun `signatures verify only for the signed data and key`() {
        val keyPair = NativeCryptoPrimitives.ed25519Generate()
        val data = "roster".encodeToByteArray()
        val signature = crypto.ed25519Sign(keyPair.privateKey, data)

        assertTrue(crypto.ed25519Verify(keyPair.publicKey, data, signature))
        assertFalse(crypto.ed25519Verify(keyPair.publicKey, "other".encodeToByteArray(), signature))
        assertFalse(crypto.ed25519Verify(ByteArray(31), data, signature))
        assertFalse(crypto.ed25519Verify(keyPair.publicKey, data, signature.copyOf(63)))
    }

    @Test
    fun `random bytes have the requested size`() {
        assertEquals(32, crypto.randomBytes(32).size)
    }

    @Test
    fun `a signed roster validates and admits its devices`() {
        val founder = device("00000000-0000-4000-8000-0000000000a1")
        val joiner = device("00000000-0000-4000-8000-0000000000a2")
        val authority = openTestAuthority(founder)
        val founderRecord = founderRecord(
            crypto = crypto,
            device = founder,
            addedAt = Hlc(wall = 1L, counter = 0, node = founder.deviceId),
            authority = authority,
        )
        val groupId = founderRecord.derivedGroupId(crypto::sha256)
        val signer = RosterSigner(crypto, groupId)
        val joinerRecord = signer.admit(
            device = signer.selfSign(
                device = joiner.toRosterDevice(
                    addedAt = Hlc(wall = 2L, counter = 0, node = founder.deviceId),
                    addedBy = founder.deviceId,
                ),
                signPrivateKey = P2pEncoding.base64UrlOrNull(joiner.signPrivateKeyBase64)!!,
            ),
            inviterAuthorityPrivateKey = authority.seed,
        )
        authority.close()
        val roster = Roster(
            founder = founder.deviceId,
            devices = listOf(founderRecord, joinerRecord),
        )

        val validator = RosterValidator(crypto, groupId)
        val validated = validator.validate(roster)
        assertEquals(roster, validated)
        assertEquals(
            setOf(founder.deviceId, joiner.deviceId),
            RosterActivity(validated, crypto::sha256).activeIds,
        )

        // A record changed after signing is dropped, and so is everyone it admitted.
        val forged = roster.copy(devices = listOf(founderRecord.copy(label = "Forged"), joinerRecord))
        assertTrue(validator.validate(forged).devices.isEmpty())
        // The signatures cover the group: in another one, nothing verifies.
        assertTrue(RosterValidator(crypto, "00000000-0000-4000-8000-000000000000").validate(roster).devices.isEmpty())
    }

    private fun device(id: String): P2pDevice {
        val keyPair = NativeCryptoPrimitives.ed25519Generate()
        return newTestDevice("Device $id").copy(
            deviceId = id,
            label = "Device $id",
            platform = "desktop",
            noisePublicKeyBase64 = P2pEncoding.base64Url(ByteArray(32) { 1 }),
            noisePrivateKeyBase64 = P2pEncoding.base64Url(ByteArray(32) { 2 }),
            signPublicKeyBase64 = P2pEncoding.base64Url(keyPair.publicKey),
            signPrivateKeyBase64 = P2pEncoding.base64Url(keyPair.privateKey),
        )
    }
}
