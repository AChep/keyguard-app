package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_SSH_FINGERPRINT_LABEL
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_SSH_PRIVATE_KEY_LABEL
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_SSH_PUBLIC_KEY_LABEL
import com.artemchep.keyguard.common.service.sshagent.isEligibleForSshAgent
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.test.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * One SSH key item as a test asks a format to export it. A `null` member is
 * left out of the export.
 */
internal data class SshExport(
    val privateKey: String?,
    val passphrase: String? = null,
    val publicKey: String? = null,
    val fingerprint: String? = null,
)

/**
 * How one import format writes an SSH key item, for [SshImportContractTest].
 */
internal interface SshImportFormat {
    val format: ImportFormatId

    /** The title of the exported item, which names the item in skips. */
    val title: String

    val carriesPassphrase: Boolean
    val carriesPublicKey: Boolean
    val carriesFingerprint: Boolean

    /** The labels a key the vault cannot convert is kept under. */
    val privateKeyLabel: String get() = IMPORT_SSH_PRIVATE_KEY_LABEL
    val publicKeyLabel: String get() = IMPORT_SSH_PUBLIC_KEY_LABEL
    val fingerprintLabel: String get() = IMPORT_SSH_FINGERPRINT_LABEL

    fun export(key: SshExport): ByteArray

    /** Checks the item's other data, which survives whether or not its key converts. */
    fun assertMetadata(plan: ImportPlan, item: ImportPlan.Item, key: SshExport)
}

/**
 * What every importer that reads SSH key items guarantees:
 * - a key that converts becomes a usable SSH key item, whose public key and
 *   fingerprint come from the private key;
 * - a source public key or fingerprint that disagrees with them is kept as a
 *   field, a matching one is dropped;
 * - a key that does not convert, or a missing one, is kept in a note
 *   unchanged, and counted as one skip.
 */
abstract class SshImportContractTest internal constructor(
    private val source: SshImportFormat,
) {
    @Test
    fun `unencrypted keys become usable SSH keys`() = runTest {
        val fixtures = mapOf(
            "openssh/id_ed25519" to "ssh-key-ed25519",
            "openssh/id_rsa_3072" to "ssh-key-rsa-3072",
            "pem/pkcs1-rsa-none.pem" to "pem-pkcs1-rsa-1024",
            "pem/pkcs8-rsa-none.pem" to "pem-pkcs8-rsa-2048",
            "ppk/v2-ed25519-none.ppk" to "ppk-ed25519",
            "ppk/v3-rsa-none.ppk" to "ppk-rsa-1024",
        )
        for ((path, goldenId) in fixtures) {
            val key = SshExport(privateKey = sshCorpusText(path))
            assertConverted(parse(key), key, goldenId)
        }
    }

    @Test
    fun `a matching source public key is dropped`() = runTest {
        if (!source.carriesPublicKey) return@runTest
        val golden = sshCorpusGolden("ssh-key-ed25519")
        val key = SshExport(
            privateKey = sshCorpusText("openssh/id_ed25519"),
            // The comment is not part of the key.
            publicKey = golden[2].split(' ').take(2).joinToString(" ") + " exported@example.org",
            fingerprint = golden[3].takeIf { source.carriesFingerprint },
        )
        val request = assertConverted(parse(key), key, "ssh-key-ed25519")
        assertTrue(
            request.fields.none { it.name == source.publicKeyLabel || it.name == source.fingerprintLabel },
            request.fields.toString(),
        )
    }

    @Test
    fun `a disagreeing source public key is kept as a field`() = runTest {
        if (!source.carriesPublicKey) return@runTest
        val wrongPublicKey = sshCorpusGolden("ssh-key-rsa-3072")[2]
        val key = SshExport(
            privateKey = sshCorpusText("openssh/id_ed25519"),
            publicKey = wrongPublicKey,
            fingerprint = "SHA256:incorrect".takeIf { source.carriesFingerprint },
        )
        val request = assertConverted(parse(key), key, "ssh-key-ed25519")
        assertEquals(
            listOfNotNull(
                DSecret.Field(source.publicKeyLabel, wrongPublicKey, type = DSecret.Field.Type.Text),
                key.fingerprint?.let {
                    DSecret.Field(source.fingerprintLabel, it, type = DSecret.Field.Type.Text)
                },
            ),
            request.fields.filter { it.name == source.publicKeyLabel || it.name == source.fingerprintLabel },
        )
    }

    @Test
    fun `encrypted keys convert with their passphrase`() = runTest {
        if (!source.carriesPassphrase) return@runTest
        val fixtures = listOf(
            Triple("openssh/id_ed25519.aes256-ctr.enc", "hunter42", "ssh-key-ed25519"),
            Triple("pem/pkcs1-rsa-encrypted.pem", "passphrase", "pem-pkcs1-rsa-2048-encrypted"),
            Triple("pem/pkcs8-rsa-encrypted.pem", "passphrase", "pem-pkcs8-rsa-2048-encrypted"),
            Triple("ppk/v3-rsa-argon2id-aes256-cbc.ppk", "changeit", "ppk-rsa-2048"),
        )
        for ((path, passphrase, goldenId) in fixtures) {
            val key = SshExport(privateKey = sshCorpusText(path), passphrase = passphrase)
            assertConverted(parse(key), key, goldenId)
        }
    }

    @Test
    fun `keys that do not convert are kept in a note`() = runTest {
        val encrypted = sshCorpusText("openssh/id_ed25519.aes256-ctr.enc")
        val passphrases = if (source.carriesPassphrase) listOf(null, "incorrect", "hunter42 ") else listOf(null)
        val keys = passphrases.map { SshExport(privateKey = encrypted, passphrase = it) } + listOf(
            "openssh/id_ecdsa_p256",
            "openssh/id_dsa_1024",
            "pem/pkcs8-ecdsa-none.pem",
            "ppk/v3-ecdsa-none.ppk",
        ).map { SshExport(privateKey = sshCorpusText(it)) } + listOf(
            "  -----BEGIN PRIVATE KEY-----\nmalformed\n-----END PRIVATE KEY-----",
            "not a key, \"quoted\"\nsecond line",
            "x".repeat(65 * 1024),
        ).map { SshExport(privateKey = it) }
        for (key in keys) {
            val exported = key.copy(
                publicKey = "original public key".takeIf { source.carriesPublicKey },
                fingerprint = "original fingerprint".takeIf { source.carriesFingerprint },
            )
            assertKeptInNote(parse(exported), exported)
        }
    }

    @Test
    fun `a failing backend keeps the key in a note`() = runTest {
        val service = FakeSshKeyImportService(error = IllegalStateException("Unavailable backend"))
        val key = SshExport(privateKey = sshCorpusText("openssh/id_ed25519"))
        assertKeptInNote(parse(key, service), key)
        assertEquals(1, service.callCount)
    }

    @Test
    fun `an item without a private key is kept in a note`() = runTest {
        val service = FakeSshKeyImportService(error = IllegalStateException("Must not convert"))
        val key = SshExport(
            privateKey = null,
            publicKey = sshCorpusGolden("ssh-key-ed25519")[2].takeIf { source.carriesPublicKey },
        )
        assertKeptInNote(parse(key, service), key)
        assertEquals(0, service.callCount)
    }

    private suspend fun parse(
        key: SshExport,
        service: SshKeyImportService = NativeSshKeyImportService,
    ): ImportPlan {
        val result = importTestService(sshKeyImportService = service).parse(
            format = source.format,
            input = BytesImportInput(source.export(key)),
            now = IMPORT_TEST_NOW,
        )
        return assertIs<ImportResult.Success>(result).plan.also { plan ->
            plan.assertVaultStorable()
            val item = plan.items.single()
            assertEquals(source.title, item.request.title)
            source.assertMetadata(plan, item, key)
        }
    }

    private fun assertConverted(
        plan: ImportPlan,
        key: SshExport,
        goldenId: String,
    ): CreateRequest {
        val label = "${source.format}: ${key.privateKey?.lineSequence()?.firstOrNull()}"
        val request = plan.items.single().request
        val golden = sshCorpusGolden(goldenId)
        assertEquals(DSecret.Type.SshKey, request.type, label)
        assertEquals(golden[2], request.sshKey.publicKey, label)
        assertEquals(golden[3], request.sshKey.fingerprint, label)
        assertTrue(plan.skips.isEmpty, "$label: ${plan.skips}")
        assertTrue(request.fields.none { it.value == key.privateKey }, label)
        val secret = createSecret(
            id = "imported",
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = request.sshKey.privateKey,
                publicKey = request.sshKey.publicKey,
                fingerprint = request.sshKey.fingerprint,
            ),
        )
        assertTrue(secret.isEligibleForSshAgent(), label)
        // The stored key is decrypted and identifies the same key.
        val reimported = NativeSshKeyImportService.import(
            SshKeyImportRequest(content = assertNotNull(request.sshKey.privateKey)),
        )
        assertEquals(golden[2], assertIs<SshKeyImportResult.Success>(reimported).keyPair.publicKey.ssh, label)
        return request
    }

    private fun assertKeptInNote(
        plan: ImportPlan,
        key: SshExport,
    ) {
        val label = "${source.format}: ${key.privateKey?.take(40)}"
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SecureNote, request.type, label)
        assertEquals(CreateRequest.SshKey(), request.sshKey, label)
        val expected = listOfNotNull(
            key.privateKey?.let { DSecret.Field(source.privateKeyLabel, it, type = DSecret.Field.Type.Hidden) },
            key.publicKey?.let { DSecret.Field(source.publicKeyLabel, it, type = DSecret.Field.Type.Text) },
            key.fingerprint?.let { DSecret.Field(source.fingerprintLabel, it, type = DSecret.Field.Type.Text) },
        )
        val labels = setOf(source.privateKeyLabel, source.publicKeyLabel, source.fingerprintLabel)
        assertEquals(expected, request.fields.filter { it.name in labels }, label)
        assertEquals(importSkips(ImportSkipReason.SshKey to 1), plan.skips, label)
        assertEquals(mapOf(source.title to 1), plan.skips.titlesOf(ImportSkipReason.SshKey), label)
    }
}

/** A row of `public-key-goldens.tsv`: id, type, public key, fingerprint. */
internal fun sshCorpusGolden(id: String): List<String> = sshCorpusText("public-key-goldens.tsv")
    .lineSequence()
    .map { it.split('\t') }
    .first { it.first() == id }

internal fun sshCorpusText(path: String): String = checkNotNull(
    SshImportContractTest::class.java.classLoader.getResourceAsStream("ssh-import-corpus/$path"),
) { "Missing SSH corpus resource $path" }.use { it.readBytes().decodeToString() }
