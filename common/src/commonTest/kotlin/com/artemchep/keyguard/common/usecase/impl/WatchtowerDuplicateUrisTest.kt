package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchtowerDuplicateUrisTest {
    @Test
    fun `reverse direction is checked after forward miss`() = runTest {
        val duplicateCheck = RecordingUrlDuplicateCheck { a, b ->
            a.uri == "https://b.example.com" && b.uri == "https://a.example.com"
        }

        val result = watchtower(duplicateCheck).process(
            listOf(secret("https://a.example.com", "https://b.example.com")),
        )

        assertTrue(result.single().threat)
        assertEquals(
            listOf(
                "https://a.example.com" to "https://b.example.com",
                "https://b.example.com" to "https://a.example.com",
            ),
            duplicateCheck.calls,
        )
    }

    @Test
    fun `reverse direction is skipped after forward match`() = runTest {
        val duplicateCheck = RecordingUrlDuplicateCheck { a, b ->
            a.uri == "https://a.example.com" && b.uri == "https://b.example.com"
        }

        val result = watchtower(duplicateCheck).process(
            listOf(secret("https://a.example.com", "https://b.example.com")),
        )

        assertTrue(result.single().threat)
        assertEquals(
            listOf("https://a.example.com" to "https://b.example.com"),
            duplicateCheck.calls,
        )
    }

    private fun watchtower(
        duplicateCheck: CipherUrlDuplicateCheck,
    ) = WatchtowerDuplicateUris(
        getAutofillDefaultMatchDetection = object : GetAutofillDefaultMatchDetection {
            override fun invoke() = flowOf(DSecret.Uri.MatchType.Domain)
        },
        cipherUrlDuplicateCheck = duplicateCheck,
        equivalentDomainsBuilderFactory = EquivalentDomainsBuilderFactory(
            logRepository = DuplicateUrisNoopLogRepository,
            getEquivalentDomains = object : GetEquivalentDomains {
                override fun invoke() = flowOf(emptyList<DEquivalentDomains>())
            },
        ),
    )

    private fun secret(
        vararg urls: String,
    ) = DSecret(
        id = "cipher",
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = null,
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = "Login",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        uris = urls.map { url ->
            DSecret.Uri(
                uri = url,
                match = DSecret.Uri.MatchType.Domain,
            )
        },
        type = DSecret.Type.Login,
        login = DSecret.Login(),
    )
}

private class RecordingUrlDuplicateCheck(
    private val isDuplicate: (DSecret.Uri, DSecret.Uri) -> Boolean,
) : CipherUrlDuplicateCheck {
    val calls = mutableListOf<Pair<String, String>>()

    override fun invoke(
        a: DSecret.Uri,
        b: DSecret.Uri,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
    ): IO<DSecret.Uri?> {
        calls += a.uri to b.uri
        return io(a.takeIf { isDuplicate(a, b) })
    }
}

private object DuplicateUrisNoopLogRepository : LogRepository {
    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = Unit
}
