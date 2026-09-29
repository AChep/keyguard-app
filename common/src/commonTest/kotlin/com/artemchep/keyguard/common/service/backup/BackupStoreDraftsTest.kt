package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
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

        val webDav = drafts.select(folder, BackupStoreKind.WebDav)

        assertEquals(BackupStoreConfig.WebDav(), webDav)
        assertSame(folder, drafts.select(webDav, BackupStoreKind.Local))
    }

    @Test
    fun switchingAwayAndBackKeepsWebDavCredentials() {
        val drafts = BackupStoreDrafts()
        val webDav = BackupStoreConfig.WebDav(
            url = "https://example.com/backups",
            username = "test-user",
            password = Password("test-password"),
        )

        val folder = drafts.select(webDav, BackupStoreKind.Local)

        assertEquals(BackupStoreConfig.Local(), folder)
        assertSame(webDav, drafts.select(folder, BackupStoreKind.WebDav))
    }

    @Test
    fun selectingTheCurrentKindKeepsTheCurrentValue() {
        val drafts = BackupStoreDrafts()
        val folder = BackupStoreConfig.Local(
            path = "file:///backups",
            accessToken = FileAccessToken("test-bookmark"),
        )
        val webDav = BackupStoreConfig.WebDav(url = "https://example.com/backups")

        assertSame(folder, drafts.select(folder, BackupStoreKind.Local))
        assertSame(webDav, drafts.select(webDav, BackupStoreKind.WebDav))
    }

    @Test
    fun completedEditsReplaceOnlyTheirOwnDestinationDraft() {
        val drafts = BackupStoreDrafts()
        val folder = BackupStoreConfig.Local(
            path = "file:///original",
            accessToken = FileAccessToken("original-bookmark"),
        )
        drafts.select(folder, BackupStoreKind.WebDav)
        val webDav = BackupStoreConfig.WebDav(
            url = "https://example.com/backups",
            password = Password("test-password"),
        )
        drafts.select(webDav, BackupStoreKind.Local)
        val replacementFolder = folder.copy(
            path = "file:///replacement",
            accessToken = FileAccessToken("replacement-bookmark"),
        )

        assertSame(webDav, drafts.select(replacementFolder, BackupStoreKind.WebDav))
        assertSame(replacementFolder, drafts.select(webDav, BackupStoreKind.Local))
    }
}
