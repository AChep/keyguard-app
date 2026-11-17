package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.model.UrlBlockScope
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.blocksFill
import com.artemchep.keyguard.common.usecase.blocksSave
import com.artemchep.keyguard.common.usecase.matching
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class BlockedUrlCheckImplTest {
    private val equivalentDomains = EquivalentDomains(
        domains = mapOf("example.com" to listOf("example.com", "example.net")),
    )

    @Test
    fun `delegates block mode match type to cipher url check`() = runTest {
        val fakeCipherUrlCheck = RecordingBlockedCipherUrlCheck(result = true)
        val blockedUrlCheck = BlockedUrlCheckImpl(fakeCipherUrlCheck)

        val result = blockedUrlCheck(
            uri = block(
                uri = "login.example.com",
                mode = MatchDetection.Host,
            ),
            url = "https://login.example.com/path",
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertTrue(result)
        assertEquals(DSecret.Uri("login.example.com", DSecret.Uri.MatchType.Host), fakeCipherUrlCheck.uri)
        assertEquals("https://login.example.com/path", fakeCipherUrlCheck.url)
        assertEquals(DSecret.Uri.MatchType.Domain, fakeCipherUrlCheck.defaultMatchDetection)
        assertEquals(equivalentDomains, fakeCipherUrlCheck.equivalentDomains)
    }

    @Test
    fun `default block mode delegates null match type`() = runTest {
        val fakeCipherUrlCheck = RecordingBlockedCipherUrlCheck(result = true)
        val blockedUrlCheck = BlockedUrlCheckImpl(fakeCipherUrlCheck)

        blockedUrlCheck(
            uri = block(
                uri = "example.com",
                mode = MatchDetection.Default,
            ),
            url = "https://login.example.com/path",
            defaultMatchDetection = DSecret.Uri.MatchType.Host,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertEquals(DSecret.Uri("example.com", null), fakeCipherUrlCheck.uri)
        assertEquals(DSecret.Uri.MatchType.Host, fakeCipherUrlCheck.defaultMatchDetection)
    }

    @Test
    fun `a rule of saving only blocks saving, not autofill`() {
        val all = block(uri = "example.com", mode = MatchDetection.Domain)
        assertTrue(all.blocksFill)
        assertTrue(all.blocksSave)
        val save = all.copy(scope = UrlBlockScope.SAVE)
        assertFalse(save.blocksFill)
        assertTrue(save.blocksSave)
        // A rule that is off blocks nothing.
        for (rule in listOf(all, save).map { it.copy(enabled = false) }) {
            assertFalse(rule.blocksFill)
            assertFalse(rule.blocksSave)
        }
    }

    @Test
    fun `matching keeps the enabled rules that match any of the urls`() = runTest {
        val blockedUrlCheck = BlockedUrlCheckImpl(PrefixCipherUrlCheck)
        val app = block(uri = "androidapp://com.example", mode = MatchDetection.Domain)
        val web = block(uri = "https://example.com", mode = MatchDetection.Domain)
            .copy(scope = UrlBlockScope.SAVE)
        val off = web.copy(enabled = false)
        val other = block(uri = "https://example.org", mode = MatchDetection.Domain)

        val matching = blockedUrlCheck.matching(
            rules = listOf(app, web, off, other),
            urls = listOf("androidapp://com.example", "https://example.com/sign-in"),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        )

        assertEquals(listOf(app, web), matching)
    }

    private fun block(
        uri: String,
        mode: MatchDetection,
    ) = DGlobalUrlBlock(
        id = "block-id",
        name = "Block",
        description = "",
        uri = uri,
        mode = mode,
        createdDate = Instant.fromEpochMilliseconds(0),
        enabled = true,
        exposed = true,
    )
}

/** Matches a URL that starts with the URI. */
private object PrefixCipherUrlCheck : CipherUrlCheck {
    override fun invoke(
        uri: DSecret.Uri,
        url: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = {
        url.startsWith(uri.uri)
    }
}

private class RecordingBlockedCipherUrlCheck(
    private val result: Boolean,
) : CipherUrlCheck {
    var uri: DSecret.Uri? = null
    var url: String? = null
    var defaultMatchDetection: DSecret.Uri.MatchType? = null
    var equivalentDomains: EquivalentDomains? = null

    override fun invoke(
        uri: DSecret.Uri,
        url: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = {
        this.uri = uri
        this.url = url
        this.defaultMatchDetection = defaultMatchDetection
        this.equivalentDomains = equivalentDomains
        result
    }
}
