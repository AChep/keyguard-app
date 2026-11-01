package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.export.impl.transportFieldSecrets
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_BOUNDED_USER_CERTIFICATE
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_PRIVATE_KEY
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_PUBLIC_KEY
import com.artemchep.keyguard.common.service.sshagent.TEST_ED25519_USER_CERTIFICATE
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenPasskeyFieldCodec
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenSshCertificateFieldCodec
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BitwardenMetadataImportTest {
    @Test
    fun `native metadata survives export and import`() = runTest {
        val plan = parseImportPlan(ImportFormatId.Bitwarden, metadataExport())
        plan.assertVaultStorable()
        assertMetadataImported(plan)
    }

    @Test
    fun `every transport field comes back from a keyguard export`() = runTest {
        val secrets = transportFieldSecrets()
        val plan = parseImportPlan(ImportFormatId.Bitwarden, exportMetadataSecrets(secrets))
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        val requests = plan.items.associate { it.request.title to it.request }
        val note = field("Note", "hello")

        val gpg = requests.getValue("gpg")
        assertEquals(DSecret.Type.GpgKey, gpg.type)
        assertEquals(CreateRequest.GpgKey("PRIVATE", "PUBLIC", "ABCD"), gpg.gpgKey)
        assertEquals(listOf("work", "keys"), gpg.tags)
        assertEquals(KeePassIcon.World, gpg.customIcon)
        assertEquals(listOf(note, field("Custom Icon Name #1", "user icon")), gpg.fields)

        val env = requests.getValue("env")
        assertEquals(DSecret.Type.Environment, env.type)
        assertEquals(secrets[1].environment, env.environment)
        assertEquals(KeePassIcon.Warning, env.customIcon)
        assertEquals(listOf(note), env.fields)

        val ssh = requests.getValue("ssh")
        assertEquals(secrets[2].sshKey?.certificate, ssh.sshKey.certificate)
        assertEquals(KeePassIcon.TerminalEncrypted, ssh.customIcon)
        assertEquals(listOf(note), ssh.fields)

        val login = requests.getValue("login")
        assertEquals(listOf("web", "field tag"), login.tags)
        assertEquals(listOf("b2xk", "bmV3"), login.fido2Credentials.map { it.prfSecret })
        assertNull(login.customIcon)
        assertEquals(listOf(note), login.fields)

        val large = requests.getValue("large")
        assertEquals(secrets[4].gpgKey?.privateKeyArmored, large.gpgKey.privateKeyArmored)
        assertEquals(secrets[4].gpgKey?.publicKeyArmored, large.gpgKey.publicKeyArmored)
        assertEquals(secrets[5].sshKey?.certificate, requests.getValue("large-ssh").sshKey.certificate)
        // The links point at items of the source account, so none comes back.
        assertTrue(plan.items.all { item -> item.request.fields.none { it.name.orEmpty().startsWith("keyguard.") } })
    }

    @Test
    fun `large certificates and passkey documents survive chunking`() = runTest {
        val certificate = TEST_ED25519_USER_CERTIFICATE + "x".repeat(12_000)
        val credentials = (1..70).map { metadataPasskey("credential-$it") }
        val secrets = listOf(
            createSecret("ssh", type = DSecret.Type.SshKey, sshKey = DSecret.SshKey(certificate = certificate)),
            createSecret("login", login = DSecret.Login(fido2Credentials = credentials)),
        )
        val plan = parseImportPlan(ImportFormatId.Bitwarden, exportMetadataSecrets(secrets))
        assertEquals(certificate, plan.items[0].request.sshKey.certificate)
        assertEquals(credentials, plan.items[1].request.fido2Credentials)
        assertTrue(plan.items.all { it.request.fields.isEmpty() })
    }

    @Test
    fun `expired certificates are preserved for the agent to validate`() = runTest {
        val secret = createSecret(
            "expired",
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(certificate = TEST_ED25519_BOUNDED_USER_CERTIFICATE),
        )
        val result = importTestService().parse(
            format = ImportFormatId.Bitwarden,
            input = BytesImportInput(exportMetadataSecrets(listOf(secret))),
            now = Instant.parse("2101-01-01T00:00:00Z"),
        )
        val plan = assertIs<ImportResult.Success>(result).plan
        assertEquals(TEST_ED25519_BOUNDED_USER_CERTIFICATE, plan.items.single().request.sshKey.certificate)
    }

    @Test
    fun `PRF metadata follows credential IDs rather than array order`() = runTest {
        val fields = BitwardenPasskeyFieldCodec.encode(
            listOf(
                BitwardenPasskeyFieldCodec.Secrets("second", "second-secret", "second-without-uv"),
                BitwardenPasskeyFieldCodec.Secrets("first", "first-secret", "first-without-uv"),
            ),
            emptyList(),
        ).map(BitwardenCipher.Field::toDomain)
        val secret = createSecret(
            "login",
            login = DSecret.Login(fido2Credentials = listOf("first", "second", "third").map {
                metadataPasskey(it).copy(prfSecret = null, prfSecretWithoutUv = null)
            }),
            fields = fields,
        )
        // Simulate another client reordering native credentials without editing the hidden document.
        val root = Json.parseToJsonElement(exportMetadataSecrets(listOf(secret))).jsonObject
        val item = root.getValue("items").jsonArray.single().jsonObject
        val login = item.getValue("login").jsonObject
        val reordered = JsonObject(login + ("fido2Credentials" to JsonArray(
            login.getValue("fido2Credentials").jsonArray.reversed(),
        )))
        val text = JsonObject(root + ("items" to JsonArray(listOf(JsonObject(item + ("login" to reordered))))))
        val request = parseImportPlan(ImportFormatId.Bitwarden, text.toString()).items.single().request
        assertEquals(listOf(null, "second-secret", "first-secret"), request.fido2Credentials.map { it.prfSecret })
        assertEquals(
            listOf(null, "second-without-uv", "first-without-uv"),
            request.fido2Credentials.map { it.prfSecretWithoutUv },
        )
        assertTrue(request.fields.isEmpty())
    }

    @Test
    fun `unsupported or incomplete credential metadata stays in custom fields`() = runTest {
        val certificate = BitwardenSshCertificateFieldCodec.encode("x".repeat(12_000))
        val passkeys = BitwardenPasskeyFieldCodec.encode(
            listOf(BitwardenPasskeyFieldCodec.Secrets("first", "x".repeat(12_000))),
            emptyList(),
        )
        val invalidDocuments = listOf(
            "not-json",
            """{"version":2,"credentials":[]}""",
        ).map { field("keyguard.passkeys", it, DSecret.Field.Type.Hidden) }
        val cases = listOf(
            DSecret.Type.SshKey to certificate.dropLast(1).map(BitwardenCipher.Field::toDomain),
            DSecret.Type.SshKey to listOf(field("keyguard.ssh.certificate", "cert", DSecret.Field.Type.Hidden)),
            DSecret.Type.Login to passkeys.dropLast(1).map(BitwardenCipher.Field::toDomain),
            DSecret.Type.Login to listOf(field("keyguard.passkeys", """{"version":1,"credentials":[]}""")),
        ) + invalidDocuments.map { DSecret.Type.Login to listOf(it) }
        cases.forEach { (type, fields) ->
            val text = exportMetadataSecrets(listOf(createSecret("item", type = type, fields = fields)))
            val request = parseImportPlan(ImportFormatId.Bitwarden, text).items.single().request
            assertEquals(fields, request.fields)
            assertNull(request.sshKey.certificate)
        }
    }

    @Test
    fun `credential metadata on unrelated item types stays untouched`() = runTest {
        val fields = (
            BitwardenSshCertificateFieldCodec.encode(TEST_ED25519_USER_CERTIFICATE) +
                BitwardenPasskeyFieldCodec.encode(
                    listOf(BitwardenPasskeyFieldCodec.Secrets("first", "secret")),
                    emptyList(),
                )
            ).map(BitwardenCipher.Field::toDomain)
        val text = exportMetadataSecrets(listOf(createSecret("note", type = DSecret.Type.SecureNote, fields = fields)))
        val request = parseImportPlan(ImportFormatId.Bitwarden, text).items.single().request
        assertEquals(fields, request.fields)
        assertNull(request.sshKey.certificate)
    }

    @Test
    fun `tags merge in order while blank hidden and linked fields remain`() = runTest {
        val kept = listOf(
            field("Tag", " "),
            field("Tag", "hidden", DSecret.Field.Type.Hidden),
            field("Tag", "linked").copy(linkedId = DSecret.Field.LinkedId.Login_Username),
        )
        val secret = createSecret(
            "tags",
            tags = listOf("top", "shared"),
            fields = listOf(field("Tag", "shared"), field("Tag", "custom"), field("Tag", "custom")) + kept,
        )
        val request = parseImportPlan(ImportFormatId.Bitwarden, exportMetadataSecrets(listOf(secret)))
            .items.single().request
        assertEquals(listOf("top", "shared", "custom"), request.tags)
        assertEquals(kept, request.fields)
    }

    @Test
    fun `icon export renames colliding user fields without overwriting them`() = runTest {
        val secret = createSecret(
            "icon",
            fields = listOf(
                field("Custom Icon Name", "Warning"),
                field("Custom Icon Name #1", "reserved"),
                field("Custom Icon Name", "hidden", DSecret.Field.Type.Hidden),
            ),
        ).copy(customIcon = KeePassIcon.World)
        val request = parseImportPlan(ImportFormatId.Bitwarden, exportMetadataSecrets(listOf(secret)))
            .items.single().request
        assertEquals(KeePassIcon.World, request.customIcon)
        assertEquals(
            listOf("Custom Icon Name #2", "Custom Icon Name #1", "Custom Icon Name #3"),
            request.fields.map { it.name },
        )
        assertEquals(secret.fields.map { it.value }, request.fields.map { it.value })
        assertEquals(secret.fields.map { it.type }, request.fields.map { it.type })
    }

    @Test
    fun `only the first valid icon is consumed including the explicit default`() = runTest {
        listOf(KeePassIcon.Key, KeePassIcon.Warning).forEach { icon ->
            val kept = listOf(
                field("Custom Icon Name", "FutureIcon"),
                field("Custom Icon Name", "World", DSecret.Field.Type.Hidden),
                field("Custom Icon Name", "World").copy(linkedId = DSecret.Field.LinkedId.Login_Username),
            )
            val extra = field("Custom Icon Name", "World")
            val fields = kept + field("Custom Icon Name", icon.name) + extra
            val text = exportMetadataSecrets(listOf(createSecret("icon", fields = fields)))
            val request = parseImportPlan(ImportFormatId.Bitwarden, text).items.single().request
            // The default icon reads as none, as the sync reads it.
            assertEquals(icon.takeUnless { it == KeePassIcon.Key }, request.customIcon)
            assertEquals(kept + extra, request.fields)
        }
    }

    private fun field(name: String, value: String, type: DSecret.Field.Type = DSecret.Field.Type.Text) =
        DSecret.Field(name = name, value = value, type = type)
}

internal fun metadataPasskey(id: String) = DSecret.Login.Fido2Credentials(
    credentialId = id,
    keyType = "public-key",
    keyAlgorithm = "ECDSA",
    keyCurve = "P-256",
    keyValue = "test-key",
    rpId = "example.com",
    rpName = null,
    counter = 0,
    userHandle = null,
    userName = null,
    userDisplayName = null,
    discoverable = true,
    creationDate = IMPORT_TEST_NOW,
    prfSecret = "c2VjcmV0",
    prfSecretWithoutUv = "b3RoZXI",
)

internal fun metadataExport(): String = exportMetadataSecrets(
    listOf(
        createSecret(
            "ssh",
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = TEST_ED25519_PRIVATE_KEY,
                publicKey = TEST_ED25519_PUBLIC_KEY,
                certificate = TEST_ED25519_USER_CERTIFICATE,
            ),
            tags = listOf("native"),
            fields = listOf(DSecret.Field(name = "Tag", value = "transport", type = DSecret.Field.Type.Text)),
        ).copy(customIcon = KeePassIcon.TerminalEncrypted),
        createSecret("login", login = DSecret.Login(fido2Credentials = listOf(metadataPasskey("first")))),
    ),
)

internal fun exportMetadataSecrets(secrets: List<DSecret>): String = JsonExportServiceImpl(Json).export(
    organizations = emptyList(),
    collections = emptyList(),
    folders = emptyList(),
    ciphers = secrets,
)

internal fun assertMetadataImported(plan: ImportPlan) {
    val ssh = plan.items.first { it.request.type == DSecret.Type.SshKey }.request
    assertEquals(TEST_ED25519_PRIVATE_KEY, ssh.sshKey.privateKey)
    assertEquals(TEST_ED25519_PUBLIC_KEY, ssh.sshKey.publicKey)
    assertEquals(TEST_ED25519_USER_CERTIFICATE, ssh.sshKey.certificate)
    assertEquals(listOf("native", "transport"), ssh.tags)
    assertEquals(KeePassIcon.TerminalEncrypted, ssh.customIcon)
    assertTrue(ssh.fields.isEmpty())
    val login = plan.items.first { it.request.type == DSecret.Type.Login }.request
    assertEquals(listOf(metadataPasskey("first")), login.fido2Credentials)
    assertTrue(login.fields.isEmpty())
}
