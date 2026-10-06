package com.artemchep.keyguard.android.autofill

import arrow.optics.Getter
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.android.AutofillActivity
import com.artemchep.keyguard.android.AutofillSaveActivity
import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import com.artemchep.keyguard.autofillTarget
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.of
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.autofill
import com.artemchep.keyguard.provider.bitwarden.usecase.autofill1
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutofillLoopbackIntegrationTest {
    @Test
    fun `saved logins from embedded localhost apps match only their respective app`() = runTest {
        val appA = adapt("com.example.alpha")
        val appB = adapt("com.example.beta")
        val urisA = appA.savedLoginUris()
        val urisB = appB.savedLoginUris()
        val ciphers = listOf(secret("a", urisA), secret("b", urisB))

        assertEquals(listOf("androidapp://com.example.alpha"), urisA.map { it.uri })
        assertEquals(listOf("androidapp://com.example.beta"), urisB.map { it.uri })
        assertEquals(listOf("a"), suggestions(appA, ciphers))
        assertEquals(listOf("b"), suggestions(appB, ciphers))
    }

    @Test
    fun `existing localhost URI does not displace the current app login`() = runTest {
        val appA = adapt("com.example.alpha")
        val appB = adapt("com.example.beta")
        val ciphers = listOf(
            secret("a", appA.savedLoginUris() + DSecret.Uri("http://localhost")),
            secret("b", appB.savedLoginUris()),
        )

        assertEquals(listOf("b"), suggestions(appB, ciphers))
    }

    @Test
    fun `browser localhost login still saves and matches by web origin`() = runTest {
        val browser = adapt("org.mozilla.firefox")
        val uris = browser.savedLoginUris()
        val ciphers = listOf(
            secret("web", uris),
            secret("app", adapt("com.example.alpha").savedLoginUris()),
        )

        assertEquals(listOf("http://localhost"), uris.map { it.uri })
        assertEquals(listOf("web"), suggestions(browser, ciphers))
    }

    @Test
    fun `save URI for an existing embedded app login adds only the app URI`() {
        val structure = adapt("com.example.alpha")
        val uris = autofill1(
            applicationId = structure.applicationId,
            webDomain = structure.webDomain,
            webScheme = structure.webScheme,
        )

        assertEquals(listOf("androidapp://com.example.alpha"), uris.map { it.uri })
    }

    @Test
    fun `unknown requester with loopback has no matching or saved identity`() = runTest {
        val structure = adapt(null)
        val ciphers = listOf(secret("web", listOf(DSecret.Uri("http://localhost"))))

        assertTrue(structure.target().links.isEmpty())
        assertTrue(structure.savedLoginUris().isEmpty())
        assertTrue(suggestions(structure, ciphers).isEmpty())
    }

    private fun adapt(applicationId: String?) = ParseResultV2(
        structure = NormalizedStructureV2(
            applicationId = applicationId,
            webDomain = "localhost",
            webScheme = "http",
            webView = true,
        ),
    ).toAutofillStructure2()

    private fun AutofillStructure2.savedLoginUris(): List<DSecret.Uri> {
        val autofill = AddRoute.Args.Autofill.of(
            AutofillSaveActivity.Args(
                autofillStructure2 = this,
                applicationId = applicationId,
                webDomain = webDomain,
                webScheme = webScheme,
            ),
        )
        return emptyList<DSecret.Uri>().autofill(
            applicationId = autofill.applicationId,
            webDomain = autofill.webDomain,
            webScheme = autofill.webScheme,
        )
    }

    private fun AutofillStructure2.target() = AppMode.Pick(
        args = AutofillActivity.Args(
            autofillStructure2 = this,
            applicationId = applicationId,
            webDomain = webDomain,
            webScheme = webScheme,
        ),
        onAutofill = { _, _ -> },
    ).autofillTarget!!.copy(hints = listOf(AutofillHint.USERNAME, AutofillHint.PASSWORD))

    private fun secret(id: String, uris: List<DSecret.Uri>) = createSecret(
        id = id,
        uris = uris,
        login = DSecret.Login(username = "user", password = "password"),
    )

    private suspend fun suggestions(
        structure: AutofillStructure2,
        ciphers: List<DSecret>,
    ): List<String> {
        val getSuggestions = GetSuggestionsImpl(
            getAutofillDefaultMatchDetection = object : GetAutofillDefaultMatchDetection {
                override fun invoke() = flowOf(DSecret.Uri.MatchType.Domain)
            },
            getUrlBlocks = object : GetUrlBlocks {
                override fun invoke() = flowOf(emptyList<DGlobalUrlBlock>())
            },
            cipherUrlCheck = CipherUrlCheckImpl(object : TldService {
                override val version = "test"
                override fun getDomainName(host: String): IO<String> = { host }
            }),
        )
        val equivalentDomains = EquivalentDomainsBuilderFactory(
            logRepository = LogRepositoryBridge(emptyList()),
            getEquivalentDomains = object : GetEquivalentDomains {
                override fun invoke() = flowOf(emptyList<DEquivalentDomains>())
            },
        )
        return getSuggestions(
            ciphers = ciphers,
            getter = Getter { it as DSecret },
            target = structure.target(),
            equivalentDomainsBuilderFactory = equivalentDomains,
        ).bind().map { (it as DSecret).id }
    }
}
