package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CipherUrlDuplicateCheckImplTest {
    private val equivalentDomains = EquivalentDomains(
        domains = mapOf("example.com" to listOf("example.com", "example.net")),
    )

    @Test
    fun `never match on either side is not a duplicate`() = runTest {
        val duplicateCheck = CipherUrlDuplicateCheckImpl(RecordingDuplicateCipherUrlCheck(result = true))

        val result = duplicateCheck(
            a = DSecret.Uri("https://example.com", DSecret.Uri.MatchType.Never),
            b = DSecret.Uri("https://example.com", DSecret.Uri.MatchType.Exact),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertNull(result)
    }

    @Test
    fun `identical regular expressions are duplicates without delegation`() = runTest {
        val fakeCipherUrlCheck = RecordingDuplicateCipherUrlCheck(result = false)
        val duplicateCheck = CipherUrlDuplicateCheckImpl(fakeCipherUrlCheck)
        val regexUri = DSecret.Uri("https://example\\.com/.*", DSecret.Uri.MatchType.RegularExpression)

        val result = duplicateCheck(
            a = regexUri,
            b = regexUri,
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertEquals(regexUri, result)
        assertNull(fakeCipherUrlCheck.uri)
    }

    @Test
    fun `different regular expressions are not duplicates without delegation`() = runTest {
        val fakeCipherUrlCheck = RecordingDuplicateCipherUrlCheck(result = true)
        val duplicateCheck = CipherUrlDuplicateCheckImpl(fakeCipherUrlCheck)

        val result = duplicateCheck(
            a = DSecret.Uri("https://example\\.com/.*", DSecret.Uri.MatchType.RegularExpression),
            b = DSecret.Uri("https://example\\.net/.*", DSecret.Uri.MatchType.RegularExpression),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertNull(result)
        assertNull(fakeCipherUrlCheck.uri)
    }

    @Test
    fun `delegated match returns first uri as duplicate`() = runTest {
        val fakeCipherUrlCheck = RecordingDuplicateCipherUrlCheck(result = true)
        val duplicateCheck = CipherUrlDuplicateCheckImpl(fakeCipherUrlCheck)
        val a = DSecret.Uri("login.example.net", DSecret.Uri.MatchType.Host)
        val b = DSecret.Uri("login.example.com", DSecret.Uri.MatchType.Host)

        val result = duplicateCheck(
            a = a,
            b = b,
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertEquals(a, result)
        assertEquals(a, fakeCipherUrlCheck.uri)
        assertEquals(b.uri, fakeCipherUrlCheck.url)
        assertEquals(DSecret.Uri.MatchType.Domain, fakeCipherUrlCheck.defaultMatchDetection)
        assertEquals(equivalentDomains, fakeCipherUrlCheck.equivalentDomains)
    }

    @Test
    fun `delegated miss is not a duplicate`() = runTest {
        val duplicateCheck = CipherUrlDuplicateCheckImpl(RecordingDuplicateCipherUrlCheck(result = false))

        val result = duplicateCheck(
            a = DSecret.Uri("login.example.net", DSecret.Uri.MatchType.Host),
            b = DSecret.Uri("vault.example.com", DSecret.Uri.MatchType.Host),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = equivalentDomains,
        ).bind()

        assertNull(result)
    }
}

private class RecordingDuplicateCipherUrlCheck(
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
