package com.artemchep.keyguard.common.service.imports.corpus

import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentPayload
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.TEST_INSTANT
import com.artemchep.keyguard.test.createSecret
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The synthetic vault of the Keyguard export in the import corpus: every item
 * type Keyguard has, nested folders and one attachment.
 */
internal object KeyguardImportCorpusSeed {
    const val ATTACHMENT_ID = "attachment-manual"
    const val ATTACHMENT_NAME = "manual.txt"
    const val ATTACHMENT_TEXT = "Synthetic attachment of the import corpus.\n"

    private const val ACCOUNT_ID = "corpus-account"

    fun folders(): List<DFolder> = listOf(
        "folder-work" to "Work",
        "folder-work-email" to "Work/Email",
    ).map { (id, name) ->
        DFolder(
            id = id,
            accountId = ACCOUNT_ID,
            revisionDate = TEST_INSTANT,
            service = BitwardenService(),
            deleted = false,
            synced = true,
            name = name,
        )
    }

    fun ciphers(): List<DSecret> = logins() + notes() + wallet() + keys() + environment()

    private fun logins() = listOf(
        createSecret(
            id = "login-mail",
            name = "Mail",
            accountId = ACCOUNT_ID,
            folderId = "folder-work-email",
            tags = listOf("work"),
            favorite = true,
            reprompt = true,
            notes = "First line\nSecond line",
            uris = listOf(
                DSecret.Uri("https://mail.example.com", DSecret.Uri.MatchType.Host),
                DSecret.Uri("androidapp://com.example.mail"),
            ),
            fields = listOf(
                DSecret.Field(name = "PIN", value = "1234", type = DSecret.Field.Type.Text),
                DSecret.Field(name = "Recovery", value = "abcd-efgh", type = DSecret.Field.Type.Hidden),
                DSecret.Field(name = "Remember me", value = "true", type = DSecret.Field.Type.Boolean),
                DSecret.Field(
                    name = "Login",
                    linkedId = DSecret.Field.LinkedId.Login_Username,
                    type = DSecret.Field.Type.Linked,
                ),
            ),
            login = DSecret.Login(
                username = "alice@example.com",
                password = "correct horse battery staple",
                totp = totp("otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example"),
                fido2Credentials = listOf(passkey()),
            ),
            passwordHistory = listOf(
                DSecret.Login.PasswordHistory(password = "old password", lastUsedDate = TEST_INSTANT),
            ),
            // Links name items of the source account; the importer drops them.
            links = listOf(DSecret.Link("00000000-0000-0000-0000-000000000001")),
        ),
        createSecret(
            id = "login-forum",
            name = "Old forum",
            accountId = ACCOUNT_ID,
            uris = listOf(DSecret.Uri("https://forum.example.org")),
            login = DSecret.Login(username = "bob", password = "older password"),
            archivedDate = TEST_INSTANT,
        ),
    )

    private fun notes() = listOf(
        createSecret(
            id = "note-wifi",
            name = "Wi-Fi",
            accountId = ACCOUNT_ID,
            folderId = "folder-work",
            type = DSecret.Type.SecureNote,
            notes = "The password is \"42\", with a comma.",
        ),
        createSecret(
            id = "note-manual",
            name = "Manual",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.SecureNote,
            notes = "Has an attachment.",
            attachments = listOf(
                DSecret.Attachment.Remote(
                    id = ATTACHMENT_ID,
                    url = null,
                    remoteCipherId = "note-manual",
                    fileName = ATTACHMENT_NAME,
                    keyBase64 = null,
                    size = ATTACHMENT_TEXT.encodeToByteArray().size.toLong(),
                ),
            ),
        ),
    )

    private fun wallet() = listOf(
        createSecret(
            id = "card-visa",
            name = "Visa",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.Card,
            card = DSecret.Card(
                cardholderName = "Alice Example",
                brand = "Visa",
                number = "4111111111111111",
                expMonth = "12",
                expYear = "2030",
                code = "123",
            ),
        ),
        createSecret(
            id = "identity-home",
            name = "Home",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.Identity,
            identity = DSecret.Identity(
                title = "Ms",
                firstName = "Alice",
                lastName = "Example",
                address1 = "12 Sample Road",
                city = "Springfield",
                postalCode = "62701",
                country = "US",
                email = "alice@example.com",
                phone = "+1 555 0100",
            ),
        ),
    )

    private fun keys() = listOf(
        createSecret(
            id = "ssh-server",
            name = "Server key",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = sshCorpusText("openssh/id_ed25519"),
                publicKey = sshCorpusText("openssh/id_ed25519.pub").trim(),
            ),
        ),
        createSecret(
            id = "gpg-signing",
            name = "Signing key",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.GpgKey,
            gpgKey = DSecret.GpgKey(
                privateKeyArmored = armored("PGP PRIVATE KEY BLOCK"),
                publicKeyArmored = armored("PGP PUBLIC KEY BLOCK"),
                fingerprint = "0123456789ABCDEF0123456789ABCDEF01234567",
            ),
        ),
    )

    private fun environment(): DSecret {
        val definition = EnvironmentDefinition(
            variables = listOf(
                EnvironmentVariable(
                    name = "API_TOKEN",
                    source = EnvironmentValueSource.Literal("synthetic-token"),
                ),
            ),
        )
        return createSecret(
            id = "environment-deploy",
            name = "Deploy",
            accountId = ACCOUNT_ID,
            type = DSecret.Type.Environment,
        ).copy(
            environment = EnvironmentPayload.Decoded(
                definition = definition,
                document = EnvironmentFormat.encode(definition),
                lossless = true,
            ),
        )
    }

    private fun totp(raw: String) = DSecret.Login.Totp(
        raw = raw,
        token = TotpToken.parse(raw).getOrNull()!!,
    )

    /**
     * A passkey with a real P-256 key, so the file looks like one a vault
     * holds; the key is generated anew on every run.
     */
    private fun passkey(): DSecret.Login.Fido2Credentials {
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return DSecret.Login.Fido2Credentials(
            credentialId = "8c0a1f6e-1c55-4bde-9b8f-4f1f2b7c9d10",
            keyType = "public-key",
            keyAlgorithm = "ECDSA",
            keyCurve = "P-256",
            keyValue = encoder.encodeToString(keyPair.private.encoded),
            rpId = "mail.example.com",
            rpName = "Example Mail",
            counter = 0,
            userHandle = encoder.encodeToString("alice".encodeToByteArray()),
            userName = "alice@example.com",
            userDisplayName = "Alice Example",
            discoverable = true,
            creationDate = TEST_INSTANT,
        )
    }

    private fun sshCorpusText(path: String): String = javaClass.classLoader
        .getResourceAsStream("ssh-import-corpus/$path")
        .let { requireNotNull(it) { "Missing SSH corpus resource $path" } }
        .use { it.readBytes().decodeToString() }
}

private fun armored(label: String) = "-----BEGIN $label-----\nsynthetic\n-----END $label-----"
