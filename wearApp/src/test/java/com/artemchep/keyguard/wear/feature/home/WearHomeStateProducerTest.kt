package com.artemchep.keyguard.wear.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.home_favorites_label
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WearHomeStateProducerTest {
    @Test
    fun `favorites action is exposed as a header item`() {
        val content = createWearHomeContentSpec(
            ciphers = emptyList(),
            folders = emptyList(),
        )

        assertEquals(
            "preset.favorites",
            content.headerItem?.id,
        )
        assertEquals(
            TextHolder.Res(Res.string.home_favorites_label),
            content.headerItem?.title,
        )
        assertEquals(
            Icons.Outlined.Star,
            content.headerItem?.icon,
        )
        assertEquals(
            listOf(
                "section.other",
                "home",
                "send",
                "generator",
                "settings",
            ),
            content.items.map { it.id },
        )
    }

    @Test
    fun `folder section is omitted when there is one non-empty folder`() {
        val content = createWearHomeContentSpec(
            ciphers = listOf(
                createSecret(
                    id = "cipher-1",
                    accountId = "account-1",
                    folderId = "folder-1",
                ),
            ),
            folders = listOf(
                createFolder(
                    id = "folder-1",
                    accountId = "account-1",
                    name = "Folder 1",
                ),
            ),
        )

        assertEquals(
            listOf(
                "section.other",
                "home",
                "send",
                "generator",
                "settings",
            ),
            content.items.map { it.id },
        )
    }

    @Test
    fun `folder presets include only non-empty folders when more than one qualifies`() {
        val content = createWearHomeContentSpec(
            ciphers = listOf(
                createSecret(
                    id = "cipher-1",
                    accountId = "account-1",
                    folderId = "folder-b",
                ),
                createSecret(
                    id = "cipher-2",
                    accountId = "account-1",
                    folderId = "folder-a",
                ),
            ),
            folders = listOf(
                createFolder(
                    id = "folder-a",
                    accountId = "account-1",
                    name = "Alpha",
                ),
                createFolder(
                    id = "folder-b",
                    accountId = "account-1",
                    name = "Beta",
                ),
                createFolder(
                    id = "folder-c",
                    accountId = "account-1",
                    name = "Gamma",
                ),
            ),
        )

        assertEquals(
            listOf(
                "section.folders",
                "preset.folder.account-1.folder-a",
                "preset.folder.account-1.folder-b",
                "section.other",
                "home",
                "send",
                "generator",
                "settings",
            ),
            content.items.map { it.id },
        )
    }

    private fun createFolder(
        id: String,
        accountId: String,
        name: String,
    ) = DFolder(
        id = id,
        accountId = accountId,
        revisionDate = TEST_INSTANT,
        service = BitwardenService(),
        deleted = false,
        synced = true,
        name = name,
    )

    private fun createSecret(
        id: String,
        accountId: String,
        folderId: String?,
    ) = DSecret(
        id = id,
        accountId = accountId,
        folderId = folderId,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = id,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.Login,
        login = null,
        card = null,
        identity = null,
    )

    private companion object {
        private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
    }
}
