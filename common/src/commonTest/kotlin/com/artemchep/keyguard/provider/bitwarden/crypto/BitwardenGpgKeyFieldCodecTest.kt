package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitwardenGpgKeyFieldCodecTest {
    @Test
    fun `short and boundary content keep the legacy field format`() {
        listOf(
            "short key",
            "a".repeat(GPG_CHUNK_BYTES),
        ).forEach { privateKey ->
            val fields = encode(
                BitwardenCipher.GpgKey(privateKeyArmored = privateKey),
            )

            assertEquals(
                listOf(
                    gpgField(
                        name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                        value = privateKey,
                        type = BitwardenCipher.Field.Type.Hidden,
                    ),
                ),
                fields,
            )
        }
    }

    @Test
    fun `oversized private and public content use independently hashed chunks`() {
        val privateKey = "a".repeat(GPG_CHUNK_BYTES + 1)
        val publicKey = "b".repeat(GPG_CHUNK_BYTES * 2 + 1)
        val gpgKey = BitwardenCipher.GpgKey(
            privateKeyArmored = privateKey,
            publicKeyArmored = publicKey,
            fingerprint = GPG_FINGERPRINT,
        )

        val fields = encode(gpgKey)

        assertEquals(
            listOf(
                privatePartName(1),
                privatePartName(2),
                privateHashName(),
                publicPartName(1),
                publicPartName(2),
                publicPartName(3),
                publicHashName(),
                GpgAgentFields.FINGERPRINT,
            ),
            fields.map(BitwardenCipher.Field::name),
        )
        assertEquals(
            listOf(GPG_CHUNK_BYTES, 1),
            fields
                .filter { it.name?.startsWith(privatePartPrefix()) == true }
                .map { it.value!!.encodeToByteArray().size },
        )
        assertEquals(
            "eace2cdf4bfa0998191ad9cc50deb82a7af21d7f39129a65373996d2a1190162",
            fields.single { it.name == privateHashName() }.value,
        )
        assertTrue(
            fields
                .filter { it.name?.startsWith(privatePrefix()) == true }
                .all { it.type == BitwardenCipher.Field.Type.Hidden },
        )
        assertTrue(
            fields
                .filter { it.name?.startsWith(publicPrefix()) == true }
                .all { it.type == BitwardenCipher.Field.Type.Text },
        )

        val decoded = assertNotNull(decode(fields))
        assertEquals(gpgKey, decoded.gpgKey)
        assertTrue(decoded.remainingFields.isEmpty())
    }

    @Test
    fun `utf8 chunks preserve scalar boundaries and fit the byte budget`() {
        val privateKey = "🔐".repeat(1_000)

        val fields = encode(
            BitwardenCipher.GpgKey(privateKeyArmored = privateKey),
        )
        val parts = fields
            .filter { it.name?.startsWith(privatePartPrefix()) == true }
            .map { it.value!! }

        assertEquals(privateKey, parts.joinToString(separator = ""))
        assertTrue(parts.all { it.encodeToByteArray().size <= GPG_CHUNK_BYTES })
        assertEquals(privateKey, decode(fields)?.gpgKey?.privateKeyArmored)
    }

    @Test
    fun `private and public content select their wire formats independently`() {
        val publicOnlyKey = BitwardenCipher.GpgKey(
            publicKeyArmored = "p".repeat(GPG_CHUNK_BYTES + 1),
            fingerprint = GPG_FINGERPRINT,
        )
        val publicOnlyFields = encode(publicOnlyKey)

        assertTrue(
            publicOnlyFields.none { field ->
                field.name?.startsWith(privatePrefix()) == true
            },
        )
        assertEquals(publicOnlyKey, decode(publicOnlyFields)?.gpgKey)

        val mixedKey = BitwardenCipher.GpgKey(
            privateKeyArmored = "s".repeat(GPG_CHUNK_BYTES + 1),
            publicKeyArmored = "short public key",
        )
        val mixedFields = encode(mixedKey)

        assertTrue(mixedFields.any { it.name == privatePartName(1) })
        assertTrue(
            mixedFields.any { field ->
                field.name == GpgAgentFields.PUBLIC_KEY_ARMORED &&
                        field.value == mixedKey.publicKeyArmored
            },
        )
        assertEquals(mixedKey, decode(mixedFields)?.gpgKey)
    }

    @Test
    fun `chunk order comes from indexes and remaining fields preserve duplicates`() {
        val privateKey = "0123456789".repeat(800)
        val encodedFields = encode(
            BitwardenCipher.GpgKey(privateKeyArmored = privateKey),
        )
        val userField = gpgField(
            name = "User field",
            value = "keep me",
            type = BitwardenCipher.Field.Type.Text,
        )
        val fields = listOf(userField) + encodedFields.reversed() + userField

        val decoded = assertNotNull(decode(fields))

        assertEquals(privateKey, decoded.gpgKey.privateKeyArmored)
        assertEquals(listOf(userField, userField), decoded.remainingFields)
        assertTrue(decoded.remainingFields.all { field -> field === userField })
    }

    @Test
    fun `invalid chunk groups reject the whole gpg aggregate`() {
        val privateKey = "a".repeat(GPG_CHUNK_BYTES + 1)
        val fields = encode(
            BitwardenCipher.GpgKey(
                privateKeyArmored = privateKey,
                publicKeyArmored = "public key",
                fingerprint = GPG_FINGERPRINT,
            ),
        )
        val firstPart = fields.single { it.name == privatePartName(1) }
        val secondPart = fields.single { it.name == privatePartName(2) }
        val hash = fields.single { it.name == privateHashName() }
        val invalidGroups = listOf(
            fields - secondPart,
            fields - hash,
            fields + firstPart,
            fields + hash,
            fields.map { if (it === secondPart) it.copy(name = privatePartName(3)) else it },
            fields.map { if (it === secondPart) it.copy(name = privatePartPrefix() + "02") else it },
            fields.map { if (it === secondPart) it.copy(name = privatePrefix() + "unknown") else it },
            fields.map { if (it === secondPart) it.copy(value = "") else it },
            fields
                .filterNot { it === secondPart }
                .map { if (it === firstPart) it.copy(value = privateKey) else it },
            fields.map {
                if (it === firstPart) it.copy(type = BitwardenCipher.Field.Type.Text) else it
            },
            fields.map {
                if (it === firstPart) {
                    it.copy(linkedId = BitwardenCipher.Field.LinkedId.Login_Username)
                } else {
                    it
                }
            },
            fields.map { if (it === firstPart) it.copy(value = "changed") else it },
            fields.map { if (it === hash) it.copy(value = "0".repeat(64)) else it },
            fields.map { if (it === hash) it.copy(value = hash.value!!.uppercase()) else it },
        )

        invalidGroups.forEach { invalidFields ->
            assertNull(decode(invalidFields))
        }
    }

    @Test
    fun `matching legacy and chunked values coexist but conflicts reject decoding`() {
        val privateKey = "a".repeat(GPG_CHUNK_BYTES + 1)
        val fields = encode(
            BitwardenCipher.GpgKey(privateKeyArmored = privateKey),
        )
        val matchingLegacy = gpgField(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = privateKey,
            type = BitwardenCipher.Field.Type.Hidden,
        )

        val matching = assertNotNull(decode(fields + matchingLegacy))
        assertEquals(privateKey, matching.gpgKey.privateKeyArmored)
        assertTrue(matching.remainingFields.isEmpty())

        listOf("different", null).forEach { conflictingLegacyValue ->
            val conflictingLegacy = matchingLegacy.copy(value = conflictingLegacyValue)
            assertNull(decode(fields + conflictingLegacy))
        }
    }

    @Test
    fun `encode removes stale representations and preserves unrelated fields`() {
        val userField = gpgField(
            name = "User field",
            value = "keep me",
            type = BitwardenCipher.Field.Type.Text,
        )
        val staleFields = listOf(
            gpgField(
                name = privatePartName(9),
                value = "stale",
                type = BitwardenCipher.Field.Type.Hidden,
            ),
            gpgField(
                name = privateHashName(),
                value = "0".repeat(64),
                type = BitwardenCipher.Field.Type.Hidden,
            ),
            userField,
        )

        val shrunkFields = encode(
            gpgKey = BitwardenCipher.GpgKey(privateKeyArmored = "short key"),
            fields = staleFields,
        )
        assertEquals(
            listOf(GpgAgentFields.PRIVATE_KEY_ARMORED, "User field"),
            shrunkFields.map(BitwardenCipher.Field::name),
        )

        val deletedPrivateFields = encode(
            gpgKey = BitwardenCipher.GpgKey(publicKeyArmored = "public key"),
            fields = shrunkFields,
        )
        assertEquals(
            listOf(GpgAgentFields.PUBLIC_KEY_ARMORED, "User field"),
            deletedPrivateFields.map(BitwardenCipher.Field::name),
        )
        assertEquals(
            staleFields,
            encode(
                gpgKey = BitwardenCipher.GpgKey(),
                fields = staleFields,
            ),
        )
    }

    @Test
    fun `decode keeps partial legacy material atomic`() {
        val fields = listOf(
            gpgField(
                name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                value = "wire public key",
                type = BitwardenCipher.Field.Type.Hidden,
            ),
            gpgField(
                name = GpgAgentFields.FINGERPRINT,
                value = GPG_FINGERPRINT,
                type = BitwardenCipher.Field.Type.Hidden,
            ),
        )

        val decoded = assertNotNull(decode(fields))

        assertEquals(
            BitwardenCipher.GpgKey(
                publicKeyArmored = "wire public key",
                fingerprint = GPG_FINGERPRINT,
            ),
            decoded.gpgKey,
        )
        assertTrue(decoded.remainingFields.isEmpty())
    }

    private fun encode(
        gpgKey: BitwardenCipher.GpgKey,
        fields: List<BitwardenCipher.Field> = emptyList(),
    ): List<BitwardenCipher.Field> = BitwardenGpgKeyFieldCodec.encode(
        gpgKey = gpgKey,
        fields = fields,
    )

    private fun decode(
        fields: List<BitwardenCipher.Field>,
    ): BitwardenGpgKeyFieldCodec.Decoded? = BitwardenGpgKeyFieldCodec.decode(
        fields = fields,
    )
}

private fun privatePrefix() = gpgChunkPrefix(GpgAgentFields.PRIVATE_KEY_ARMORED)

private fun publicPrefix() = gpgChunkPrefix(GpgAgentFields.PUBLIC_KEY_ARMORED)

private fun privatePartPrefix() = gpgChunkPartPrefix(GpgAgentFields.PRIVATE_KEY_ARMORED)

private fun privatePartName(index: Int) = gpgChunkPartName(GpgAgentFields.PRIVATE_KEY_ARMORED, index)

private fun publicPartName(index: Int) = gpgChunkPartName(GpgAgentFields.PUBLIC_KEY_ARMORED, index)

private fun privateHashName() = gpgChunkHashName(GpgAgentFields.PRIVATE_KEY_ARMORED)

private fun publicHashName() = gpgChunkHashName(GpgAgentFields.PUBLIC_KEY_ARMORED)
