package com.artemchep.keyguard.apple.model

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultItemColorizeTest {
    @Test
    fun copyKeepsTheOriginalValueAndHandler() = runTest {
        val original = "e\u0301•9!🔐"
        var copied: String? = null
        val handlers = mutableMapOf<String, () -> Unit>()
        val snapshots = buildVaultItemSnapshots(
            items = listOf(
                VaultViewItem.Value(
                    id = "password",
                    title = "Password",
                    value = original,
                    colorize = true,
                    dropdown = listOf(
                        FlatItemAction(
                            title = TextHolder.Value("Copy password"),
                            type = FlatItemAction.Type.COPY,
                            onClick = { copied = original },
                        ),
                    ),
                ),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = handlers,
        )
        val action = snapshots.single().actions.single()
        assertTrue(action.isCopy)
        assertNull(copied)
        handlers.getValue(action.id).invoke()
        assertEquals(original, copied)
    }

    @Test
    fun valuesUseProducerFlagRatherThanTitleOrFont() = runTest {
        val items = listOf(
            VaultViewItem.Value(id = "key", title = "Key", value = "a9!", colorize = true),
            VaultViewItem.Value(id = "ordinary", title = "Password", value = "a9!", monospace = true),
        )
        val snapshots = project(items)
        assertTrue(snapshots[0].colorize)
        assertFalse(snapshots[1].colorize)
        assertEquals(items.map { it.value }, snapshots.map { it.text })
    }

    @Test
    fun regexUriColoringIsForwardedWithoutChangingThePattern() = runTest {
        val pattern = "^https://example\\.com/[0-9]+$"
        val snapshots = project(
            listOf(
                VaultViewItem.Uri(id = "regex", title = AnnotatedString(pattern), icon = {}, colorize = true),
                VaultViewItem.Uri(id = "web", title = AnnotatedString("https://example.com"), icon = {}),
            ),
        )
        assertTrue(snapshots[0].colorize)
        assertEquals(pattern, snapshots[0].title)
        assertFalse(snapshots[1].colorize)
    }

    @Test
    fun colorMetadataDoesNotBypassConcealmentOrPolicy() = runTest {
        val secret = VaultViewItem.Value(
            id = "secret",
            title = "Password",
            value = "synthetic-9!",
            colorize = true,
            visibility = Visibility(concealed = true),
        )
        val locked = secret.copy(id = "locked", visibility = Visibility(concealed = true, hidden = true))
        val requested = mutableListOf<String>()
        suspend fun snapshot(revealed: Set<String>) = buildVaultItemSnapshots(
            items = listOf(secret, locked),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = mutableMapOf(),
            revealedIds = revealed,
            onRequestReveal = { item ->
                requested += item.id
                "reveal:${item.id}"
            },
        )

        val hidden = snapshot(emptySet())
        assertTrue(hidden.all { it.colorize && it.concealed })
        assertTrue(hidden.all { it.text == null && !it.isVisible })
        assertEquals("reveal:secret", hidden[0].revealActionId)
        assertTrue(hidden[1].revealLocked)
        assertNull(hidden[1].revealActionId)

        val revealed = snapshot(setOf("secret", "locked"))
        assertEquals(secret.value, revealed[0].text)
        assertTrue(revealed[0].isVisible)
        assertNull(revealed[1].text)
        assertFalse(revealed[1].isVisible)
        assertEquals(listOf("secret", "secret"), requested)
        assertNull(snapshot(emptySet())[0].text)
    }

    private suspend fun project(items: List<VaultViewItem>) = buildVaultItemSnapshots(
        items = items,
        notesText = null,
        leContext = LeContext(),
        actionHandlers = mutableMapOf(),
    )
}
