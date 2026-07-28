package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SshImportCompatibilityCorpusTest {
    private val service = NativeSshKeyImportService

    @Test
    fun `frozen corpus preserves import results and exact public identity`() {
        val goldens = goldenRows().associateBy { it.required("id") }

        manifestRows().forEach { row ->
            assertTrue(
                row.required("coverage").startsWith("FROZEN_"),
                "${row.required("id")}: unchecked compatibility row",
            )
            val resource = row.required("resource")
            val content = resourceText(resource)
            val passphrase = row.optional("passphrase")
            val golden = row.optional("golden_id")?.let { id ->
                checkNotNull(goldens[id]) { "${row.required("id")}: unknown golden $id" }
            }

            assertExpected(
                caseId = row.required("id"),
                expectation = row.required("without_passphrase"),
                result = import(content, resource, passphrase = null),
                golden = golden,
            )

            if (passphrase != null) {
                assertExpected(
                    caseId = row.required("id"),
                    expectation = row.required("with_passphrase"),
                    result = import(content, resource, passphrase),
                    golden = golden,
                )
                assertExpected(
                    caseId = "${row.required("id")}: wrong-passphrase result",
                    expectation = row.required("wrong_passphrase"),
                    result = import(content, resource, passphrase = "definitely-wrong-passphrase"),
                    golden = null,
                )
            }
        }
    }

    @Test
    fun `manifest covers every SSHJ OpenSSH-v1 cipher`() {
        val actual = manifestRows()
            .filter { it.required("container") == "OPENSSH" }
            .map { it.required("encryption") }
            .filterNot { it == "none" }
            .toSet()

        assertEquals(
            setOf(
                "3des-cbc",
                "aes128-cbc",
                "aes192-cbc",
                "aes256-cbc",
                "aes128-ctr",
                "aes192-ctr",
                "aes256-ctr",
                "aes128-gcm@openssh.com",
                "aes256-gcm@openssh.com",
                "chacha20-poly1305@openssh.com",
            ),
            actual,
        )
    }

    @Test
    fun `manifest covers PPK versions algorithms and v3 Argon2 modes`() {
        val puttyRows = manifestRows().filter { it.required("container") == "PPK" }

        assertEquals(setOf("1", "2", "3"), puttyRows.map { it.required("version") }.toSet())
        assertTrue(puttyRows.any { it.isSuccessfulUnencrypted("RSA") })
        assertTrue(puttyRows.any { it.isSuccessfulUnencrypted("ED25519") })
        assertEquals(
            setOf("Argon2d-1.3", "Argon2i-1.3", "Argon2id-1.3"),
            puttyRows
                .filter { it.required("version") == "3" && it.required("encryption") != "none" }
                .map { it.required("kdf") }
                .toSet(),
        )
        assertTrue(
            puttyRows.any {
                it.required("algorithm") == "ECDSA" &&
                        it.required("with_passphrase") == "UNSUPPORTED_ALGORITHM"
            },
        )
    }

    @Test
    fun `legacy PEM matrix freezes every BC 1_84 DEK spelling`() {
        val rows = tsv("legacy-pem-dek-matrix.tsv")
        val algorithms = rows.map { it.required("dek_algorithm") }.toSet()
        val expected = buildSet {
            listOf(128, 192, 256).forEach { bits ->
                listOf("CBC", "CFB", "OFB", "ECB").forEach { mode -> add("AES-$bits-$mode") }
            }
            listOf("CBC", "CFB", "OFB", "ECB").forEach { mode -> add("DES-$mode") }
            listOf("DES-EDE", "DES-EDE3").forEach { family ->
                listOf("CBC", "CFB", "OFB").forEach { mode -> add("$family-$mode") }
                add(family)
                add("$family-ECB")
            }
            listOf("CBC", "CFB", "OFB", "ECB").forEach { mode -> add("BF-$mode") }
            listOf("RC2", "RC2-40", "RC2-64").forEach { family ->
                listOf("CBC", "CFB", "OFB", "ECB").forEach { mode -> add("$family-$mode") }
            }
        }

        assertEquals(expected, algorithms)
        assertEquals(42, rows.size)
        assertEquals(setOf("FROZEN"), rows.map { it.required("coverage") }.toSet())
        assertEquals(setOf("SUCCESS"), rows.map { it.required("outcome") }.toSet())
        rows.forEach { row -> resourceBytes(row.required("resource")) }
    }

    @Test
    fun `PKCS8 PBE matrix freezes the exact SSHJ JDK21 boundary`() {
        val rows = tsv("pkcs8-pbe-matrix.tsv")
        val accepted = rows.filter { it.required("coverage") == "FROZEN_ACCEPTED" }
        val rejected = rows.filter { it.required("coverage") == "FROZEN_REJECTED" }

        assertEquals(14, accepted.size)
        assertEquals(
            setOf(
                "HmacSHA1",
                "HmacSHA224",
                "HmacSHA256",
                "HmacSHA384",
                "HmacSHA512",
                "HmacSHA512/224",
                "HmacSHA512/256",
            ),
            accepted.map { it.required("prf") }.toSet(),
        )
        assertEquals(setOf("AES-128-CBC", "AES-256-CBC"), accepted.map { it.required("cipher") }.toSet())
        assertEquals(setOf("SUCCESS"), accepted.map { it.required("outcome") }.toSet())
        accepted.forEach { row -> resourceBytes(row.required("resource")) }

        assertEquals(7, rejected.size)
        assertEquals(setOf("MALFORMED_KEY"), rejected.map { it.required("outcome") }.toSet())
        assertEquals(setOf("-"), rejected.map { it.required("resource") }.toSet())
    }

    @Test
    fun `frozen fixture checksums prevent accidental corpus drift`() {
        val expectedResources = manifestRows().map { it.required("resource") }.toSet() + setOf(
            "openssh/id_ed25519.pub",
            "openssh/id_rsa_3072.pub",
        )
        val checksums = resourceText("checksums.sha256")
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val separator = line.indexOf("  ")
                require(separator > 0) { "Malformed checksum line: $line" }
                line.substring(separator + 2) to line.substring(0, separator)
            }

        assertEquals(expectedResources, checksums.keys)
        checksums.forEach { (resource, expected) ->
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(resourceBytes(resource))
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            assertEquals(expected, actual, resource)
        }
    }

    private fun assertExpected(
        caseId: String,
        expectation: String,
        result: SshKeyImportResult,
        golden: Map<String, String>?,
    ) {
        when {
            expectation == "SUCCESS" -> {
                val expected = checkNotNull(golden) { "$caseId: success requires a golden" }
                val success = assertIs<SshKeyImportResult.Success>(result, caseId)
                assertEquals(KeyPair.Type.valueOf(expected.required("type")), success.keyPair.type, caseId)
                assertEquals(expected.required("public_key"), success.keyPair.publicKey.ssh, caseId)
                assertEquals(expected.required("fingerprint"), success.keyPair.publicKey.fingerprint, caseId)
                assertTrue(
                    success.keyPair.privateKey.ssh.startsWith(
                        "-----BEGIN ${expected.required("private_pem_label")}-----",
                    ),
                    "$caseId: private PEM label",
                )
                when (success.keyPair.type) {
                    KeyPair.Type.RSA -> assertEquals(
                        expected.required("private_der_sha256"),
                        success.keyPair.privateKey.encoded.sha256Hex(),
                        "$caseId: normalized PKCS#8 DER",
                    )

                    KeyPair.Type.ED25519 -> assertEd25519OpenSshPrivateKey(
                        caseId = caseId,
                        privateKeyPem = success.keyPair.privateKey.ssh,
                        publicKeyBlob = success.keyPair.publicKey.encoded,
                        expectedSeedHex = expected.required("ed25519_seed_hex"),
                        expectedComment = expected.required("ed25519_comment")
                            .takeUnless { it == "<empty>" }
                            .orEmpty(),
                    )
                }
            }

            expectation == "UNSUPPORTED_ALGORITHM" -> assertEquals(
                SshKeyImportResult.Error(SshKeyImportError.UnsupportedAlgorithm),
                result,
                caseId,
            )

            expectation == "INVALID_PASSPHRASE" -> assertEquals(
                SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase),
                result,
                caseId,
            )

            expectation == "MALFORMED_KEY" -> assertEquals(
                SshKeyImportResult.Error(SshKeyImportError.MalformedKey),
                result,
                caseId,
            )

            expectation.startsWith("NEEDS_PASSPHRASE:") -> assertEquals(
                SshKeyImportResult.NeedsPassphrase(expectation.substringAfter(':')),
                result,
                caseId,
            )

            else -> error("$caseId: unknown expectation $expectation")
        }
    }

    private fun import(
        content: String,
        resource: String,
        passphrase: String?,
    ): SshKeyImportResult = service.import(
        SshKeyImportRequest(
            content = content,
            fileName = resource.substringAfterLast('/'),
            passphrase = passphrase,
        ),
    )

    private fun Map<String, String>.isSuccessfulUnencrypted(algorithm: String): Boolean =
        required("algorithm") == algorithm &&
                required("encryption") == "none" &&
                required("with_passphrase") == "SUCCESS"

    private fun manifestRows(): List<Map<String, String>> = tsv("manifest.tsv")

    private fun goldenRows(): List<Map<String, String>> = tsv("public-key-goldens.tsv")

    private fun tsv(resource: String): List<Map<String, String>> {
        val lines = resourceText(resource)
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .toList()
        val header = lines.first().split('\t')
        return lines.drop(1).mapIndexed { index, line ->
            val values = line.split('\t')
            require(values.size == header.size) {
                "$resource:${index + 2}: expected ${header.size} columns, got ${values.size}"
            }
            header.zip(values).toMap()
        }
    }

    private fun Map<String, String>.required(name: String): String =
        checkNotNull(this[name]) { "Missing column $name" }

    private fun Map<String, String>.optional(name: String): String? =
        required(name).takeUnless { it == "-" }

    private fun resourceText(path: String): String = resourceBytes(path).decodeToString()

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHex()

    private fun assertEd25519OpenSshPrivateKey(
        caseId: String,
        privateKeyPem: String,
        publicKeyBlob: ByteArray,
        expectedSeedHex: String,
        expectedComment: String,
    ) {
        val encoded = Base64.getMimeDecoder().decode(
            privateKeyPem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString(separator = ""),
        )
        val magic = "openssh-key-v1\u0000".encodeToByteArray()
        assertContentEquals(magic, encoded.copyOfRange(0, magic.size), "$caseId: OpenSSH magic")
        val envelope = SshCursor(encoded, magic.size)
        assertEquals("none", envelope.readString().decodeToString(), "$caseId: cipher")
        assertEquals("none", envelope.readString().decodeToString(), "$caseId: KDF")
        assertContentEquals(byteArrayOf(), envelope.readString(), "$caseId: KDF options")
        assertEquals(1L, envelope.readUInt32(), "$caseId: public-key count")
        val outerPublic = envelope.readString()
        val privateBlock = envelope.readString()
        assertTrue(envelope.isExhausted(), "$caseId: trailing envelope bytes")
        assertContentEquals(publicKeyBlob, outerPublic, "$caseId: outer public blob")

        val private = SshCursor(privateBlock)
        val firstCheckint = private.readUInt32()
        assertEquals(firstCheckint, private.readUInt32(), "$caseId: duplicated checkint")
        assertEquals("ssh-ed25519", private.readString().decodeToString(), "$caseId: key type")
        val public = private.readString()
        val seedAndPublic = private.readString()
        assertEquals(32, public.size, "$caseId: public-key length")
        assertEquals(64, seedAndPublic.size, "$caseId: private-key length")
        assertContentEquals(public, seedAndPublic.copyOfRange(32, 64), "$caseId: embedded public key")
        assertEquals(expectedSeedHex, seedAndPublic.copyOfRange(0, 32).toHex(), "$caseId: seed")
        assertEquals(expectedComment, private.readString().decodeToString(), "$caseId: comment")

        val publicCursor = SshCursor(outerPublic)
        assertEquals("ssh-ed25519", publicCursor.readString().decodeToString(), "$caseId: public type")
        assertContentEquals(public, publicCursor.readString(), "$caseId: public blob key")
        assertTrue(publicCursor.isExhausted(), "$caseId: trailing public bytes")

        val padding = private.readRemaining()
        assertTrue(padding.isNotEmpty(), "$caseId: missing deterministic padding")
        padding.forEachIndexed { index, byte ->
            assertEquals(index + 1, byte.toInt() and 0xff, "$caseId: padding byte $index")
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun resourceBytes(path: String): ByteArray = checkNotNull(
        javaClass.classLoader.getResourceAsStream("ssh-import-corpus/$path"),
    ) { "Missing SSH import corpus resource: $path" }.use { it.readBytes() }

    private class SshCursor(
        private val bytes: ByteArray,
        private var offset: Int = 0,
    ) {
        fun readUInt32(): Long {
            require(bytes.size - offset >= 4) { "Truncated SSH uint32" }
            var value = 0L
            repeat(4) {
                value = (value shl 8) or (bytes[offset++].toLong() and 0xff)
            }
            return value
        }

        fun readString(): ByteArray {
            val length = readUInt32()
            require(length <= Int.MAX_VALUE && length <= bytes.size - offset) {
                "Invalid SSH string length: $length"
            }
            return bytes.copyOfRange(offset, offset + length.toInt()).also {
                offset += length.toInt()
            }
        }

        fun readRemaining(): ByteArray = bytes.copyOfRange(offset, bytes.size).also {
            offset = bytes.size
        }

        fun isExhausted(): Boolean = offset == bytes.size
    }
}
