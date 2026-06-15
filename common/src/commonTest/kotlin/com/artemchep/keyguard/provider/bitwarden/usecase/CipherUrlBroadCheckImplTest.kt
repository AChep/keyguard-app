package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherUrlBroadCheckImplTest {
    @Test
    fun `domain uri is broad when equivalent host detection uri exists`() = runTest {
        val broadCheck = CipherUrlBroadCheckImpl(BroadHostOnlyTldService)

        val result = broadCheck(
            ciphers = listOf(
                secret(
                    id = "host-cipher",
                    uris = listOf(DSecret.Uri("login.example.net", DSecret.Uri.MatchType.Host)),
                ),
                secret(
                    id = "domain-cipher",
                    uris = listOf(DSecret.Uri("example.com", DSecret.Uri.MatchType.Domain)),
                ),
            ),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomainsBuilder = equivalentDomainsBuilder(),
        ).bind()

        assertEquals(listOf("example.com|example.com"), result.map { it.value })
    }

    @Test
    fun `host detection full url contributes base domain to broad detection`() = runTest {
        val broadCheck = CipherUrlBroadCheckImpl(BroadHostOnlyTldService)

        val result = broadCheck(
            ciphers = listOf(
                secret(
                    id = "host-cipher",
                    uris = listOf(DSecret.Uri("https://login.example.net/path", DSecret.Uri.MatchType.Host)),
                ),
                secret(
                    id = "domain-cipher",
                    uris = listOf(DSecret.Uri("https://example.com/login", DSecret.Uri.MatchType.Domain)),
                ),
            ),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomainsBuilder = equivalentDomainsBuilder(),
        ).bind()

        assertEquals(listOf("example.com|https://example.com/login"), result.map { it.value })
    }

    @Test
    fun `app protocol uris are ignored for broad detection`() = runTest {
        val broadCheck = CipherUrlBroadCheckImpl(BroadHostOnlyTldService)

        val result = broadCheck(
            ciphers = listOf(
                secret(
                    id = "host-cipher",
                    uris = listOf(DSecret.Uri("androidapp://com.example", DSecret.Uri.MatchType.Host)),
                ),
                secret(
                    id = "domain-cipher",
                    uris = listOf(DSecret.Uri("example.com", DSecret.Uri.MatchType.Domain)),
                ),
            ),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomainsBuilder = equivalentDomainsBuilder(),
        ).bind()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `tld failures are skipped during broad detection`() = runTest {
        val broadCheck = CipherUrlBroadCheckImpl(ThrowingBroadTldService)

        val result = broadCheck(
            ciphers = listOf(
                secret(
                    id = "host-cipher",
                    uris = listOf(DSecret.Uri("broken.example.net", DSecret.Uri.MatchType.Host)),
                ),
                secret(
                    id = "domain-cipher",
                    uris = listOf(DSecret.Uri("example.com", DSecret.Uri.MatchType.Domain)),
                ),
            ),
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomainsBuilder = equivalentDomainsBuilder(),
        ).bind()

        assertTrue(result.isEmpty())
    }

    private fun equivalentDomainsBuilder() = EquivalentDomainsBuilder(
        logRepository = NoopBroadLogRepository,
        sharedAllEquivalentDomains = {
            listOf(
                DEquivalentDomains(
                    id = "equivalent-domains-id",
                    accountId = "account-id",
                    global = true,
                    excluded = false,
                    domains = listOf("example.com", "example.net"),
                ),
            )
        },
    )

    private fun secret(
        id: String,
        uris: List<DSecret.Uri>,
    ) = DSecret(
        id = id,
        accountId = "account-id",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.fromEpochMilliseconds(0),
        createdDate = Instant.fromEpochMilliseconds(0),
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = id,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        uris = uris,
        type = DSecret.Type.Login,
    )
}

private object BroadHostOnlyTldService : TldService {
    override val version: String = "test"

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        when {
            host.endsWith(".example.com") || host == "example.com" -> "example.com"
            host.endsWith(".example.net") || host == "example.net" -> "example.net"
            else -> host.lowercase()
        }
    }
}

private object ThrowingBroadTldService : TldService {
    override val version: String = "test"

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        if (host.startsWith("broken.")) {
            error("Broken test host.")
        }

        BroadHostOnlyTldService.getDomainName(host).bind()
    }
}

private object NoopBroadLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}
