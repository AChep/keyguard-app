package com.artemchep.keyguard.apple.model

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.iconRequestOrNull
import com.artemchep.keyguard.feature.favicon.toIconKey
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UriIconsTest {
    @Test
    fun websiteKeepsItsAccountAndPreservesTheUriAndActions() = runTest {
        val original = "https://example.com/path?q=1#fragment"
        val source = VaultUriIcon.Website(FaviconUrl("account", original), enabled = true)
        var copied: String? = null
        var calls = 0
        val handlers = mutableMapOf<String, () -> Unit>()
        val item = uri("website", source).copy(
            title = AnnotatedString(original),
            text = "Existing subtitle",
            dropdown = listOf(
                FlatItemAction(
                    title = TextHolder.Value("Copy URL"),
                    type = FlatItemAction.Type.COPY,
                    onClick = {
                        copied = original
                        calls++
                    },
                ),
            ),
        )
        val snapshot = buildVaultItemSnapshots(
            listOf(item), null, LeContext(), handlers,
        ).single()
        assertEquals(original, snapshot.title)
        assertEquals(item.text, snapshot.text)
        assertEquals(UriIconKind.WEBSITE, snapshot.uriIcon?.kind)
        // The icon loader resolves the account's icon server itself, so
        // the snapshot keeps the account; pages of a website share the key.
        assertEquals(
            FaviconUrl("account", "https://example.com"),
            snapshot.uriIcon?.iconKey?.let(::iconRequestOrNull),
        )
        assertEquals(0, calls)
        assertTrue(snapshot.actions.single().isCopy)
        handlers.getValue(snapshot.actions.single().id).invoke()
        assertEquals(original, copied)
        assertEquals(1, calls)

        assertNull(source.copy(enabled = false).toUriIconSnapshot().iconKey)
    }

    @Test
    fun appAndGenericMetadataSurvivesProjectionWithoutChangingRegexColoring() = runTest {
        val ios = VaultUriIcon.IosApp("com.example.app", enabled = true)
        val android = VaultUriIcon.AndroidApp("com.example.app", enabled = true)
        val pattern = "^https://example\\.com/[0-9]+$"
        val snapshots = buildVaultItemSnapshots(
            items = listOf(
                uri("ios", ios),
                uri("android", android),
                uri("disabled", ios.copy(enabled = false)),
                uri("regex", null).copy(title = AnnotatedString(pattern), colorize = true),
                VaultViewItem.Value(id = "ordinary", title = "Username", value = "demo"),
            ),
            notesText = null,
            leContext = LeContext(),
            actionHandlers = mutableMapOf(),
        )
        assertEquals(
            UriIconSnapshot(UriIconKind.APP, IosAppIconUrl("com.example.app").toIconKey()),
            snapshots[0].uriIcon,
        )
        assertEquals(
            UriIconSnapshot(UriIconKind.APP, AppIconUrl("com.example.app").toIconKey()),
            snapshots[1].uriIcon,
        )
        assertEquals(UriIconSnapshot(UriIconKind.APP, null), snapshots[2].uriIcon)
        assertEquals(UriIconSnapshot(UriIconKind.LINK, null), snapshots[3].uriIcon)
        assertEquals(pattern, snapshots[3].title)
        assertTrue(snapshots[3].colorize)
        assertNull(snapshots[4].uriIcon)
    }

    private fun uri(id: String, source: VaultUriIcon?) = VaultViewItem.Uri(
        id = id,
        title = AnnotatedString(id),
        icon = {},
        iconSource = source,
    )
}
