package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.session.AccountIconServerSessionAccess
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.RemoteIconUrl
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class IconResolverTest {
    private val accountHttpClient = HttpClient(MockEngine { error("The account client is not called") })

    private val servers = mutableMapOf<String, AccountIconServer>(
        "bitwarden" to AccountIconServer.Remote(
            env = ServerEnv(iconsUrl = "https://vault.example.com/icons/"),
            httpClient = accountHttpClient,
        ),
        "keepass" to AccountIconServer.Website,
    )

    @Test
    fun `account icons come from the account icon server`() = runTest {
        val fixture = Fixture()

        val icon = fixture.resolver.resolve(FaviconUrl("bitwarden", "https://Example.com/login?x=1"))

        assertEquals("https://vault.example.com/icons/example.com/icon.png", icon?.url)
        assertSame(accountHttpClient, icon?.client?.httpClient)
        // A redirect off the account's servers is followed without the account's client.
        assertSame(fixture.iconHttpClient, icon?.client?.redirectHttpClient)
        assertEquals(emptyList(), fixture.requests)
    }

    @Test
    fun `account icon servers do not get local or non-default port websites`() = runTest {
        val fixture = Fixture()

        assertNull(fixture.resolver.resolve(FaviconUrl("bitwarden", "https://example.com:8443/")))
        assertNull(fixture.resolver.resolve(FaviconUrl("bitwarden", "http://192.168.1.1/")))
        assertEquals(emptyList(), fixture.requests)
    }

    @Test
    fun `icons of accounts without a server come from the website`() = runTest {
        val fixture = Fixture()

        val icon = fixture.resolver.resolve(FaviconUrl("keepass", "https://example.com/login"))

        assertEquals("https://example.com/favicon.ico", icon?.url)
        assertSame(fixture.iconHttpClient, icon?.client?.httpClient)
        assertEquals(listOf("https://example.com/"), fixture.requests)
    }

    @Test
    fun `icons without an account come from the website`() = runTest {
        val fixture = Fixture()

        val icon = fixture.resolver.resolve(FaviconUrl(url = "https://example.com"))

        assertEquals("https://example.com/favicon.ico", icon?.url)
    }

    @Test
    fun `account icons do not leak to the website while locked`() = runTest {
        val fixture = Fixture(unlocked = false)

        assertNull(fixture.resolver.resolve(FaviconUrl("bitwarden", "https://example.com")))
        assertNull(fixture.resolver.resolve(FaviconUrl("keepass", "https://example.com")))
        assertEquals(emptyList(), fixture.requests)
    }

    @Test
    fun `icons of unknown accounts are not downloaded`() = runTest {
        val fixture = Fixture()

        assertNull(fixture.resolver.resolve(FaviconUrl("deleted", "https://example.com")))
        assertEquals(emptyList(), fixture.requests)
    }

    @Test
    fun `website icons setting gates favicons and remote icons`() = runTest {
        val fixture = Fixture(websiteIcons = false)
        val requests = listOf<IconRequest>(
            FaviconUrl("bitwarden", "https://example.com"),
            FaviconUrl(url = "https://example.com"),
            RemoteIconUrl("https://logos.example.com/x.png"),
            AppIconUrl("com.example"),
            IosAppIconUrl("com.example"),
        )

        requests.forEach { request ->
            assertFalse(fixture.resolver.isAllowed(request))
            assertNull(fixture.resolver.resolve(request))
        }
        assertEquals(emptyList(), fixture.requests)
    }

    @Test
    fun `store icons need both settings`() = runTest {
        val disabled = Fixture(appIcons = false)
        listOf(AppIconUrl("com.example"), IosAppIconUrl("com.example")).forEach { request ->
            assertFalse(disabled.resolver.isAllowed(request))
            assertNull(disabled.resolver.resolve(request))
        }
        assertTrue(disabled.requests.isEmpty())
        assertTrue(Fixture(appIcons = false).resolver.isAllowed(FaviconUrl(url = "https://example.com")))
        assertTrue(Fixture().resolver.isAllowed(AppIconUrl("com.example")))
        assertTrue(Fixture().resolver.isAllowed(IosAppIconUrl("com.example")))
    }

    @Test
    fun `store icons come from the store listing`() = runTest {
        val fixture = Fixture()

        val icon = fixture.resolver.resolve(AppIconUrl("com.example"))

        assertEquals("https://play-lh.example.com/icon.png", icon?.url)
        assertSame(fixture.iconHttpClient, icon?.client?.httpClient)
    }

    @Test
    fun `ios and android identifiers resolve through their own stores`() = runTest {
        val fixture = Fixture()

        val ios = fixture.resolver.resolve(IosAppIconUrl("com.example"))
        val android = fixture.resolver.resolve(AppIconUrl("com.example"))

        assertEquals("https://is1-ssl.example.com/icon.png", ios?.url)
        assertEquals("https://play-lh.example.com/icon.png", android?.url)
        assertSame(fixture.iconHttpClient, ios?.client?.httpClient)
        assertTrue("https://itunes.apple.com/lookup?bundleId=com.example" in fixture.requests)
    }

    @Test
    fun `remote icons must be public http urls`() = runTest {
        val resolver = Fixture().resolver

        assertEquals(
            "https://logos.example.com/x.png",
            resolver.resolve(RemoteIconUrl("https://logos.example.com/x.png"))?.url,
        )
        assertNull(resolver.resolve(RemoteIconUrl("http://10.0.0.1/x.png")))
        assertNull(resolver.resolve(RemoteIconUrl("file:///etc/passwd")))
        assertNull(resolver.resolve(RemoteIconUrl("https://")))
        assertNull(resolver.resolve(RemoteIconUrl("")))
    }

    private inner class Fixture(
        unlocked: Boolean = true,
        websiteIcons: Boolean = true,
        appIcons: Boolean = true,
    ) {
        val requests = mutableListOf<String>()

        val iconHttpClient = HttpClient(
            MockEngine { request ->
                requests += request.url.toString()
                when (request.url.host) {
                    "play.google.com" -> respond(PLAY_PAGE, HttpStatusCode.OK)
                    "itunes.apple.com" -> respond(APP_STORE_RESPONSE, HttpStatusCode.OK)
                    else -> respond("", HttpStatusCode.OK)
                }
            },
        )

        val resolver = IconResolverImpl(
            getVaultSession = FakeGetVaultSession(if (unlocked) unlockedSession() else MasterSession.Empty()),
            accountIconServerSessionAccess = AccountIconServerSessionAccess {
                object : AccountIconServers {
                    override suspend fun get(accountId: AccountId) = servers[accountId.id]
                }
            },
            getWebsiteIcons = object : GetWebsiteIcons {
                override fun invoke(): Flow<Boolean> = MutableStateFlow(websiteIcons)
            },
            getAppIcons = object : GetAppIcons {
                override fun invoke(): Flow<Boolean> = MutableStateFlow(appIcons)
            },
            faviconScraper = FaviconScraper(iconHttpClient, Json),
            googlePlayParser = AndroidAppGooglePlayParser(iconHttpClient, Json { ignoreUnknownKeys = true }),
            appStoreParser = IosAppAppStoreParser(iconHttpClient, Json { ignoreUnknownKeys = true }),
            httpClient = iconHttpClient,
        )
    }

    private class FakeGetVaultSession(session: MasterSession) : GetVaultSession {
        private val flow = MutableStateFlow(session)

        override val valueOrNull: MasterSession get() = flow.value

        override fun invoke(): Flow<MasterSession> = flow
    }
}

private fun unlockedSession() = MasterSession.Key(
    masterKey = MasterKey(MasterKdfVersion.V0, ByteArray(32)),
    session = object : VaultSession {
        override val id = "session"
        override val active = MutableStateFlow(true)
        override fun retire() = Unit
        override fun close() = Unit
    },
    origin = MasterSession.Key.Authenticated,
    createdAt = Instant.fromEpochSeconds(0),
)

private val PLAY_PAGE = """
    <html><head>
    <script type="application/ld+json">
    {
      "@type": "SoftwareApplication",
      "name": "Example",
      "image": "https://play-lh.example.com/icon.png",
      "description": "An example app"
    }
    </script>
    </head></html>
""".trimIndent()

private val APP_STORE_RESPONSE = """
    {"resultCount":1,"results":[{
        "trackName":"Example",
        "artworkUrl512":"https://is1-ssl.example.com/icon.png",
        "description":"An example app"
    }]}
""".trimIndent()
