package com.artemchep.keyguard.apple.model

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UriOverridesTest {
    @Test
    fun overridesKeepTheirFieldsAndOwnActions() = runTest {
        val invoked = mutableListOf<String>()
        fun copy(name: String) = FlatItemAction(
            id = "copy",
            title = TextHolder.Value("Copy"),
            type = FlatItemAction.Type.COPY,
            onClick = { invoked += name },
        )
        val handlers = mutableMapOf<String, () -> Unit>()
        val item = VaultViewItem.Uri(
            id = "uri",
            title = AnnotatedString("https://example.com"),
            icon = {},
            dropdown = listOf(copy("uri")),
            overrides = listOf(
                VaultViewItem.Uri.Override(
                    title = "Open in Firefox",
                    text = "firefox https://example.com",
                    error = false,
                    dropdown = listOf(copy("ok")),
                ),
                VaultViewItem.Uri.Override(
                    title = "Broken",
                    text = "Unknown placeholder",
                    error = true,
                    dropdown = listOf(ContextItem.Custom {}),
                ),
            ),
        )
        val snapshot = buildVaultItemSnapshots(
            items = listOf(item),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = handlers,
        ).single()

        val (ok, broken) = snapshot.uriOverrides
        assertEquals("Open in Firefox", ok.title)
        assertEquals("firefox https://example.com", ok.text)
        assertFalse(ok.error)
        assertTrue(ok.actions.single().isCopy)
        assertEquals("Broken", broken.title)
        assertEquals("Unknown placeholder", broken.text)
        assertTrue(broken.error)
        assertEquals(emptyList(), broken.actions)

        // The override's action shares a producer id with the URI's own, yet
        // both stay reachable.
        val uriActionId = snapshot.actions.single().id
        val okActionId = ok.actions.single().id
        assertTrue(uriActionId != okActionId)
        handlers.getValue(okActionId).invoke()
        handlers.getValue(uriActionId).invoke()
        assertEquals(listOf("ok", "uri"), invoked)
    }

    @Test
    fun uriWithoutOverridesHasNone() = runTest {
        val snapshot = buildVaultItemSnapshots(
            items = listOf(
                VaultViewItem.Uri(id = "uri", title = AnnotatedString("https://example.com"), icon = {}),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = mutableMapOf(),
        ).single()
        assertEquals(emptyList(), snapshot.uriOverrides)
    }
}
