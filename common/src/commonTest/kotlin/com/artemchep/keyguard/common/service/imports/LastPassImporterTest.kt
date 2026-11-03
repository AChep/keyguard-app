package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.test.fakeSshKeyPair
import com.artemchep.keyguard.test.sshKeyImportNeedsPassphrase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class LastPassImporterTest {
    @Test
    fun `structured notes preserve comma-only and whitespace-only secret fields`() = runTest {
        for (secret in listOf(",,,", "   ", "\t", " , , ", "  secret  ", "")) {
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv("NoteType:Server\nPassword:$secret\nPIN:$secret\nCustom:,,,\nNotes:Keep"),
            )
            plan.assertVaultStorable()
            assertEquals(importSkips(), plan.skips)
            val request = plan.items.single().request
            for (name in listOf("Password", "PIN")) {
                val field = request.fields.find { it.name == name }
                assertEquals(secret.takeIf { it.isNotEmpty() }, field?.value, name)
                if (secret.isNotEmpty()) {
                    assertEquals(DSecret.Field.Type.Hidden, field?.type, name)
                }
            }
            assertEquals(",,,", request.fields.single { it.name == "Custom" }.value)
            assertEquals("Keep", request.note)
        }
    }

    @Test
    fun `ssh conversion uses derived material and preserves metadata`() = runTest {
        val service = encryptedKeyService()
        val plan = parseImportPlan(
            ImportFormatId.LastPass,
            lastPassNoteCsv(lastPassSshNote(SSH_PRIVATE_KEY, "  secret,,  ", "source public key")),
            sshKeyImportService = service,
        )
        plan.assertVaultStorable()
        assertEquals(importSkips(), plan.skips)
        val item = plan.items.single()
        assertEquals(plan.folders.last().key, item.folderKey)
        with(item.request) {
            assertEquals(DSecret.Type.SshKey, type)
            assertEquals("Imported item", title)
            assertEquals(true, favorite)
            assertEquals("first line\nNotes:still notes", note)
            assertEquals(fakeSshKeyPair().privateKey.ssh, sshKey.privateKey)
            assertEquals(fakeSshKeyPair().publicKey.ssh, sshKey.publicKey)
            assertEquals(fakeSshKeyPair().publicKey.fingerprint, sshKey.fingerprint)
            assertFalse(fields.any { it.name == "Private Key" })
            assertEquals("source public key", fields.single { it.name == "Public Key" }.value)
            assertEquals("build.example.org", fields.single { it.name == "Hostname" }.value)
            assertEquals("RSA", fields.single { it.name == "Format" }.value)
            assertEquals("2048", fields.single { it.name == "Bit Strength" }.value)
            assertEquals("January,5,2024", fields.single { it.name == "Date" }.value)
            assertEquals(DSecret.Field.Type.Hidden, fields.single { it.name == "Passphrase" }.type)
        }
        assertEquals(SSH_PRIVATE_KEY, service.lastRequest!!.content.decodeToString())
        assertEquals("  secret,,  ", service.lastRequest!!.passphrase)
    }

    @Test
    fun `matching public key is not duplicated and unusual passphrases survive`() = runTest {
        for (passphrase in listOf(",,", "   ", "")) {
            // Without a passphrase, only an unencrypted key converts.
            val service = if (passphrase.isEmpty()) FakeSshKeyImportService() else encryptedKeyService()
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv(lastPassSshNote(SSH_PRIVATE_KEY, passphrase, fakeSshKeyPair().publicKey.ssh)),
                sshKeyImportService = service,
            )
            val fields = plan.items.single().request.fields
            assertFalse(fields.any { it.name == "Public Key" })
            assertEquals(passphrase.takeIf { it.isNotEmpty() }, service.lastRequest!!.passphrase)
            assertEquals(passphrase.takeIf { it.isNotEmpty() }, fields.find { it.name == "Passphrase" }?.value)
        }
    }

    @Test
    fun `ssh failures preserve source fields and count only the credential`() = runTest {
        val services = SshKeyImportError.entries.map {
            FakeSshKeyImportService(result = SshKeyImportResult.Error(it))
        } + listOf(
            FakeSshKeyImportService(result = sshKeyImportNeedsPassphrase()),
            FakeSshKeyImportService(error = IllegalStateException("Backend unavailable")),
        )
        for (service in services) {
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv(lastPassSshNote(SSH_PRIVATE_KEY, ",,", "original public key")),
                sshKeyImportService = service,
            )
            plan.assertVaultStorable()
            assertEquals(importSkips(ImportSkipReason.SshKey to 1), plan.skips)
            with(plan.items.single().request) {
                assertEquals(DSecret.Type.SecureNote, type)
                assertEquals("first line\nNotes:still notes", note)
                assertEquals(SSH_PRIVATE_KEY, fields.single { it.name == "Private Key" }.value)
                assertEquals(DSecret.Field.Type.Hidden, fields.single { it.name == "Private Key" }.type)
                assertEquals(",,", fields.single { it.name == "Passphrase" }.value)
                assertEquals(DSecret.Field.Type.Hidden, fields.single { it.name == "Passphrase" }.type)
                assertEquals("original public key", fields.single { it.name == "Public Key" }.value)
            }
        }
    }

    @Test
    fun `missing and oversized private keys fall back without calling crypto`() = runTest {
        for (privateKey in listOf("", "x".repeat(64 * 1024 + 1))) {
            val service = FakeSshKeyImportService()
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv(lastPassSshNote(privateKey)),
                sshKeyImportService = service,
            )
            assertEquals(0, service.callCount)
            assertEquals(importSkips(ImportSkipReason.SshKey to 1), plan.skips)
            with(plan.items.single().request) {
                assertEquals(DSecret.Type.SecureNote, type)
                assertEquals(
                    privateKey.takeIf { it.isNotEmpty() },
                    fields.find { it.name == "Private Key" }?.value,
                )
            }
        }
    }

    @Test
    fun `card expiry accepts complete partial and empty dates`() = runTest {
        val cases = listOf(
            Triple("January,2029", "1", "2029"),
            Triple("January,", "1", null),
            Triple(",2029", null, "2029"),
            Triple(" jAnUaRy , 29 ", "1", "2029"),
            Triple(",00", null, "2000"),
            Triple("12/29", "12", "2029"),
            Triple("2029-12", "12", "2029"),
            Triple(",", null, null),
            Triple("", null, null),
        )
        for ((raw, month, year) in cases) {
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv("NoteType:Credit Card\nExpiration Date:$raw\nStart Date:,\nNotes:"),
            )
            plan.assertVaultStorable()
            with(plan.items.single().request) {
                assertEquals(DSecret.Type.Card, type)
                assertEquals(month, card.expMonth, raw)
                assertEquals(year, card.expYear, raw)
                assertEquals(emptyList(), fields, raw)
            }
        }
    }

    @Test
    fun `invalid expiries and partial start dates stay as fields`() = runTest {
        for (raw in listOf("Unknown,2029", "January,invalid", "January,0000", "January,-1", "January,2029,extra")) {
            val plan = parseImportPlan(
                ImportFormatId.LastPass,
                lastPassNoteCsv("NoteType:Credit Card\nExpiration Date:$raw\nStart Date:January,\nNotes:"),
            )
            with(plan.items.single().request) {
                assertNull(card.expMonth)
                assertNull(card.expYear)
                assertEquals(raw, fields.single { it.name == "Expiration Date" }.value)
                assertEquals("January,", fields.single { it.name == "Start Date" }.value)
            }
        }
    }
}

internal fun lastPassNoteCsv(extra: String): String =
    "url,username,password,totp,extra,name,grouping,fav\n" +
        "http://sn,,,,\"${extra.replace("\"", "\"\"")}\",Imported item,Work\\Servers,1\n"

/** A key that converts only on the retry with its passphrase. */
private fun encryptedKeyService() = FakeSshKeyImportService(
    results = listOf(sshKeyImportNeedsPassphrase()),
    result = SshKeyImportResult.Success(fakeSshKeyPair()),
)

internal fun lastPassSshNote(
    privateKey: String,
    passphrase: String = "",
    publicKey: String = "",
): String = listOf(
    "NoteType:SSH Key",
    "Language:en-US",
    "Bit Strength:2048",
    "Format:RSA",
    "Passphrase:$passphrase",
    "Private Key:$privateKey",
    "Public Key:$publicKey",
    "Hostname:build.example.org",
    "Date:January,5,2024",
    "Notes:first line",
    "Notes:still notes",
).joinToString("\n")

private const val SSH_PRIVATE_KEY = "-----BEGIN RSA PRIVATE KEY-----\n" +
    "Proc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00112233\n" +
    "AAAAfakebase64bodyAAAA\n-----END RSA PRIVATE KEY-----"
