package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.session.AccountIconServerSessionAccess
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.RemoteIconUrl
import com.artemchep.keyguard.provider.bitwarden.transport.AccountHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

/** Where to download an icon from, and with which client. */
class ResolvedIcon(
    val url: String,
    val client: AccountHttpClient,
) {
    /** Runs the [block] with the client and the URL of the icon; see [AccountHttpClient.request]. */
    suspend fun <T> request(
        block: suspend (httpClient: HttpClient, url: String) -> T,
    ): T = client.request(url, block)
}

/**
 * Decides whether an icon may be downloaded and where from. Icons of
 * websites in an account with an icon server come from that server,
 * through the account's client; other icons come from the websites.
 */
interface IconResolver {
    /** Whether the icon settings allow downloading the icon. */
    suspend fun isAllowed(request: IconRequest): Boolean

    /**
     * Returns where to download the icon from, or `null` if it must
     * not or can not be downloaded.
     */
    suspend fun resolve(request: IconRequest): ResolvedIcon? =
        if (isAllowed(request)) resolveAllowed(request) else null

    /** Like [resolve], for a caller that has already checked [isAllowed]. */
    suspend fun resolveAllowed(request: IconRequest): ResolvedIcon?
}

class IconResolverImpl(
    private val getVaultSession: GetVaultSession,
    private val accountIconServerSessionAccess: AccountIconServerSessionAccess,
    private val getWebsiteIcons: GetWebsiteIcons,
    private val getAppIcons: GetAppIcons,
    private val faviconScraper: FaviconScraper,
    private val googlePlayParser: AndroidAppGooglePlayParser,
    private val appStoreParser: IosAppAppStoreParser,
    private val httpClient: HttpClient,
) : IconResolver {
    override suspend fun isAllowed(request: IconRequest): Boolean = when (request) {
        is FaviconUrl,
        is RemoteIconUrl,
        -> getWebsiteIcons().first()

        // A store icon is both an app icon and
        // a download from a website.
        is AppIconUrl,
        is IosAppIconUrl,
        -> getAppIcons().first() && getWebsiteIcons().first()
    }

    override suspend fun resolveAllowed(request: IconRequest): ResolvedIcon? =
        when (request) {
            is FaviconUrl -> resolveFavicon(request)
            is AppIconUrl -> resolveAppIcon(request)
            is IosAppIconUrl -> resolveIosAppIcon(request)
            is RemoteIconUrl -> resolveRemote(request.url)
        }

    private suspend fun resolveFavicon(request: FaviconUrl): ResolvedIcon? {
        val origin = faviconOrigin(request.url)
            ?: return null
        val server = if (request.serverId != null) {
            getAccountIconServer(AccountId(request.serverId))
        } else {
            AccountIconServer.Website
        }
        return when (server) {
            // An icon of an account item must not leak to the website
            // when the account's server is not available.
            null -> null
            is AccountIconServer.Remote -> iconServerLookupHost(origin)
                ?.let { host ->
                    ResolvedIcon(
                        url = server.requestUrl(host),
                        // A redirect of the account's icon server to another
                        // host must not get the account's client certificate.
                        client = AccountHttpClient(server.httpClient, httpClient),
                    )
                }

            is AccountIconServer.Website -> faviconScraper.find(origin)
                ?.let { url ->
                    ResolvedIcon(
                        url = url.toString(),
                        client = AccountHttpClient(httpClient),
                    )
                }
        }
    }

    private suspend fun getAccountIconServer(accountId: AccountId): AccountIconServer? {
        val session = getVaultSession().first() as? MasterSession.Key
        return session
            ?.let(accountIconServerSessionAccess::invoke)
            ?.get(accountId)
    }

    private suspend fun resolveAppIcon(request: AppIconUrl): ResolvedIcon? {
        val listing = googlePlayParser(request.packageName)
            .bind()
            ?: return null
        return resolveRemote(listing.iconUrl)
    }

    private suspend fun resolveIosAppIcon(request: IosAppIconUrl): ResolvedIcon? {
        val listing = appStoreParser(request.bundleId)
            .bind()
            ?: return null
        return resolveRemote(listing.iconUrl)
    }

    private fun resolveRemote(url: String): ResolvedIcon? = parseHttpUrlOrNull(url)
        ?.takeUnless { verifyIsLocalUrl(it) }
        ?.let {
            ResolvedIcon(
                url = url,
                client = AccountHttpClient(httpClient),
            )
        }
}
