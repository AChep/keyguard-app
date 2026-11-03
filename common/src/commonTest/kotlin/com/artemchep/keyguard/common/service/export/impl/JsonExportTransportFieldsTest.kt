package com.artemchep.keyguard.common.service.export.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.EnvironmentVariable
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.environmentPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pins the exact JSON export of items that carry every kind of Keyguard data
 * that travels in custom fields, so a change to the transport is a visible
 * change to this file.
 */
class JsonExportTransportFieldsTest {
    @Test
    fun `exports every transport field byte for byte`() {
        val exported = JsonExportServiceImpl(Json).export(
            organizations = emptyList(),
            collections = emptyList(),
            folders = emptyList(),
            ciphers = transportFieldSecrets(),
        )
        assertEquals(EXPECTED_EXPORT, exported)
    }
}

/** Items that carry every kind of Keyguard data that travels in custom fields. */
internal fun transportFieldSecrets(): List<DSecret> = listOf(
    gpgSecret(),
    environmentSecret(),
    sshSecret(),
    loginSecret(),
    largeGpgSecret(),
    largeSshSecret(),
    gpgMetadataSecret(),
)

private fun gpgSecret() = createSecret(
    id = "gpg",
    type = DSecret.Type.GpgKey,
    tags = listOf("work", "keys"),
    fields = listOf(
        field(GpgAgentFields.PRIVATE_KEY_ARMORED, "stale private key"),
        field("Note", "hello"),
        field("Custom Icon Name", "user icon"),
    ),
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = "PRIVATE",
        publicKeyArmored = "PUBLIC",
        fingerprint = "ABCD",
        metadata = GpgAgentKeyMetadata(),
    ),
    links = listOf(DSecret.Link(TARGET_REMOTE_ID)),
).copy(customIcon = KeePassIcon.World)

private fun environmentSecret() = createSecret(
    id = "env",
    type = DSecret.Type.Environment,
    fields = listOf(
        field("keyguard.environment.v1.part.1", "stale environment", DSecret.Field.Type.Hidden),
        field("Note", "hello"),
    ),
    links = listOf(DSecret.Link(OTHER_REMOTE_ID), DSecret.Link(TARGET_REMOTE_ID)),
).copy(
    customIcon = KeePassIcon.Warning,
    environment = environmentPayload(
        EnvironmentDefinition(
            listOf(
                EnvironmentVariable(
                    name = "TOKEN",
                    source = EnvironmentValueSource.Literal("value"),
                ),
            ),
        ),
    ),
)

private fun sshSecret() = createSecret(
    id = "ssh",
    type = DSecret.Type.SshKey,
    fields = listOf(
        field("keyguard.ssh.certificate", "stale certificate"),
        field("Note", "hello"),
    ),
    sshKey = DSecret.SshKey(
        privateKey = "PRIVATE",
        publicKey = "ssh-ed25519 AAAA",
        certificate = "ssh-ed25519-cert-v01@openssh.com AAAA user@example.com",
    ),
).copy(customIcon = KeePassIcon.TerminalEncrypted)

private fun loginSecret() = createSecret(
    id = "login",
    tags = listOf("web"),
    fields = listOf(
        field(
            "keyguard.passkeys",
            """{"version":1,"credentials":[{"credentialId":"cred-1","prfSecret":"b2xk"}]}""",
            DSecret.Field.Type.Hidden,
        ),
        field("Note", "hello"),
        field("Tag", "field tag"),
    ),
    login = DSecret.Login(
        username = "user",
        fido2Credentials = listOf(
            passkey("cred-1", prfSecret = null),
            passkey("cred-2", prfSecret = "bmV3"),
        ),
    ),
    links = listOf(DSecret.Link(TARGET_REMOTE_ID)),
).copy(customIcon = KeePassIcon.Key)

private fun largeGpgSecret() = createSecret(
    id = "large",
    type = DSecret.Type.SecureNote,
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = LARGE_PRIVATE_KEY,
        publicKeyArmored = LARGE_PUBLIC_KEY,
    ),
)

private fun largeSshSecret() = createSecret(
    id = "large-ssh",
    type = DSecret.Type.SshKey,
    sshKey = DSecret.SshKey(
        certificate = LARGE_CERTIFICATE,
    ),
)

private fun gpgMetadataSecret() = createSecret(
    id = "gpg-metadata",
    type = DSecret.Type.GpgKey,
    fields = listOf(
        field(GpgAgentFields.PRIVATE_KEY_ARMORED, "user value"),
    ),
    gpgKey = DSecret.GpgKey(
        privateKeyArmored = " ",
        metadata = GpgAgentKeyMetadata(),
    ),
)

private fun field(
    name: String,
    value: String,
    type: DSecret.Field.Type = DSecret.Field.Type.Text,
) = DSecret.Field(
    name = name,
    value = value,
    type = type,
)

private fun passkey(
    credentialId: String,
    prfSecret: String?,
) = DSecret.Login.Fido2Credentials(
    credentialId = credentialId,
    keyType = "public-key",
    keyAlgorithm = "ECDSA",
    keyCurve = "P-256",
    keyValue = "AQID",
    rpId = "example.com",
    rpName = null,
    counter = 0,
    userHandle = null,
    userName = null,
    userDisplayName = null,
    discoverable = true,
    creationDate = Instant.fromEpochSeconds(0),
    prfSecret = prfSecret,
)

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"

private val LARGE_PRIVATE_KEY = "P".repeat(5_000)
private val LARGE_PUBLIC_KEY = "Q".repeat(4_000)
private val LARGE_CERTIFICATE = "C".repeat(5_000)

private val EXPECTED_EXPORT =
    """{"encrypted":false,"items":[{"id":"gpg","folderId":null,""" +
    """"organizationId":null,"collectionIds":null,"name":"gpg",""" +
    """"notes":"","favorite":false,"reprompt":0,"revisionDate":"2024-01-01T00:00:00Z",""" +
    """"creationDate":"2024-01-01T00:00:00Z","archivedDate":null,""" +
    """"deletedDate":null,"tags":["work","keys"],"type":2,"fields":[{"type":1,""" +
    """"name":"keyguard.gpg.private_key.armored","value":"PRIVATE"},""" +
    """{"type":0,"name":"keyguard.gpg.public_key.armored","value":"PUBLIC"},""" +
    """{"type":0,"name":"keyguard.gpg.fingerprint","value":"ABCD"},""" +
    """{"type":0,"name":"Custom Icon Name","value":"World"},{"type":0,""" +
    """"name":"Note","value":"hello"},{"type":0,"name":"Custom Icon Name #1",""" +
    """"value":"user icon"},{"type":0,"name":"keyguard.link.1",""" +
    """"value":"keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"}],""" +
    """"passwordHistory":null},{"id":"env","folderId":null,"organizationId":null,""" +
    """"collectionIds":null,"name":"env","notes":"","favorite":false,""" +
    """"reprompt":0,"revisionDate":"2024-01-01T00:00:00Z","creationDate":"2024-01-01T00:00:00Z",""" +
    """"archivedDate":null,"deletedDate":null,"type":2,"fields":[{"type":0,""" +
    """"name":"Custom Icon Name","value":"Warning"},{"type":1,""" +
    """"name":"keyguard.environment.v1.part.1","value":"{\"version\":1,""" +
    """\"variables\":[{\"name\":\"TOKEN\",\"source\":{\"kind\":\"literal\",""" +
    """\"value\":\"value\"}}]}"},{"type":1,"name":"keyguard.environment.v1.sha256",""" +
    """"value":"4e2303b468c5799e26059987142cff8c8cd463c92690905dbd244adfdb130bd8"},""" +
    """{"type":0,"name":"Note","value":"hello"},{"type":0,"name":"keyguard.link.1",""" +
    """"value":"keyguard://cipher/c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"},""" +
    """{"type":0,"name":"keyguard.link.2","value":"keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"}],""" +
    """"passwordHistory":null,"secureNote":{"type":0}},{"id":"ssh",""" +
    """"folderId":null,"organizationId":null,"collectionIds":null,""" +
    """"name":"ssh","notes":"","favorite":false,"reprompt":0,"revisionDate":"2024-01-01T00:00:00Z",""" +
    """"creationDate":"2024-01-01T00:00:00Z","archivedDate":null,""" +
    """"deletedDate":null,"type":5,"fields":[{"type":0,"name":"Custom Icon Name",""" +
    """"value":"TerminalEncrypted"},{"type":0,"name":"Note","value":"hello"},""" +
    """{"type":0,"name":"keyguard.ssh.certificate",""" +
    """"value":"ssh-ed25519-cert-v01@openssh.com AAAA user@example.com"}],""" +
    """"passwordHistory":null,"sshKey":{"privateKey":"PRIVATE",""" +
    """"publicKey":"ssh-ed25519 AAAA"}},{"id":"login","folderId":null,""" +
    """"organizationId":null,"collectionIds":null,"name":"login",""" +
    """"notes":"","favorite":false,"reprompt":0,"revisionDate":"2024-01-01T00:00:00Z",""" +
    """"creationDate":"2024-01-01T00:00:00Z","archivedDate":null,""" +
    """"deletedDate":null,"tags":["web"],"type":1,"fields":[{"type":0,""" +
    """"name":"Note","value":"hello"},{"type":0,"name":"Tag","value":"field tag"},""" +
    """{"type":1,"name":"keyguard.passkeys","value":"{\"version\":1,""" +
    """\"credentials\":[{\"credentialId\":\"cred-1\",\"prfSecret\":\"b2xk\"},""" +
    """{\"credentialId\":\"cred-2\",\"prfSecret\":\"bmV3\"}]}"},""" +
    """{"type":0,"name":"keyguard.link.1","value":"keyguard://cipher/b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"}],""" +
    """"passwordHistory":null,"login":{"uris":[],"username":"user",""" +
    """"fido2Credentials":[{"credentialId":"cred-1","keyType":"public-key",""" +
    """"keyAlgorithm":"ECDSA","keyCurve":"P-256","keyValue":"AQID",""" +
    """"rpId":"example.com","rpName":null,"counter":"0","discoverable":"true",""" +
    """"creationDate":"1970-01-01T00:00:00Z"},{"credentialId":"cred-2",""" +
    """"keyType":"public-key","keyAlgorithm":"ECDSA","keyCurve":"P-256",""" +
    """"keyValue":"AQID","rpId":"example.com","rpName":null,"counter":"0",""" +
    """"discoverable":"true","creationDate":"1970-01-01T00:00:00Z"}]}},""" +
    """{"id":"large","folderId":null,"organizationId":null,"collectionIds":null,""" +
    """"name":"large","notes":"","favorite":false,"reprompt":0,""" +
    """"revisionDate":"2024-01-01T00:00:00Z","creationDate":"2024-01-01T00:00:00Z",""" +
    """"archivedDate":null,"deletedDate":null,"type":2,"fields":[{"type":1,""" +
    """"name":"keyguard.gpg.private_key.armored","value":"${LARGE_PRIVATE_KEY}"},""" +
    """{"type":0,"name":"keyguard.gpg.public_key.armored","value":"${LARGE_PUBLIC_KEY}"}],""" +
    """"passwordHistory":null,"secureNote":{"type":0}},{"id":"large-ssh",""" +
    """"folderId":null,"organizationId":null,"collectionIds":null,""" +
    """"name":"large-ssh","notes":"","favorite":false,"reprompt":0,""" +
    """"revisionDate":"2024-01-01T00:00:00Z","creationDate":"2024-01-01T00:00:00Z",""" +
    """"archivedDate":null,"deletedDate":null,"type":5,"fields":[{"type":0,""" +
    """"name":"keyguard.ssh.certificate.v1.part.1","value":"${"C".repeat(3_500)}"},""" +
    """{"type":0,"name":"keyguard.ssh.certificate.v1.part.2","value":"${"C".repeat(1_500)}"},""" +
    """{"type":0,"name":"keyguard.ssh.certificate.v1.sha256",""" +
    """"value":"0786f809f430e419f278cc0329e3f4ca155586d5effa584b3feaabb4b323f419"}],""" +
    """"passwordHistory":null,"sshKey":{}},{"id":"gpg-metadata",""" +
    """"folderId":null,"organizationId":null,"collectionIds":null,""" +
    """"name":"gpg-metadata","notes":"","favorite":false,"reprompt":0,""" +
    """"revisionDate":"2024-01-01T00:00:00Z","creationDate":"2024-01-01T00:00:00Z",""" +
    """"archivedDate":null,"deletedDate":null,"type":2,"fields":[{"type":0,""" +
    """"name":"keyguard.gpg.private_key.armored","value":"user value"}],""" +
    """"passwordHistory":null}]}"""
