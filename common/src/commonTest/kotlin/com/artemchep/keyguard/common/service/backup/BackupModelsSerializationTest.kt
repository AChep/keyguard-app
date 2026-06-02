package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BackupModelsSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `backup config can run without password`() {
        assertEquals(
            true,
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(
                    path = "/tmp/keyguard-backups",
                ),
                password = null,
            ).canRun(),
        )
        assertEquals(
            false,
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(),
                password = Password("password"),
            ).canRun(),
        )
        assertEquals(
            true,
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.WebDav(
                    url = "https://example.com/dav/",
                ),
            ).canRun(),
        )
        assertEquals(
            false,
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.WebDav(
                    url = "",
                ),
            ).canRun(),
        )
    }

    @Test
    fun `backup config network requirement follows store and attachments`() {
        assertEquals(
            false,
            BackupConfig(
                store = BackupStoreConfig.Local(),
                includeAttachments = false,
            ).requiresNetwork(),
        )
        assertEquals(
            true,
            BackupConfig(
                store = BackupStoreConfig.Local(),
                includeAttachments = true,
            ).requiresNetwork(),
        )
        assertEquals(
            true,
            BackupConfig(
                store = BackupStoreConfig.WebDav(),
                includeAttachments = false,
            ).requiresNetwork(),
        )
    }

    @Test
    fun `backup config password round trips as json string`() {
        val model = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.Local(
                path = "/tmp/keyguard-backups",
            ),
            password = Password("super-secret"),
        )
        val encoded = json.encodeToString(model)
        val passwordElement = json.parseToJsonElement(encoded)
            .jsonObject
            .getValue("password")
            .jsonPrimitive

        assertEquals(true, passwordElement.isString)
        assertEquals("super-secret", passwordElement.content)
        assertEquals(
            model,
            json.decodeFromString<BackupConfig>(encoded),
        )
    }

    @Test
    fun `backup config web dav settings round trip through json`() {
        val model = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.WebDav(
                url = "https://example.com/dav/",
                username = "alice",
                password = Password("secret"),
            ),
        )
        val encoded = json.encodeToString(model)
        val root = json.parseToJsonElement(encoded)
            .jsonObject
        val store = root
            .getValue("store")
            .jsonObject

        assertEquals("web_dav", store.getValue("type").jsonPrimitive.content)
        assertEquals("https://example.com/dav/", store.getValue("url").jsonPrimitive.content)
        assertEquals(
            "secret",
            store
                .getValue("password")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            model,
            json.decodeFromString<BackupConfig>(encoded),
        )
    }

    @Test
    fun `backup config never clear retention round trips through json`() {
        val model = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.Local(
                path = "/tmp/keyguard-backups",
            ),
            retention = BackupRetention(
                maxSnapshots = BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS,
            ),
        )
        val encoded = json.encodeToString(model)
        val root = json.parseToJsonElement(encoded)
            .jsonObject

        assertEquals(
            BackupRetention.NEVER_CLEAR_MAX_SNAPSHOTS.toString(),
            root
                .getValue("retention")
                .jsonObject
                .getValue("maxSnapshots")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            model,
            json.decodeFromString<BackupConfig>(encoded),
        )
    }

    @Test
    fun `backup password toString is redacted`() {
        val secret = "super-secret"
        val password = Password(secret)
        val webDavPassword = Password(secret)
        val config = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.WebDav(
                url = "https://example.com/dav/",
                username = "alice",
                password = webDavPassword,
            ),
            password = password,
        )

        assertFalse(password.toString().contains(secret))
        assertFalse(webDavPassword.toString().contains(secret))
        assertFalse(config.toString().contains(secret))
    }

    @Test
    fun `repository metadata round trips through json`() {
        val model = BackupRepositoryMetadata(
            repoId = "repo-1",
            createdAt = Instant.fromEpochMilliseconds(1L),
        )

        assertEquals(
            model,
            json.decodeFromString<BackupRepositoryMetadata>(
                json.encodeToString(model),
            ),
        )
    }

    @Test
    fun `index round trips through json`() {
        val now = Instant.fromEpochMilliseconds(2L)
        val encryption = BackupObjectEncryption(
            method = BackupObjectEncryptionMethod.ZipAes256,
            keyBase64 = "object-key",
        )
        val model = BackupIndex(
            indexId = "index-1",
            generation = 7L,
            parentIndexIds = setOf("index-0"),
            updatedAt = now,
            snapshots = mapOf(
                "snapshot-1" to BackupIndexSnapshot(
                    path = "snapshots/snapshot-1.zip",
                    createdAt = now,
                    vaultSize = 123L,
                    blobIds = setOf("blob-1"),
                    encryption = encryption,
                    stats = BackupSnapshotStats(
                        cipherCount = 1,
                        attachmentCount = 1,
                        newBlobCount = 1,
                        reusedBlobCount = 0,
                    ),
                ),
            ),
            attachments = mapOf(
                "fingerprint-1" to BackupIndexAttachment(
                    blobId = "blob-1",
                    plainSize = 12L,
                    createdAt = now,
                    lastSeenAt = now,
                ),
            ),
            blobs = mapOf(
                "blob-1" to BackupIndexBlob(
                    path = "blobs/bl/ob/blob-1.zip",
                    plainSize = 12L,
                    encryptedSize = 34L,
                    createdAt = now,
                    lastSeenAt = now,
                    lastValidatedAt = now,
                    encryption = encryption,
                ),
            ),
        )

        assertEquals(
            model,
            json.decodeFromString<BackupIndex>(
                json.encodeToString(model),
            ),
        )
    }

    @Test
    fun `index blob decodes missing validation timestamp as null`() {
        val encoded = """
            {
              "blobs": {
                "blob-1": {
                  "path": "blobs/bl/ob/blob-1.zip",
                  "plainSize": 12,
                  "encryptedSize": 34,
                  "createdAt": "1970-01-01T00:00:00.002Z",
                  "lastSeenAt": "1970-01-01T00:00:00.002Z"
                }
              }
            }
        """.trimIndent()

        val model = json.decodeFromString<BackupIndex>(encoded)

        assertEquals(
            null,
            model.blobs.getValue("blob-1").lastValidatedAt,
        )
    }

    @Test
    fun `snapshot manifest round trips through json`() {
        val now = Instant.fromEpochMilliseconds(3L)
        val model = BackupSnapshotManifest(
            snapshotId = "snapshot-1",
            createdAt = now,
            options = BackupSnapshotOptions(
                includeAttachments = true,
            ),
            vault = BackupSnapshotVault(
                size = 123L,
            ),
            attachments = listOf(
                BackupSnapshotAttachment(
                    accountId = "account-1",
                    localCipherId = "local-cipher-1",
                    remoteCipherId = "remote-cipher-1",
                    attachmentId = "attachment-1",
                    fileName = "file.txt",
                    plainSize = 12L,
                    fingerprint = "fingerprint-1",
                    blobId = "blob-1",
                    blobPath = "blobs/bl/ob/blob-1.zip",
                    exportPath = "attachments/attachment-1/file.txt",
                ),
            ),
            stats = BackupSnapshotStats(
                cipherCount = 1,
                attachmentCount = 1,
                newBlobCount = 1,
                reusedBlobCount = 0,
            ),
        )

        assertEquals(
            model,
            json.decodeFromString<BackupSnapshotManifest>(
                json.encodeToString(model),
            ),
        )
    }

    @Test
    fun `backup status round trips with no current run`() {
        val model = BackupStatus(
            lastStartedAt = Instant.fromEpochMilliseconds(4L),
            lastFinishedAt = Instant.fromEpochMilliseconds(5L),
            lastSnapshotId = "snapshot-1",
            changeGeneration = 2L,
            lastChangedAt = Instant.fromEpochMilliseconds(6L),
            lastSuccessfulBackupAt = Instant.fromEpochMilliseconds(7L),
            lastSuccessfulBackupChangeGeneration = 2L,
            currentRun = null,
        )

        assertEquals(
            model,
            json.decodeFromString<BackupStatus>(
                json.encodeToString(model),
            ),
        )
    }

    @Test
    fun `old backup status json decodes with no current run`() {
        val model = json.decodeFromString<BackupStatus>(
            """
            {
              "lastSkippedReason": "vault_locked"
            }
            """.trimIndent(),
        )

        assertEquals("vault_locked", model.lastSkippedReason)
        assertEquals(null, model.currentRun)
    }

    @Test
    fun `legacy backup status json decodes with missing optional fields`() {
        val model = json.decodeFromString<BackupStatus>(
            """
            {
              "lastStartedAt": "1970-01-01T00:00:00.004Z",
              "lastFinishedAt": "1970-01-01T00:00:00.005Z",
              "lastSnapshotId": "snapshot-1"
            }
            """.trimIndent(),
        )

        assertEquals(Instant.fromEpochMilliseconds(4L), model.lastStartedAt)
        assertEquals(Instant.fromEpochMilliseconds(5L), model.lastFinishedAt)
        assertEquals("snapshot-1", model.lastSnapshotId)
        assertEquals(null, model.lastSkippedReason)
        assertEquals(null, model.lastErrorMessage)
        assertEquals(null, model.lastStats)
        assertEquals(0L, model.changeGeneration)
        assertEquals(null, model.lastChangedAt)
        assertEquals(null, model.lastSuccessfulBackupAt)
        assertEquals(0L, model.lastSuccessfulBackupChangeGeneration)
        assertEquals(null, model.currentRun)
    }
}
