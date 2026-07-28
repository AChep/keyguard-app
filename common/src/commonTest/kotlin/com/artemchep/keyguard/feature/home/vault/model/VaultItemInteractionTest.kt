package com.artemchep.keyguard.feature.home.vault.model

import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultItemInteractionTest {
    @Test
    fun `empty shared state produces an idle item`() {
        val state = VaultItem2.Item.SharedState(
            selectedIds = persistentSetOf(),
            openedId = null,
        )

        assertEquals(
            VaultItem2.Item.SharedItemState(
                selecting = false,
                selected = false,
                opened = false,
            ),
            state.forItem("item"),
        )
    }

    @Test
    fun `shared state derives selection and opened flags by interaction id`() {
        val state = VaultItem2.Item.SharedState(
            selectedIds = persistentSetOf("selected"),
            openedId = "opened",
        )

        assertEquals(
            VaultItem2.Item.SharedItemState(
                selecting = true,
                selected = true,
                opened = false,
            ),
            state.forItem("selected"),
        )
        assertEquals(
            VaultItem2.Item.SharedItemState(
                selecting = true,
                selected = false,
                opened = true,
            ),
            state.forItem("opened"),
        )
        assertEquals(
            VaultItem2.Item.SharedItemState(
                selecting = true,
                selected = false,
                opened = false,
            ),
            state.forItem("other"),
        )
    }
}
