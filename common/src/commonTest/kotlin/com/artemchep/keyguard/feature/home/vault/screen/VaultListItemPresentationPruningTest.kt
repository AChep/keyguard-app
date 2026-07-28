package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.search.createItem
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultListItemPresentationPruningTest {
    @Test
    fun `attachments are removed when only attachment presentation is disabled`() {
        val attachment = DSecret.Attachment.Local(
            id = "attachment-id",
            url = "file:///attachment.txt",
            fileName = "attachment.txt",
        )
        val item = createItem(
            source = createSecret(
                id = "cipher-id",
                attachments = listOf(attachment),
            ),
        ).copy(
            attachments2 = persistentListOf(
                VaultItem2.Item.Attachment(
                    source = attachment,
                    onClick = {},
                ),
            ),
        )
        assertTrue(item.attachments2.isNotEmpty())

        val result = pruneVaultListItemPresentation(
            list = listOf(item),
            keepOtp = true,
            keepPasskey = true,
            keepPassword = true,
            keepAttachment = false,
        )

        val resultItem = assertIs<VaultItem2.Item>(result.single())
        assertEquals(emptyList(), resultItem.attachments2)
    }
}
