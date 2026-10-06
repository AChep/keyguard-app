package com.artemchep.keyguard.apple.model

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.feature.favicon.Favicon
import com.artemchep.keyguard.feature.favicon.FaviconAccountServer
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.model.VaultUriIcon
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UriIconsTest {
    @Test
    fun websiteUsesItsAccountServerAndPreservesTheUriAndActions() = runTest {
        val previous = Favicon.servers
        val transformed = mutableListOf<String>()
        Favicon.servers = listOf(
            FaviconAccountServer("other") { error("Wrong account") },
            FaviconAccountServer("account") { host ->
                transformed += host
                "https://icons.example/$host.png"
            },
        )
        try {
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
            assertEquals(
                UriIconSnapshot(UriIconKind.WEBSITE, "https://icons.example/example.com.png"),
                snapshot.uriIcon,
            )
            assertEquals(listOf("example.com"), transformed)
            assertEquals(0, calls)
            assertTrue(snapshot.actions.single().isCopy)
            handlers.getValue(snapshot.actions.single().id).invoke()
            assertEquals(original, copied)
            assertEquals(1, calls)

            assertNull(source.copy(enabled = false).toUriIconSnapshot(emptyMap()).url)
            assertEquals(1, transformed.size)
            assertNull(source.copy(url = FaviconUrl("missing", original)).toUriIconSnapshot(emptyMap()).url)
            assertNull(source.copy(url = FaviconUrl("account", "http://localhost")).toUriIconSnapshot(emptyMap()).url)
        } finally {
            Favicon.servers = previous
        }
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
            uriAppIcons = mapOf(ios to "https://icons.example/ios.png", android to "https://icons.example/android.png"),
        )
        assertEquals(UriIconSnapshot(UriIconKind.APP, "https://icons.example/ios.png"), snapshots[0].uriIcon)
        assertEquals(UriIconSnapshot(UriIconKind.APP, "https://icons.example/android.png"), snapshots[1].uriIcon)
        assertEquals(UriIconSnapshot(UriIconKind.APP, null), snapshots[2].uriIcon)
        assertEquals(UriIconSnapshot(UriIconKind.LINK, null), snapshots[3].uriIcon)
        assertEquals(pattern, snapshots[3].title)
        assertTrue(snapshots[3].colorize)
        assertNull(snapshots[4].uriIcon)
    }

    @Test
    fun lookupsAreDeduplicatedByPlatformAndCacheFailures() = runTest {
        val ios = VaultUriIcon.IosApp("same.id", enabled = true)
        val android = VaultUriIcon.AndroidApp("same.id", enabled = true)
        val missing = VaultUriIcon.IosApp("missing", enabled = true)
        val sources = MutableStateFlow<Set<VaultUriIcon.App>>(setOf(ios, android, missing))
        val calls = mutableListOf<VaultUriIcon.App>()
        var result = emptyMap<VaultUriIcon.App, String?>()
        backgroundScope.launch {
            sources.resolveUriAppIcons { source ->
                calls += source
                if (source == missing) error("Store unavailable")
                if (source == ios) "ios.png" else "android.png"
            }.collect { result = it }
        }
        runCurrent()
        assertEquals(3, calls.size)
        assertEquals(mapOf(ios to "ios.png", android to "android.png", missing to null), result)
        sources.value = emptySet()
        runCurrent()
        assertTrue(result.isEmpty())
        sources.value = setOf(ios, android, missing)
        runCurrent()
        assertEquals(3, calls.size)
        assertEquals(3, result.size)
    }

    @Test
    fun disabledSourcesNeverLookUpAndCancelPendingWork() = runTest {
        val app = VaultUriIcon.IosApp("example", enabled = false)
        val sources = MutableStateFlow<Set<VaultUriIcon.App>>(setOf(app))
        var started = 0
        var cancelled = 0
        var result = emptyMap<VaultUriIcon.App, String?>()
        val job = backgroundScope.launch {
            sources.resolveUriAppIcons {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }.collect { result = it }
        }
        runCurrent()
        assertEquals(0, started)
        sources.value = setOf(app.copy(enabled = true))
        runCurrent()
        assertEquals(1, started)
        assertTrue(result.isEmpty()) // placeholder is available before the store replies
        sources.value = setOf(app)
        runCurrent()
        assertEquals(1, cancelled)
        assertTrue(result.isEmpty())
        sources.value = setOf(app.copy(enabled = true))
        runCurrent()
        assertEquals(2, started)
        job.cancelAndJoin()
        assertEquals(2, cancelled)
    }

    private fun uri(id: String, source: VaultUriIcon?) = VaultViewItem.Uri(
        id = id,
        title = AnnotatedString(id),
        icon = {},
        iconSource = source,
    )
}
