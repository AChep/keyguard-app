package com.artemchep.keyguard.common.service.browseragent

import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.BrowserAutofillSessionAccess
import com.artemchep.keyguard.common.service.session.BrowserAutofillSessionDependencies
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

interface BrowserAutofillBackend {
    suspend fun query(
        domain: String,
        uri: String?,
    ): QueryResult

    suspend fun getSecret(
        itemId: String,
    ): SecretResult
}

class VaultBrowserAutofillBackend(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: BrowserAutofillSessionAccess,
) : BrowserAutofillBackend {
    private val fallbackEquivalentDomains = EquivalentDomains(emptyMap())

    private fun session(): MasterSession.Key? =
        getVaultSession.valueOrNull as? MasterSession.Key

    private suspend fun ciphers(
        deps: BrowserAutofillSessionDependencies,
    ): List<DSecret> =
        filterHiddenProfiles(
            getCiphers = deps.getCiphers,
            getProfiles = deps.getProfiles,
            filter = null,
        ).first()

    private suspend fun equivalentDomains(
        deps: BrowserAutofillSessionDependencies,
    ): EquivalentDomains {
        val profiles = deps.getProfiles().first()
        val accountId = profiles.firstOrNull()?.accountId
            ?: return fallbackEquivalentDomains
        return try {
            val builder = deps.filterContext.equivalentDomainsBuilderFactory.build()
            builder.getAndCache(accountId)
        } catch (_: Exception) {
            fallbackEquivalentDomains
        }
    }

    private suspend fun matchesUri(
        deps: BrowserAutofillSessionDependencies,
        secret: DSecret,
        webUrl: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        eqDomains: EquivalentDomains,
    ): Boolean = secret.uris.any { uri ->
        uri.match != DSecret.Uri.MatchType.Never &&
            deps.cipherUrlCheck(uri, webUrl, defaultMatchDetection, eqDomains)
                .attempt()
                .bind()
                .getOrElse { false }
    }

    @Suppress("ReturnCount")
    override suspend fun query(
        domain: String,
        uri: String?,
    ): QueryResult {
        val session = session() ?: return QueryResult(locked = true)
        val deps = sessionAccess(session) ?: return QueryResult(locked = true)
        val webUrl = uri ?: "https://$domain"
        val defaultMatchDetection = deps.filterContext
            .getAutofillDefaultMatchDetection()
            .first()

        // Resolve equivalent domains for the active account so that
        // URIs like "www.github.com" match entries stored as "github.com".
        val eqDomains = equivalentDomains(deps)

        val items = ciphers(deps)
            .filter { secret ->
                val isLogin = secret.type == DSecret.Type.Login &&
                    !secret.deleted &&
                    !secret.archived
                isLogin && matchesUri(
                    deps = deps,
                    secret = secret,
                    webUrl = webUrl,
                    defaultMatchDetection = defaultMatchDetection,
                    eqDomains = eqDomains,
                )
            }
            .map { secret ->
                AutofillItem(
                    itemId = secret.id,
                    name = secret.name,
                    username = secret.login?.username.orEmpty(),
                    hasTotp = secret.login?.totp != null,
                    hasPasskey = secret.login?.fido2Credentials?.isNotEmpty() == true,
                )
            }
        return QueryResult(locked = false, items = items)
    }

    @Suppress("ReturnCount")
    override suspend fun getSecret(
        itemId: String,
    ): SecretResult {
        val session = session() ?: return SecretResult(locked = true)
        val deps = sessionAccess(session) ?: return SecretResult(locked = true)
        val secret = ciphers(deps).firstOrNull { it.id == itemId }
            ?: return SecretResult(locked = false)
        val login = secret.login
        val totp = login?.totp?.token
            ?.let { token ->
                deps.totpService
                    .generate(token, Clock.System.now())
                    .fold({ null }, { it.code })
            }
        return SecretResult(
            locked = false,
            username = login?.username,
            password = login?.password,
            totp = totp,
        )
    }
}
