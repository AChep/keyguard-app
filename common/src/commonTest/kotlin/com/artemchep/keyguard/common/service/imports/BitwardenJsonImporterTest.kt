package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.imports.impl.bitwarden.deriveBitwardenExportKey
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.crypto.NativeCipherEncryptor
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenEnvironmentFieldCodec
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenGpgKeyFieldCodec
import com.artemchep.keyguard.provider.bitwarden.model.PreLogin
import com.artemchep.keyguard.test.FakeSshKeyImportService
import com.artemchep.keyguard.test.createSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BitwardenJsonImporterTest {
    private val cryptoGenerator = NativeCryptoGenerator()
    private val cipherEncryptor = NativeCipherEncryptor(
        cryptoGenerator = cryptoGenerator,
        base64Service = Base64ServiceImpl(),
    )

    @Test
    fun `password-protected exports restore native Keyguard metadata`() = runTest {
        val protected = passwordProtected(
            password = "hunter2",
            plain = metadataExport(),
            kdf = PreLogin.Hash.Pbkdf2(iterationsCount = 5_000),
        )
        assertMetadataImported(parseImportPlan(ImportFormatId.Bitwarden, protected, password = "hunter2"))
    }

    @Test
    fun `plain export maps logins with every member`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = PLAIN_EXPORT,
        )
        plan.assertVaultStorable()
        assertEquals(listOf("Work", "Email"), plan.folders.map { it.title })

        val byTitle = plan.items.associateBy { it.request.title }
        val login = byTitle.getValue("Mail")
        assertEquals(plan.folders[1].key, login.folderKey)
        assertEquals(DSecret.Type.Login, login.request.type)
        assertEquals(true, login.request.favorite)
        assertEquals(true, login.request.reprompt)
        assertEquals(listOf("work"), login.request.tags)
        assertEquals(
            listOf(
                DSecret.Uri("https://mail.test", DSecret.Uri.MatchType.Host),
                DSecret.Uri("androidapp://com.mail", null),
            ),
            login.request.uris,
        )
        assertEquals("alice", login.request.login.username)
        assertEquals("secret", login.request.login.password)
        assertEquals("otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP", login.request.login.totp)
        assertEquals(
            listOf(
                DSecret.Field("PIN", "1234", type = DSecret.Field.Type.Hidden),
                DSecret.Field("Flag", "true", type = DSecret.Field.Type.Boolean),
                DSecret.Field(
                    "Login",
                    null,
                    linkedId = DSecret.Field.LinkedId.Login_Username,
                    type = DSecret.Field.Type.Linked,
                ),
            ),
            login.request.fields,
        )
        val passkey = login.request.fido2Credentials.single()
        assertEquals("cred-id", passkey.credentialId)
        assertEquals("mail.test", passkey.rpId)
        assertEquals(3, passkey.counter)
        assertEquals(true, passkey.discoverable)
    }

    @Test
    fun `plain export maps notes, cards, identities, ssh keys and skips`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = PLAIN_EXPORT,
        )
        val byTitle = plan.items.associateBy { it.request.title }
        val note = byTitle.getValue("Wi-Fi")
        assertEquals(DSecret.Type.SecureNote, note.request.type)
        assertEquals("the password is 42", note.request.note)
        assertEquals(plan.folders[0].key, note.folderKey)

        val card = byTitle.getValue("Visa")
        assertEquals(DSecret.Type.Card, card.request.type)
        assertEquals("4111111111111111", card.request.card.number)
        assertEquals("12", card.request.card.expMonth)

        val identity = byTitle.getValue("Me")
        assertEquals(DSecret.Type.Identity, identity.request.type)
        assertEquals("Alice", identity.request.identity.firstName)
        assertEquals("alice@mail.test", identity.request.identity.email)

        val ssh = byTitle.getValue("Server")
        assertEquals(DSecret.Type.SshKey, ssh.request.type)
        assertEquals("ssh-ed25519 AAAA", ssh.request.sshKey.publicKey)
        assertEquals("SHA256:abc", ssh.request.sshKey.fingerprint)

        // What a new item cannot carry is counted, attributed to its item.
        assertEquals(2, plan.skips[ImportSkipReason.PasswordHistory])
        assertEquals(mapOf("Mail" to 2), plan.skips.titlesOf(ImportSkipReason.PasswordHistory))
        assertEquals(1, plan.skips[ImportSkipReason.Attachment])
        assertEquals(1, plan.skips[ImportSkipReason.Item])
        assertEquals(mapOf("Future type" to 1), plan.skips.titlesOf(ImportSkipReason.Item))
    }

    @Test
    fun `organization collections become folders`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                {
                  "encrypted": false,
                  "collections": [{"id": "c1", "name": "Shared/Team"}],
                  "items": [{"type": 2, "name": "Doc", "collectionIds": ["c1"], "secureNote": {"type": 0}}]
                }
            """.trimIndent(),
        )
        assertEquals(listOf("Shared", "Team"), plan.folders.map { it.title })
        assertEquals(plan.folders[1].key, plan.items.single().folderKey)
    }

    @Test
    fun `keyguard gpg keys and environments come back from their fields`() = runTest {
        val gpgFields = BitwardenGpgKeyFieldCodec.encode(
            gpgKey = BitwardenCipher.GpgKey(
                privateKeyArmored = "PRIVATE",
                publicKeyArmored = "PUBLIC",
                fingerprint = "ABCD",
            ),
            fields = listOf(
                BitwardenCipher.Field(name = "Note", value = "kept", type = BitwardenCipher.Field.Type.Text),
            ),
        )
        val definition = EnvironmentDefinition(
            variables = listOf(
                EnvironmentVariable(
                    name = "TOKEN",
                    source = EnvironmentValueSource.Literal("value"),
                ),
            ),
        )
        val envFields = BitwardenEnvironmentFieldCodec.encode(
            BitwardenCipher.Environment(document = EnvironmentFormat.encode(definition)),
        )
        val text = buildJsonObject {
            put("encrypted", false)
            put(
                "items",
                buildJsonArray {
                    add(secureNoteJson("Key", gpgFields))
                    add(secureNoteJson("Env", envFields))
                },
            )
        }.toString()

        val plan = parseImportPlan(ImportFormatId.Bitwarden, text)
        plan.assertVaultStorable()
        val (gpg, env) = plan.items.map { it.request }
        assertEquals(DSecret.Type.GpgKey, gpg.type)
        assertEquals("PRIVATE", gpg.gpgKey.privateKeyArmored)
        assertEquals("PUBLIC", gpg.gpgKey.publicKeyArmored)
        assertEquals("ABCD", gpg.gpgKey.fingerprint)
        assertEquals(listOf("Note"), gpg.fields.map { it.name })

        assertEquals(DSecret.Type.Environment, env.type)
        assertEquals(definition, env.environment?.definition)
        assertTrue(env.fields.isEmpty())
    }

    @Test
    fun `a keyguard export imports back`() = runTest {
        val exported = JsonExportServiceImpl(Json).export(
            organizations = emptyList(),
            collections = emptyList(),
            folders = emptyList(),
            ciphers = listOf(
                createSecret(
                    id = "login",
                    name = "Login",
                    favorite = true,
                    tags = listOf("tag"),
                    notes = "note",
                    uris = listOf(DSecret.Uri("https://site.test", DSecret.Uri.MatchType.Exact)),
                    login = DSecret.Login(username = "u", password = "p"),
                    links = listOf(DSecret.Link("00000000-0000-0000-0000-000000000001")),
                ),
                createSecret(
                    id = "gpg",
                    name = "Key",
                    type = DSecret.Type.GpgKey,
                    gpgKey = DSecret.GpgKey(
                        privateKeyArmored = "PRIVATE",
                        publicKeyArmored = "PUBLIC",
                        fingerprint = "ABCD",
                    ),
                ),
            ),
        )

        val plan = parseImportPlan(ImportFormatId.Bitwarden, exported)
        plan.assertVaultStorable()
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        val (login, gpg) = plan.items.map { it.request }
        assertEquals("Login", login.title)
        assertEquals(true, login.favorite)
        assertEquals(listOf("tag"), login.tags)
        assertEquals("note", login.note)
        assertEquals(listOf(DSecret.Uri("https://site.test", DSecret.Uri.MatchType.Exact)), login.uris)
        assertEquals("u", login.login.username)
        assertEquals("p", login.login.password)
        // Links point at items of the source account and are not carried.
        assertTrue(login.fields.isEmpty())

        assertEquals(DSecret.Type.GpgKey, gpg.type)
        assertEquals("PRIVATE", gpg.gpgKey.privateKeyArmored)
    }

    @Test
    fun `a password-protected export asks for the password and checks it`() = runTest {
        val protected = passwordProtected(
            password = "hunter2",
            plain = """{"encrypted": false, "items": [{"type": 2, "name": "Doc", "secureNote": {"type": 0}}]}""",
            kdf = PreLogin.Hash.Pbkdf2(iterationsCount = 5_000),
        )

        assertEquals(
            ImportResult.Failure(ImportError.PasswordRequired),
            parseImport(ImportFormatId.Bitwarden, protected),
        )
        assertEquals(
            ImportResult.Failure(ImportError.WrongPassword),
            parseImport(ImportFormatId.Bitwarden, protected, password = "wrong"),
        )
        val plan = parseImportPlan(ImportFormatId.Bitwarden, protected, password = "hunter2")
        assertEquals(listOf("Doc"), plan.items.map { it.request.title })
    }

    @Test
    fun `an argon2 protected export decrypts`() = runTest {
        val protected = passwordProtected(
            password = "hunter2",
            plain = """{"encrypted": false, "items": [{"type": 2, "name": "Doc", "secureNote": {"type": 0}}]}""",
            kdf = PreLogin.Hash.Argon2id(iterationsCount = 2, memoryMb = 16, parallelism = 1),
        )
        val plan = parseImportPlan(ImportFormatId.Bitwarden, protected, password = "hunter2")
        assertEquals(1, plan.items.size)
    }

    @Test
    fun `hostile kdf settings and account encryption are refused`() = runTest {
        val tooExpensive = """
            {"encrypted": true, "passwordProtected": true, "salt": "s", "kdfType": 0,
             "kdfIterations": 2000000000, "encKeyValidation_DO_NOT_EDIT": "2.a|b|c", "data": "2.a|b|c"}
        """.trimIndent()
        assertEquals(
            ImportResult.Failure(ImportError.UnsupportedEncryption),
            parseImport(ImportFormatId.Bitwarden, tooExpensive, password = "x"),
        )
        val accountEncrypted = """{"encrypted": true, "encKeyValidation_DO_NOT_EDIT": "2.a|b|c", "items": []}"""
        assertEquals(
            ImportResult.Failure(ImportError.UnsupportedEncryption),
            parseImport(ImportFormatId.Bitwarden, accountEncrypted, password = "x"),
        )
    }

    @Test
    fun `json behind a byte-order mark and whitespace is told apart from csv`() = runTest {
        val json = """{"encrypted": false, "items": [{"type": 2, "name": "Doc", "secureNote": {"type": 0}}]}"""
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = "﻿\n  $json",
        )
        assertEquals(listOf("Doc"), plan.items.map { it.request.title })
        assertEquals("Bitwarden", plan.source.displayName)
    }

    @Test
    fun `archived and trashed items keep their state`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                {"encrypted": false, "items": [
                  {"type": 2, "name": "Live", "secureNote": {"type": 0}, "deletedDate": null},
                  {"type": 2, "name": "Old", "secureNote": {"type": 0}, "archivedDate": "2026-01-01T00:00:00Z"},
                  {"type": 2, "name": "Gone", "secureNote": {"type": 0}, "deletedDate": "2026-01-02T00:00:00Z"}
                ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Triple("Live", false, false),
                Triple("Old", true, false),
                Triple("Gone", false, true),
            ),
            plan.items.map { Triple(it.request.title, it.archived, it.trashed) },
        )
    }

    @Test
    fun `ssh bookmarks and certificate survive export and import`() = runTest {
        val bookmarks = listOf(
            DSecret.SshKey.Bookmark(name = "GitHub", url = "ssh://git@github.com"),
            DSecret.SshKey.Bookmark(name = null, url = "ssh://example.com"),
        )
        val certificate = "ssh-ed25519-cert-v01@openssh.com AAAA"
        val userField = DSecret.Field(name = "Host", value = "example.com", type = DSecret.Field.Type.Text)
        val exported = JsonExportServiceImpl(Json).export(
            organizations = emptyList(),
            collections = emptyList(),
            folders = emptyList(),
            ciphers = listOf(
                createSecret(
                    id = "ssh",
                    type = DSecret.Type.SshKey,
                    fields = listOf(userField),
                ).copy(
                    sshKey = DSecret.SshKey(
                        privateKey = "private",
                        publicKey = "ssh-ed25519 AAAA",
                        certificate = certificate,
                        bookmarks = bookmarks,
                    ),
                ),
            ),
        )
        val plan = parseImportPlan(ImportFormatId.Bitwarden, exported)
        plan.assertVaultStorable()
        val request = plan.items.single().request
        assertEquals(DSecret.Type.SshKey, request.type)
        // The importer gives unnamed custom fields its default label.
        assertEquals(bookmarks.map { it.copy(name = it.name ?: "Field") }, request.sshKey.bookmarks)
        assertEquals(certificate, request.sshKey.certificate)
        assertEquals(listOf(userField), request.fields)
    }

    @Test
    fun `ssh keys are copied as they are, never converted`() = runTest {
        // The source is a vault whose items are already SSH keys: keep them as
        // they were, even a key this version cannot read.
        val service = FakeSshKeyImportService(error = IllegalStateException("Must not convert"))
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                {"encrypted": false, "items": [
                  {"type": 5, "name": "Unreadable", "sshKey": {"privateKey": "not a key",
                    "publicKey": "ssh-ed25519 AAAA comment", "keyFingerprint": "SHA256:abc"}},
                  {"type": 5, "name": "Private only", "sshKey": {"privateKey":
                    "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n"}},
                  {"type": 5, "name": "Empty"}
                ]}
            """.trimIndent(),
            sshKeyImportService = service,
        )
        plan.assertVaultStorable()
        assertEquals(0, service.callCount)
        assertTrue(plan.skips.isEmpty, plan.skips.toString())
        assertEquals(
            listOf(
                "Unreadable" to CreateRequest.SshKey(
                    privateKey = "not a key",
                    publicKey = "ssh-ed25519 AAAA comment",
                    fingerprint = "SHA256:abc",
                ),
                "Private only" to CreateRequest.SshKey(
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----\n",
                ),
                "Empty" to CreateRequest.SshKey(),
            ),
            plan.items.map { it.request.title to it.request.sshKey },
        )
        assertTrue(plan.items.all { it.request.type == DSecret.Type.SshKey })
        assertTrue(plan.items.all { it.request.fields.isEmpty() })
    }

    @Test
    fun `a file that is not json or nests too deep fails the import`() = runTest {
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parseImport(ImportFormatId.Bitwarden, "name,url\n"),
        )
        assertEquals(
            ImportResult.Failure(ImportError.Parse),
            parseImport(ImportFormatId.Bitwarden, "[".repeat(10_000)),
        )
    }

    @Test
    fun `json without an items array is not an export`() = runTest {
        listOf(
            "{}",
            """{"folders":[]}""",
            """{"encrypted":false,"items":{}}""",
        ).forEach { text ->
            assertEquals(
                ImportResult.Failure(ImportError.Parse),
                parseImport(ImportFormatId.Bitwarden, text),
                text,
            )
        }
    }

    @Test
    fun `an export of an empty vault is empty, not broken`() = runTest {
        val result = parseImport(ImportFormatId.Bitwarden, """{"encrypted":false,"folders":[],"items":[]}""")

        val plan = assertIs<ImportResult.Success>(result).plan
        assertTrue(plan.items.isEmpty())
        assertTrue(plan.folders.isEmpty())
    }

    @Test
    fun `a damaged passkey is counted and its login still imports`() = runTest {
        val plan = parseImportPlan(
            format = ImportFormatId.Bitwarden,
            text = """
                {"encrypted": false, "items": [{"type": 1, "name": "Site",
                  "login": {"username": "u", "fido2Credentials": [{"rpId": "site.test"}]}}]}
            """.trimIndent(),
        )
        val login = plan.items.single().request
        assertEquals("u", login.login.username)
        assertTrue(login.fido2Credentials.isEmpty())
        assertEquals(1, plan.skips[ImportSkipReason.Passkey])
        assertNull(login.login.totp)
    }

    private fun secureNoteJson(
        name: String,
        fields: List<BitwardenCipher.Field>,
    ) = buildJsonObject {
        put("type", 2)
        put("name", name)
        put("secureNote", buildJsonObject { put("type", 0) })
        put(
            "fields",
            JsonArray(
                fields.map { field ->
                    buildJsonObject {
                        put("name", field.name)
                        put("value", field.value)
                        put(
                            "type",
                            JsonPrimitive(
                                when (field.type) {
                                    BitwardenCipher.Field.Type.Text -> 0
                                    BitwardenCipher.Field.Type.Hidden -> 1
                                    BitwardenCipher.Field.Type.Boolean -> 2
                                    BitwardenCipher.Field.Type.Linked -> 3
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }

    /**
     * Builds a password-protected export the way Bitwarden writes one.
     */
    private fun passwordProtected(
        password: String,
        plain: String,
        kdf: PreLogin.Hash,
    ): String {
        val salt = "c2FsdC1mb3ItdGVzdHM="
        val key = deriveBitwardenExportKey(
            cryptoGenerator = cryptoGenerator,
            password = password,
            salt = salt,
            kdf = kdf,
        )
        fun encrypt(text: String) = cipherEncryptor.encode2(
            cipherType = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
            plainText = text.encodeToByteArray(),
            symmetricCryptoKey = key,
        )
        return buildJsonObject {
            put("encrypted", true)
            put("passwordProtected", true)
            put("salt", salt)
            when (kdf) {
                is PreLogin.Hash.Pbkdf2 -> {
                    put("kdfType", 0)
                    put("kdfIterations", kdf.iterationsCount)
                }

                is PreLogin.Hash.Argon2id -> {
                    put("kdfType", 1)
                    put("kdfIterations", kdf.iterationsCount)
                    put("kdfMemory", kdf.memoryMb)
                    put("kdfParallelism", kdf.parallelism)
                }
            }
            put("encKeyValidation_DO_NOT_EDIT", encrypt("7f3c1a2e-validation"))
            put("data", encrypt(plain))
        }.toString()
    }
}

/**
 * Written by hand for these tests.
 */
private val PLAIN_EXPORT = """
{
  "encrypted": false,
  "folders": [
    {"id": "f1", "name": "Work"},
    {"id": "f2", "name": "Work/Email"},
    {"id": "f3"}
  ],
  "items": [
    {
      "id": "i1", "folderId": "f2", "type": 1, "reprompt": 1, "name": "Mail", "favorite": true,
      "tags": ["work"],
      "fields": [
        {"name": "PIN", "value": "1234", "type": 1, "linkedId": null},
        {"name": "Flag", "value": "true", "type": 2, "linkedId": null},
        {"name": "Login", "value": null, "type": 3, "linkedId": 100},
        {"name": "Orphan", "value": null, "type": 3, "linkedId": 9999}
      ],
      "login": {
        "uris": [{"match": 1, "uri": "https://mail.test"}, {"match": null, "uri": "androidapp://com.mail"}, {"uri": " "}],
        "username": "alice", "password": "secret",
        "totp": "otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP",
        "fido2Credentials": [{
          "credentialId": "cred-id", "keyType": "public-key", "keyAlgorithm": "ECDSA", "keyCurve": "P-256",
          "keyValue": "key", "rpId": "mail.test", "rpName": "Mail", "counter": "3",
          "userHandle": "handle", "userName": "alice", "userDisplayName": "Alice",
          "discoverable": "true", "creationDate": "2024-01-01T00:00:00Z"
        }]
      },
      "passwordHistory": [
        {"lastUsedDate": "2024-01-01T00:00:00Z", "password": "old1"},
        {"lastUsedDate": "2024-01-02T00:00:00Z", "password": "old2"}
      ]
    },
    {"id": "i2", "folderId": "f1", "type": 2, "name": "Wi-Fi", "notes": "the password is 42", "secureNote": {"type": 0},
     "attachments": [{"id": "a1", "fileName": "router.png", "size": "100"}]},
    {"id": "i3", "type": 3, "name": "Visa", "card": {"cardholderName": "Alice", "number": "4111111111111111", "expMonth": "12", "expYear": "2030", "code": "123"}},
    {"id": "i4", "type": 4, "name": "Me", "identity": {"firstName": "Alice", "email": "alice@mail.test"}},
    {"id": "i5", "type": 5, "name": "Server", "sshKey": {"privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----", "publicKey": "ssh-ed25519 AAAA", "keyFingerprint": "SHA256:abc"}},
    {"id": "i6", "type": 99, "name": "Future type"}
  ]
}
""".trimIndent()
