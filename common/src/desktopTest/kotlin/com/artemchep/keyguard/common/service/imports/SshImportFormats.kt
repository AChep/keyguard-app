package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class OnePasswordSshImportContractTest : SshImportContractTest(OnePasswordSshFormat)

class KeeperSshImportContractTest : SshImportContractTest(KeeperSshFormat)

class LastPassSshImportContractTest : SshImportContractTest(LastPassSshFormat)

class ProtonPassJsonSshImportContractTest : SshImportContractTest(ProtonPassSshFormat(archive = false))

class ProtonPassZipSshImportContractTest : SshImportContractTest(ProtonPassSshFormat(archive = true))

private fun json(value: String) = JsonPrimitive(value).toString()

private fun zip(name: String, text: String): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.encodeToByteArray())
        zip.closeEntry()
    }
    return output.toByteArray()
}

/** Asserts the passphrase survives as a hidden field, unless it is empty. */
private fun assertPassphraseField(
    request: CreateRequest,
    passphrase: String?,
) = assertEquals(
    listOfNotNull(
        passphrase?.takeIf { it.isNotEmpty() }
            ?.let { DSecret.Field("Passphrase", it, type = DSecret.Field.Type.Hidden) },
    ),
    request.fields.filter { it.name == "Passphrase" },
)

/** A 1PUX archive: an SSH Key item, archived, with the key in a section. */
private object OnePasswordSshFormat : SshImportFormat {
    override val format = ImportFormatId.OnePassword
    override val title = "Deploy key"
    override val carriesPassphrase = false
    override val carriesPublicKey = true
    override val carriesFingerprint = true

    override fun export(key: SshExport): ByteArray {
        val metadata = listOfNotNull(
            key.publicKey?.let { "\"publicKey\": ${json(it)}" },
            key.fingerprint?.let { "\"fingerprint\": ${json(it)}" },
        ).joinToString()
        val privateKey = key.privateKey?.let { "\"privateKey\": ${json(it)}, " }.orEmpty()
        val keyField = if (key.privateKey != null || metadata.isNotEmpty()) {
            """{"title": "private key", "id": "private_key", "value": {"sshKey":
                {$privateKey"metadata": {$metadata}}}},"""
        } else {
            ""
        }
        return zip(
            "export.data",
            """
                {"accounts": [{"vaults": [{"attrs": {"name": "Personal"}, "items": [{
                  "uuid": "i1", "favIndex": 1, "state": "archived", "categoryUuid": "114",
                  "overview": {"title": "Deploy key", "url": "", "tags": ["infra"]},
                  "details": {"notesPlain": "Keep this note", "sections": [{"title": "", "fields": [
                    $keyField
                    {"title": "host", "id": "host", "value": {"string": "server.example.org"}}
                  ]}]}
                }]}]}]}
            """.trimIndent(),
        )
    }

    override fun assertMetadata(plan: ImportPlan, item: ImportPlan.Item, key: SshExport) {
        val request = item.request
        assertEquals("Keep this note", request.note)
        assertEquals(true, request.favorite)
        assertEquals(listOf("infra"), request.tags)
        assertEquals(true, item.archived)
        assertEquals(listOf("Personal"), plan.folders.map { it.title })
        assertEquals(plan.folders.single().key, item.folderKey)
        assertTrue(DSecret.Field("host", "server.example.org", type = DSecret.Field.Type.Text) in request.fields)
    }
}

/**
 * A Keeper Commander CSV row of an SSH Keys record: the passphrase is in the
 * password column, the key in a typed `keyPair` field.
 */
private object KeeperSshFormat : SshImportFormat {
    override val format = ImportFormatId.Keeper
    override val title = "Deploy key"
    override val carriesPassphrase = true
    override val carriesPublicKey = false
    override val carriesFingerprint = false
    override val privateKeyLabel = "Deploy: primary"

    override fun export(key: SshExport): ByteArray {
        val custom = listOf("\$record_uid" to "source-id", "\$type" to "sshKeys") +
            listOfNotNull(key.privateKey?.let { "\$keyPair:Deploy: primary" to it }) +
            listOf("\$host:Host" to "server.example.org:22", "Environment" to "prod")
        val values = listOf(
            "Infrastructure\\Production", "Deploy key", "deploy", key.passphrase.orEmpty(),
            "ssh://server.example.org", "Keep, \"quoted\" notes\nsecond line", "Team#edit#reshare",
        ) + custom.flatMap { (name, value) -> listOf(name, value) }
        // Keeper Commander uses csv.writer, including quoted multiline private keys.
        return (values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\r\n")
            .encodeToByteArray()
    }

    override fun assertMetadata(plan: ImportPlan, item: ImportPlan.Item, key: SshExport) {
        val request = item.request
        assertEquals("Keep, \"quoted\" notes\nsecond line", request.note)
        assertEquals(CreateRequest.Login(), request.login)
        assertEquals(listOf("Team", "Infrastructure", "Production"), plan.folders.map { it.title })
        assertEquals(plan.folders.last().key, item.folderKey)
        listOf(
            DSecret.Field("Username", "deploy", type = DSecret.Field.Type.Text),
            DSecret.Field("URL", "ssh://server.example.org", type = DSecret.Field.Type.Text),
            DSecret.Field("Host", "server.example.org:22", type = DSecret.Field.Type.Text),
            DSecret.Field("Environment", "prod", type = DSecret.Field.Type.Text),
        ).forEach { assertTrue(it in request.fields, "Missing field ${it.name}") }
        assertPassphraseField(request, key.passphrase)
    }
}

/** A LastPass CSV row whose note is a structured SSH Key note. */
private object LastPassSshFormat : SshImportFormat {
    override val format = ImportFormatId.LastPass
    override val title = "Imported item"
    override val carriesPassphrase = true
    override val carriesPublicKey = true
    override val carriesFingerprint = false
    override val privateKeyLabel = "Private Key"
    override val publicKeyLabel = "Public Key"

    override fun export(key: SshExport): ByteArray = lastPassNoteCsv(
        lastPassSshNote(
            privateKey = key.privateKey.orEmpty(),
            passphrase = key.passphrase.orEmpty(),
            publicKey = key.publicKey.orEmpty(),
        ),
    ).encodeToByteArray()

    override fun assertMetadata(plan: ImportPlan, item: ImportPlan.Item, key: SshExport) {
        val request = item.request
        assertEquals("first line\nNotes:still notes", request.note)
        assertEquals(true, request.favorite)
        assertEquals(plan.folders.last().key, item.folderKey)
        listOf(
            DSecret.Field("Hostname", "build.example.org", type = DSecret.Field.Type.Text),
            DSecret.Field("Format", "RSA", type = DSecret.Field.Type.Text),
            DSecret.Field("Bit Strength", "2048", type = DSecret.Field.Type.Text),
            DSecret.Field("Date", "January,5,2024", type = DSecret.Field.Type.Text),
        ).forEach { assertTrue(it in request.fields, "Missing field ${it.name}") }
        assertPassphraseField(request, key.passphrase)
    }
}

/** A Proton Pass export, as a JSON file or the ZIP that wraps it. */
private class ProtonPassSshFormat(
    private val archive: Boolean,
) : SshImportFormat {
    override val format = ImportFormatId.ProtonPass
    override val title = "Deploy key"
    override val carriesPassphrase = false
    override val carriesPublicKey = true
    override val carriesFingerprint = true

    override fun export(key: SshExport): ByteArray {
        val content = listOfNotNull(
            key.privateKey?.let { "\"privateKey\": ${json(it)}" },
            key.publicKey?.let { "\"publicKey\": ${json(it)}" },
            key.fingerprint?.let { "\"fingerprint\": ${json(it)}" },
        ).joinToString()
        val text = """
            {"vaults": {"v": {"name": "Personal", "items": [{
              "state": 2, "pinned": true, "data": {
                "type": "sshKey", "metadata": {"name": "Deploy key", "note": "Keep this note"},
                "extraFields": [{"fieldName": "Server", "type": "text", "data": {"content": "build"}}],
                "content": {$content}
              }
            }]}}}
        """.trimIndent()
        return if (archive) zip("Proton Pass/data.json", text) else text.encodeToByteArray()
    }

    override fun assertMetadata(plan: ImportPlan, item: ImportPlan.Item, key: SshExport) {
        val request = item.request
        assertEquals("Keep this note", request.note)
        assertEquals(true, request.favorite)
        assertEquals(true, item.trashed)
        assertEquals(listOf("Personal"), plan.folders.map { it.title })
        assertEquals(plan.folders.single().key, item.folderKey)
        assertTrue(DSecret.Field("Server", "build", type = DSecret.Field.Type.Text) in request.fields)
    }
}
