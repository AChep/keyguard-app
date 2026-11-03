package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pins what the sync writes for items that carry every kind of Keyguard data
 * that travels in custom fields, and that it reads all of it back.
 */
class CipherCryptoTransportFieldsTest {
    @Test
    fun `encrypt writes every transport field in a fixed order`() {
        val rendered = transportCiphers().joinToString(separator = "\n") { cipher ->
            cipher.transform(identityEncrypt, identityEncrypt).render()
        }
        assertEquals(EXPECTED_ENCRYPTED, rendered)
    }

    @Test
    fun `decrypt reads back every transport field`() {
        transportCiphers().forEach { cipher ->
            val decrypted = cipher
                .transform(identityEncrypt, identityEncrypt)
                .transform(identityDecrypt, identityDecrypt)
            assertEquals(cipher.type, decrypted.type, cipher.cipherId)
            // The icon field takes the name of a user field that had it.
            val fields = cipher.fields.map {
                if (it.name == "Custom Icon Name") it.copy(name = "Custom Icon Name #1") else it
            }
            assertEquals(fields, decrypted.fields, cipher.cipherId)
            assertEquals(cipher.customIcon, decrypted.customIcon, cipher.cipherId)
            assertEquals(cipher.environment, decrypted.environment, cipher.cipherId)
            assertEquals(cipher.gpgKey?.copy(metadata = null), decrypted.gpgKey, cipher.cipherId)
            assertEquals(cipher.sshKey, decrypted.sshKey, cipher.cipherId)
            assertEquals(cipher.login, decrypted.login, cipher.cipherId)
            assertEquals(cipher.links, decrypted.links, cipher.cipherId)
        }
    }
}

private fun transportCiphers() = listOf(
    cipher("gpg", BitwardenCipher.Type.GpgKey).copy(
        fields = listOf(
            field("Note", "hello"),
            field("Custom Icon Name", "user icon"),
        ),
        customIcon = KeePassIcon.World,
        gpgKey = BitwardenCipher.GpgKey(
            privateKeyArmored = "PRIVATE",
            publicKeyArmored = "Q".repeat(4_000),
            fingerprint = "ABCD",
            metadata = GpgAgentKeyMetadata(),
        ),
        links = listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
    ),
    cipher("env", BitwardenCipher.Type.Environment).copy(
        fields = listOf(field("Note", "hello")),
        customIcon = KeePassIcon.Warning,
        environment = BitwardenCipher.Environment(
            document = EnvironmentFormat.encode(
                EnvironmentDefinition(
                    listOf(
                        EnvironmentVariable(
                            name = "TOKEN",
                            source = EnvironmentValueSource.Literal("value"),
                        ),
                    ),
                ),
            ),
        ),
        links = listOf(BitwardenCipher.Link(OTHER_REMOTE_ID), BitwardenCipher.Link(TARGET_REMOTE_ID)),
    ),
    cipher("ssh", BitwardenCipher.Type.SshKey).copy(
        fields = listOf(field("Note", "hello")),
        customIcon = KeePassIcon.TerminalEncrypted,
        sshKey = BitwardenCipher.SshKey(
            privateKey = "PRIVATE",
            publicKey = "ssh-ed25519 AAAA",
            certificate = "C".repeat(5_000),
        ),
    ),
    cipher("login", BitwardenCipher.Type.Login).copy(
        fields = listOf(field("Note", "hello")),
        login = BitwardenCipher.Login(
            username = "user",
            uris = emptyList(),
            fido2Credentials = listOf(
                passkey("cred-1", prfSecret = null),
                passkey("cred-2", prfSecret = "bmV3"),
            ),
        ),
        links = listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
    ),
)

private fun BitwardenCipher.render(): String = buildString {
    append(cipherId).append(": ").append(type)
    fields.forEach { field ->
        append("\n  ")
            .append(field.type).append('|')
            .append(field.name).append('|')
            .append(field.value?.abbreviated()).append('|')
            .append(field.linkedId)
    }
    append("\n  sshKey=").append(sshKey?.copy(certificate = sshKey.certificate?.abbreviated()))
    append("\n  login=").append(login?.fido2Credentials?.map { it.prfSecret to it.prfSecretWithoutUv })
    append("\n  gpgKey=").append(gpgKey).append(" environment=").append(environment)
    append(" links=").append(links).append(" customIcon=").append(customIcon)
}

/** A long value as its first character and its length. */
private fun String.abbreviated() = if (length > 100) "${first()}x$length" else this

private fun cipher(
    id: String,
    type: BitwardenCipher.Type,
) = BitwardenCipher(
    accountId = "account",
    cipherId = id,
    revisionDate = Instant.fromEpochSeconds(0),
    service = BitwardenService(),
    name = id,
    notes = null,
    favorite = false,
    reprompt = BitwardenCipher.RepromptType.None,
    type = type,
)

private fun field(
    name: String,
    value: String,
) = BitwardenCipher.Field(
    name = name,
    value = value,
    type = BitwardenCipher.Field.Type.Text,
)

private fun passkey(
    credentialId: String,
    prfSecret: String?,
) = BitwardenCipher.Login.Fido2Credentials(
    credentialId = credentialId,
    keyType = "public-key",
    keyAlgorithm = "ECDSA",
    keyCurve = "P-256",
    keyValue = "AQID",
    rpId = "example.com",
    rpName = null,
    counter = "0",
    discoverable = "true",
    creationDate = Instant.fromEpochSeconds(0),
    prfSecret = prfSecret,
)

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"

private val EXPECTED_ENCRYPTED = listOf(
    "gpg: GpgKey",
    "  Hidden|keyguard.gpg.private_key.armored|PRIVATE|null",
    "  Text|keyguard.gpg.public_key.armored.v1.part.1|Qx3500|null",
    "  Text|keyguard.gpg.public_key.armored.v1.part.2|Qx500|null",
    "  Text|keyguard.gpg.public_key.armored.v1.sha256|" +
        "e6879366972d179f8f0b61bc693838ba80a80b84259312554f62ec2864d65bb7|null",
    "  Text|keyguard.gpg.fingerprint|ABCD|null",
    "  Text|Custom Icon Name|World|null",
    "  Text|Note|hello|null",
    "  Text|Custom Icon Name #1|user icon|null",
    "  Text|keyguard.link.1|keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12|null",
    "  sshKey=null",
    "  login=null",
    "  gpgKey=null environment=null links=[] customIcon=World",
    "env: Environment",
    "  Text|Custom Icon Name|Warning|null",
    "  Hidden|keyguard.environment.v1.part.1|" +
        "{\"version\":1,\"variables\":[{\"name\":\"TOKEN\"," +
        "\"source\":{\"kind\":\"literal\",\"value\":\"value\"}}]}|null",
    "  Hidden|keyguard.environment.v1.sha256|4e2303b468c5799e26059987142cff8c8cd463c92690905dbd244adfdb130bd8|null",
    "  Text|Note|hello|null",
    "  Text|keyguard.link.1|keyguard://cipher/c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13|null",
    "  Text|keyguard.link.2|keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12|null",
    "  sshKey=null",
    "  login=null",
    "  gpgKey=null environment=null links=[] customIcon=Warning",
    "ssh: SshKey",
    "  Text|Custom Icon Name|TerminalEncrypted|null",
    "  Text|Note|hello|null",
    "  Text|keyguard.ssh.certificate.v1.part.1|Cx3500|null",
    "  Text|keyguard.ssh.certificate.v1.part.2|Cx1500|null",
    "  Text|keyguard.ssh.certificate.v1.sha256|0786f809f430e419f278cc0329e3f4ca155586d5effa584b3feaabb4b323f419|null",
    "  sshKey=SshKey(privateKey=PRIVATE, publicKey=ssh-ed25519 AAAA, fingerprint=null, certificate=null, bookmarks=[])",
    "  login=null",
    "  gpgKey=null environment=null links=[] customIcon=TerminalEncrypted",
    "login: Login",
    "  Text|Note|hello|null",
    "  Hidden|keyguard.passkeys|" +
        "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-2\",\"prfSecret\":\"bmV3\"}]}|null",
    "  Text|keyguard.link.1|keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12|null",
    "  sshKey=null",
    "  login=[(null, null), (null, null)]",
    "  gpgKey=null environment=null links=[] customIcon=null",
).joinToString(separator = "\n")
