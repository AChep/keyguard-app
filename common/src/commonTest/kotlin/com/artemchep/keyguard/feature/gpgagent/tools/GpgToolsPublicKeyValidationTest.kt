package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParserUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_public_key_not_encryptable
import com.artemchep.keyguard.res.gpg_tools_public_key_private
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class GpgToolsPublicKeyValidationTest {
    @Test
    fun `normalizes metadata and deduplicates multiple public certificates`() {
        val first = key("AA BB", "first")
        val duplicate = first.copy(fingerprint = "aabb")
        val second = key("CCDD", "second")
        val result = validate(
            listOf(first, duplicate, second).joinToString("\n") { it.publicKeyArmored },
            listOf(first, duplicate, second),
        )
        assertEquals(
            listOf("AABB", "CCDD"),
            assertSuccess(result).keys.map { it.fingerprint },
        )
    }

    @Test
    fun `supports several certificates in one armor block`() {
        val first = key("AA", "first")
        val second = key("BB", "second")
        val result = validate(armor("firstsecond"), listOf(first, second))
        assertEquals(2, assertSuccess(result).keys.size)
    }

    @Test
    fun `accepts armor headers line endings and surrounding whitespace`() {
        val key = key()
        val text = "  \n" + key.publicKeyArmored
            .replace("BLOCK-----\n\n", "BLOCK-----\nVersion: GnuPG\nComment: Public export\n\n")
            .replace("\n", "\r\n") + "\n "
        assertSuccess(validate(text, listOf(key)))
    }

    @Test
    fun `rejects private labels before calling parser`() {
        val parser = object : GpgPublicKeyParser {
            override fun parse(armored: String): GpgPublicKeyParseResult = error("Must not parse private input")
        }
        for (kind in listOf("PRIVATE", "SECRET")) {
            val result = validateGpgToolsPublicKeys("-----BEGIN PGP $kind KEY BLOCK-----", parser, false)
            assertEquals(
                Res.string.gpg_tools_public_key_private,
                assertError(result).resource,
            )
        }
    }

    @Test
    fun `rejects secret projection even with a public armor label`() {
        assertError(validate(armor("secret packets"), listOf(key("AA", "public projection"))))
    }

    @Test
    fun `rejects omitted packet bytes and surrounding material`() {
        val key = key()
        for (input in listOf(armor("publicextra"), armor("extrapublic"), "unparsed\n${key.publicKeyArmored}")) {
            assertError(validate(input, listOf(key)))
        }
    }

    @Test
    fun `rejects empty malformed unsupported and partly skipped results`() {
        val key = key()
        for (input in listOf(
            "",
            "  ",
            "not armor",
            "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n!\n-----END PGP PUBLIC KEY BLOCK-----",
        )) {
            assertError(validate(input, listOf(key)))
        }
        assertError(validate(key.publicKeyArmored, emptyList()))
        assertError(validateGpgToolsPublicKeys(key.publicKeyArmored, GpgPublicKeyParserUnsupported, false))
        val skipped = GpgPublicKeyParseResult.Success(listOf(key), skippedCertificates = 1)
        assertError(validateGpgToolsPublicKeys(key.publicKeyArmored, parser(skipped), false))
        for (reason in GpgPublicKeyParseError.entries) {
            val error = GpgPublicKeyParseResult.Error(reason)
            assertError(validateGpgToolsPublicKeys(key.publicKeyArmored, parser(error), false))
        }
    }

    @Test
    fun `historical keys retain statuses but cannot be new encryption recipients`() {
        val old = Instant.parse("2000-01-01T00:00:00Z")
        for (key in listOf(
            key().copy(revoked = true),
            key().copy(expiresAt = old),
            key().copy(authenticated = false),
            key().copy(canEncrypt = false),
        )) {
            assertEquals(
                listOf(key),
                assertSuccess(validate(key.publicKeyArmored, listOf(key))).keys,
            )
            val encryption = validate(key.publicKeyArmored, listOf(key), forEncryption = true)
            assertEquals(
                Res.string.gpg_tools_public_key_not_encryptable,
                assertError(encryption).resource,
            )
        }
    }

    @Test
    fun `encryption requires authenticated active primary and encryption component`() {
        val subkey = GpgPublicSubKeyInfo(
            "BB",
            keyId = "BB",
            algorithm = "RSA",
            canSign = false,
            canEncrypt = true,
            revoked = false,
            expiresAt = null,
        )
        val key = key().copy(canEncrypt = false, subKeys = listOf(subkey))
        assertSuccess(validate(key.publicKeyArmored, listOf(key), true))
        for (unusable in listOf(
            subkey.copy(authenticated = false),
            subkey.copy(revoked = true),
            subkey.copy(expiresAt = Instant.parse("2000-01-01T00:00:00Z")),
        )) {
            assertError(validate(key.publicKeyArmored, listOf(key.copy(subKeys = listOf(unusable))), true))
        }
        assertError(validate(key.publicKeyArmored, listOf(key.copy(authenticated = false)), true))
        assertError(validate(key.publicKeyArmored, listOf(key.copy(revoked = true)), true))
    }

    private fun assertSuccess(result: GpgToolsPublicKeyValidationResult) =
        assertIs<GpgToolsPublicKeyValidationResult.Success>(result)

    private fun assertError(result: GpgToolsPublicKeyValidationResult) =
        assertIs<GpgToolsPublicKeyValidationResult.Error>(result)

    private fun validate(text: String, keys: List<GpgPublicKeyInfo>, forEncryption: Boolean = false) =
        validateGpgToolsPublicKeys(text, parser(GpgPublicKeyParseResult.Success(keys)), forEncryption)

    private fun parser(result: GpgPublicKeyParseResult) = object : GpgPublicKeyParser {
        override fun parse(armored: String) = result
    }

    private fun key(fingerprint: String = "AA", packets: String = "public") = GpgPublicKeyInfo(
        fingerprint = fingerprint,
        keyId = fingerprint,
        algorithm = "RSA",
        bitStrength = 3072,
        userIds = listOf("Alice"),
        emails = emptyList(),
        createdAt = null,
        expiresAt = null,
        revoked = false,
        canSign = true,
        canEncrypt = true,
        publicKeyArmored = armor(packets),
        subKeys = emptyList(),
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun armor(packets: String) =
        "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n${Base64.Default.encode(
            packets.encodeToByteArray(),
        )}\n-----END PGP PUBLIC KEY BLOCK-----"
}
