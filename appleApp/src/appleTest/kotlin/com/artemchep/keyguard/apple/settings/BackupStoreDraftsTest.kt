package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.file.FileAccessToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BackupStoreDraftsTest {
    @Test
    fun switchingAwayAndBackKeepsFolderAccess() {
        val drafts = BackupStoreDrafts()
        val folder = BackupStoreConfig.Local(
            path = "file:///backups",
            accessToken = FileAccessToken("test-bookmark"),
        )

        val webDav = drafts.select(folder, "webdav")

        assertEquals(BackupStoreConfig.WebDav(), webDav)
        assertSame(folder, drafts.select(webDav, "local"))
    }

    @Test
    fun switchingAwayAndBackKeepsWebDavCredentials() {
        val drafts = BackupStoreDrafts()
        val webDav = BackupStoreConfig.WebDav(
            url = "https://example.com/backups",
            username = "test-user",
            password = Password("test-password"),
        )

        val folder = drafts.select(webDav, "local")

        assertEquals(BackupStoreConfig.Local(), folder)
        assertSame(webDav, drafts.select(folder, "webdav"))
    }

    @Test
    fun selectingTheCurrentKindKeepsTheCurrentValue() {
        val drafts = BackupStoreDrafts()
        val folder = BackupStoreConfig.Local(
            path = "file:///backups",
            accessToken = FileAccessToken("test-bookmark"),
        )
        val webDav = BackupStoreConfig.WebDav(url = "https://example.com/backups")

        assertSame(folder, drafts.select(folder, "local"))
        assertSame(webDav, drafts.select(webDav, "webdav"))
        assertSame(webDav, drafts.select(webDav, "unknown"))
    }

    @Test
    fun completedEditsReplaceOnlyTheirOwnDestinationDraft() {
        val drafts = BackupStoreDrafts()
        val folder = BackupStoreConfig.Local(
            path = "file:///original",
            accessToken = FileAccessToken("original-bookmark"),
        )
        drafts.select(folder, "webdav")
        val webDav = BackupStoreConfig.WebDav(
            url = "https://example.com/backups",
            password = Password("test-password"),
        )
        drafts.select(webDav, "local")
        val replacementFolder = folder.copy(
            path = "file:///replacement",
            accessToken = FileAccessToken("replacement-bookmark"),
        )

        assertSame(webDav, drafts.select(replacementFolder, "webdav"))
        assertSame(replacementFolder, drafts.select(webDav, "local"))
    }
}
