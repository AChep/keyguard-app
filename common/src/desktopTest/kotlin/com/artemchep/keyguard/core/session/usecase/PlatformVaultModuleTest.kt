package com.artemchep.keyguard.core.session.usecase

import arrow.optics.Getter
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.browser.LastTwoLabelsTldService
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.di.KoinVaultSessionFactory
import com.artemchep.keyguard.di.resolve
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.createSecret
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.module

/** The vault of the desktop app matches logins to web pages, which the browser extension asks for. */
class PlatformVaultModuleTest {
    @Test
    fun `the vault binds the suggestions of the shared matcher`() = runTest {
        val application = Koin().apply {
            loadModules(
                listOf(
                    PlatformVaultModule().module,
                    module {
                        single<GetAutofillDefaultMatchDetection> {
                            object : GetAutofillDefaultMatchDetection {
                                override fun invoke(): Flow<DSecret.Uri.MatchType> =
                                    flowOf(DSecret.Uri.MatchType.default)
                            }
                        }
                        single<GetUrlBlocks> {
                            object : GetUrlBlocks {
                                override fun invoke(): Flow<List<DGlobalUrlBlock>> = flowOf(emptyList())
                            }
                        }
                        single<CipherUrlCheck> { CipherUrlCheckImpl(LastTwoLabelsTldService) }
                    },
                ),
                allowOverride = false,
            )
        }
        try {
            val session = KoinVaultSessionFactory(application)
                .create(MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)))
            val getSuggestions = checkNotNull(session.resolve { get<GetSuggestions<Any?>>() })
            assertIs<GetSuggestionsImpl>(getSuggestions)

            val match = createSecret(
                id = "match",
                uris = listOf(DSecret.Uri(uri = "https://example.com")),
                login = DSecret.Login(username = "alice"),
            )
            val other = createSecret(
                id = "other",
                uris = listOf(DSecret.Uri(uri = "https://other.org")),
                login = DSecret.Login(username = "bob"),
            )
            val url = Url("https://login.example.com/")
            val target = AutofillTarget(
                links = listOf(LinkInfoPlatform.Web(url = url, frontPageUrl = url)),
                hints = listOf(AutofillHint.USERNAME, AutofillHint.PASSWORD),
            )
            val equivalentDomains = EquivalentDomainsBuilderFactory(
                logRepository = RecordingLogRepository(),
                getEquivalentDomains = object : GetEquivalentDomains {
                    override fun invoke(): Flow<List<DEquivalentDomains>> = flowOf(emptyList())
                },
            )
            val suggestions = getSuggestions(listOf(match, other), Getter { it as DSecret }, target, equivalentDomains)
                .bind()
            assertEquals(listOf<Any?>(match), suggestions)
            session.close()
        } finally {
            application.close()
        }
    }
}
