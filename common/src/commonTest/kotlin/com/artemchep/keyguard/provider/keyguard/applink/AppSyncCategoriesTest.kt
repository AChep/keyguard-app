package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A new kind of record linked apps sync needs a category in [AppSyncCategories.default],
 * unless it is internal to the group; the switch keys stay in the settings of each device,
 * so they never change.
 */
class AppSyncCategoriesTest {
    private val categories = AppSyncCategories
        .default(
            hashOf = { "" },
            received = AppLinkReceivedBlobs(),
        )
        .all

    @Test
    fun `every kind linked apps sync has one category`() {
        assertEquals(
            EntryKind.entries.filter { it.scope == SyncScope.APP && !it.internal }.sortedBy { it.name },
            categories.map { it.kind }.sortedBy { it.name },
        )
    }

    @Test
    fun `a kind without a category syncs whatever the switches say, and has no rows`() {
        // Forwarders stand in for a known kind of another scope: this build has no category for them.
        val all = AppSyncCategories(categories.filterNot { it.kind == EntryKind.EMAIL_FORWARDER })
        val kinds = EntryKind.entries
            .filter { it.internal || it == EntryKind.EMAIL_FORWARDER }
            .map { it.wireName } + "card-reader"
        kinds.forEach { kind ->
            val entry = SyncEntry(id = ObjectKey.ENTRY_ID, kind = kind)
            assertTrue(all.syncs(entry, enabled = emptySet()), kind)
            assertNull(all.forEntry(entry), kind)
        }
        // A kind with a category syncs only while its switch is on.
        val wordlist = SyncEntry(id = ObjectKey.ENTRY_ID, kind = EntryKind.WORDLIST)
        assertFalse(all.syncs(wordlist, enabled = emptySet()))
        assertTrue(all.syncs(wordlist, enabled = setOf(DAppLinkSyncCategory.WORDLISTS)))
    }

    @Test
    fun `every switch has one category`() {
        assertEquals(
            DAppLinkSyncCategory.entries.toList(),
            categories.map { it.category }.sortedBy { it.ordinal },
        )
    }

    @Test
    fun `the switch keys never change`() {
        assertEquals(
            mapOf(
                DAppLinkSyncCategory.EMAIL_FORWARDERS to "emailForwarders",
                DAppLinkSyncCategory.WORDLISTS to "wordlists",
                DAppLinkSyncCategory.URL_OVERRIDES to "urlOverrides",
            ),
            DAppLinkSyncCategory.entries.associateWith { it.key },
        )
    }
}
