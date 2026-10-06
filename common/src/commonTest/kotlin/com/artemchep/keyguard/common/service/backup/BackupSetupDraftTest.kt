package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupSetupDraftTest {
    @Test
    fun cancellingAnEditRestoresEverySavedSettingAndFolderGrant() {
        val folder = BackupStoreConfig.Local("file:///backups", FileAccessToken("saved-bookmark"))
        val saved = BackupConfig(
            enabled = true,
            store = folder,
            password = Password("saved-encryption-password"),
            includeAttachments = true,
            intervalMs = 1234,
            retention = BackupRetention(60),
        )
        val draft = BackupSetupDraft(saved)
        draft.setStoreKind(BackupStoreKind.WebDav)
        draft.setWebDav("https://example.com/backups", "test", "changed")
        draft.setPassword("replacement")
        draft.setIncludeAttachments(false)
        draft.setRetention(5)

        // Editing never mutates the previously saved value.
        assertEquals(folder, saved.store)
        assertEquals(BackupRetention(60), saved.retention)
        draft.reset(saved)

        assertEquals(saved, draft.config)
        draft.setStoreKind(BackupStoreKind.WebDav)
        assertEquals(BackupStoreConfig.WebDav(), draft.config.store)
        draft.setStoreKind(BackupStoreKind.Local)
        assertEquals(folder, draft.config.store)
    }

    @Test
    fun blankWebDavPasswordKeepsTheSavedCredentialOnlyForTheSameAccount() {
        val saved = BackupStoreConfig.WebDav(
            url = "https://example.com/backups",
            username = "test",
            password = Password("saved-password"),
        )
        val draft = BackupSetupDraft(BackupConfig(store = saved))
        draft.setWebDav(" https://example.com/backups ", " test ", "")
        assertEquals(saved, draft.config.store)

        draft.setWebDav("https://other.example/backups", "test", "")
        assertNull((draft.config.store as BackupStoreConfig.WebDav).password)
        draft.reset(BackupConfig(store = saved))
        draft.setWebDav("https://example.com/backups", "another-user", "")
        assertNull((draft.config.store as BackupStoreConfig.WebDav).password)
    }

    @Test
    fun returningToTheSavedWebDavAccountRestoresItsCredentialAfterFailedEdits() {
        val saved = BackupStoreConfig.WebDav(
            url = "https://original.example/backups",
            username = "original-user",
            password = Password("original-password"),
        )
        val draft = BackupSetupDraft(BackupConfig(store = saved))
        val originalUrl = requireNotNull(saved.url)
        val originalUsername = requireNotNull(saved.username)
        draft.setWebDav("https://other.example/backups", "other-user", "")
        assertNull((draft.config.store as BackupStoreConfig.WebDav).password)

        draft.setWebDav(originalUrl, originalUsername, "")
        assertEquals(saved, draft.config.store)

        draft.setWebDav(originalUrl, "different-user", "")
        assertNull((draft.config.store as BackupStoreConfig.WebDav).password)
        draft.reset(BackupConfig())
        draft.setWebDav(originalUrl, originalUsername, "")
        assertNull((draft.config.store as BackupStoreConfig.WebDav).password)
    }

    @Test
    fun destinationChoicesPreserveEachDraftUntilTheEditorIsReset() {
        val folder = BackupStoreConfig.Local("file:///backups", FileAccessToken("saved-bookmark"))
        val draft = BackupSetupDraft(BackupConfig(store = folder))
        draft.setStoreKind(BackupStoreKind.WebDav)
        draft.setWebDav("https://example.com/backups", "test", "password")
        val webDav = draft.config.store
        draft.setStoreKind(BackupStoreKind.Local)
        assertEquals(folder, draft.config.store)
        draft.setStoreKind(BackupStoreKind.WebDav)
        assertEquals(webDav, draft.config.store)
    }

    @Test
    fun editingWizardFieldsPreservesConfigurationOutsideTheWizard() {
        val draft = BackupSetupDraft(BackupConfig(intervalMs = 1234))
        draft.setPassword("password")
        draft.setIncludeAttachments(false)
        draft.setRetention(90)

        assertEquals(1234L, draft.config.intervalMs)
        assertEquals(BackupRetention(90), draft.config.retention)
        draft.setPassword("")
        assertNull(draft.config.password)
    }

    @Test
    fun restoringTheSavedPasswordKeepsOtherWizardEdits() {
        val saved = BackupConfig(password = Password("original-password"))
        val draft = BackupSetupDraft(saved)
        draft.setStoreKind(BackupStoreKind.WebDav)
        draft.setWebDav("https://example.com/backups", "test", "webdav-password")
        draft.setIncludeAttachments(false)
        draft.setRetention(90)
        val expected = draft.config
        draft.setPassword("replacement-password")

        draft.restorePassword(saved)

        assertEquals(expected, draft.config)
        draft.restorePassword(BackupConfig())
        assertNull(draft.config.password)
        assertEquals(expected.copy(password = null), draft.config)
    }

    @Test
    fun retentionUsesSupportedBoundsWithoutChangingTheSavedConfiguration() {
        val saved = BackupConfig(retention = BackupRetention(60))
        val draft = BackupSetupDraft(saved)
        draft.setRetention(-1)
        assertEquals(BackupRetention(0), draft.config.retention)
        draft.setRetention(Int.MAX_VALUE)
        assertEquals(BackupRetention(365), draft.config.retention)
        assertEquals(BackupRetention(60), saved.retention)
    }

    @Test
    fun blankS3SecretKeepsTheSavedKeyOnlyForTheSameAccount() {
        val saved = BackupStoreConfig.S3(
            endpoint = "https://minio.lan:9000",
            region = "eu-west-1",
            bucket = "backups",
            prefix = "keyguard/",
            accessKeyId = "AKID",
            secretAccessKey = Password("saved-secret"),
            pathStyle = true,
        )
        val draft = BackupSetupDraft(BackupConfig(store = saved))
        draft.setS3(" https://minio.lan:9000/ ", "eu-west-1", " backups ", "keyguard", " AKID ", "", true)
        assertEquals(saved, draft.config.store)

        draft.setS3("https://minio.lan:9000", "eu-west-1", "other", "keyguard/", "AKID", "", true)
        assertNull((draft.config.store as BackupStoreConfig.S3).secretAccessKey)
        draft.setS3("https://minio.lan:9000", "eu-west-1", "backups", "keyguard/", "OTHER", "", true)
        assertNull((draft.config.store as BackupStoreConfig.S3).secretAccessKey)

        draft.setS3("https://minio.lan:9000", "eu-west-1", "backups", "keyguard/", "AKID", "", true)
        assertEquals(saved, draft.config.store)
        draft.setS3("https://minio.lan:9000", "", "backups", "", "AKID", "new-secret", false)
        assertEquals(
            BackupStoreConfig.S3(
                endpoint = "https://minio.lan:9000",
                bucket = "backups",
                accessKeyId = "AKID",
                secretAccessKey = Password("new-secret"),
                pathStyle = false,
            ),
            draft.config.store,
        )
    }

    @Test
    fun keepingTheS3SecretAgreesWithSetS3() {
        val saved = BackupStoreConfig.S3(
            endpoint = "https://minio.lan:9000",
            bucket = "backups",
            accessKeyId = "AKID",
            secretAccessKey = Password("saved-secret"),
        )
        val draft = BackupSetupDraft(BackupConfig(store = saved))

        assertTrue(draft.keepsS3SecretAccessKey(" https://minio.lan:9000/ ", " backups ", " AKID "))
        assertFalse(draft.keepsS3SecretAccessKey("https://minio.lan:9000", "other", "AKID"))
        draft.setS3("https://minio.lan:9000", "", "other", "", "AKID", "", true)
        assertFalse(draft.keepsS3SecretAccessKey("https://minio.lan:9000", "other", "AKID"))
        // The saved account keeps its key after a detour through another one.
        assertTrue(draft.keepsS3SecretAccessKey("https://minio.lan:9000", "backups", "AKID"))
    }

    @Test
    fun switchingDestinationsKeepsTheS3Draft() {
        val draft = BackupSetupDraft(BackupConfig())
        draft.setStoreKind(BackupStoreKind.S3)
        draft.setS3("", "", "backups", "", "AKID", "secret", true)
        val s3 = draft.config.store
        draft.setStoreKind(BackupStoreKind.WebDav)
        draft.setStoreKind(BackupStoreKind.S3)
        assertEquals(s3, draft.config.store)
    }
}
