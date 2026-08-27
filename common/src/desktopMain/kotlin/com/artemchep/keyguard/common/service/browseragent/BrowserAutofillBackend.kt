package com.artemchep.keyguard.common.service.browseragent

import arrow.core.getOrElse
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

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
) : BrowserAutofillBackend {
    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
    )

    private val fallbackEquivalentDomains = EquivalentDomains(emptyMap())

    private fun session(): MasterSession.Key? =
        getVaultSession.valueOrNull as? MasterSession.Key

    private suspend fun ciphers(sdi: DirectDI): List<DSecret> =
        filterHiddenProfiles(
            getCiphers = sdi.instance(),
            getProfiles = sdi.instance(),
            filter = null,
        ).first()

    private suspend fun equivalentDomains(sdi: DirectDI): EquivalentDomains {
        val getProfiles = sdi.instance<GetProfiles>()
        val profiles = getProfiles().first()
        val accountId = profiles.firstOrNull()?.accountId
            ?: return fallbackEquivalentDomains
        return try {
            val factory = sdi.instance<EquivalentDomainsBuilderFactory>()
            val builder = factory.build()
            builder.getAndCache(accountId)
        } catch (_: Exception) {
            fallbackEquivalentDomains
        }
    }

    private suspend fun matchesUri(
        secret: DSecret,
        webUrl: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        cipherUrlCheck: CipherUrlCheck,
        eqDomains: EquivalentDomains = fallbackEquivalentDomains,
    ): Boolean {
        for (uri in secret.uris) {
            if (uri.match == DSecret.Uri.MatchType.Never) {
                continue
            }
            val matches = cipherUrlCheck(uri, webUrl, defaultMatchDetection, eqDomains)
                .attempt()
                .bind()
                .getOrElse { false }
            if (matches) {
                return true
            }
        }
        return false
    }

    override suspend fun query(
        domain: String,
        uri: String?,
    ): QueryResult {
        val session = session() ?: return QueryResult(locked = true)
        val sdi = session.di.direct
        val cipherUrlCheck = sdi.instance<CipherUrlCheck>()
        val getAutofillDefaultMatchDetection = sdi.instance<GetAutofillDefaultMatchDetection>()
        val webUrl = uri ?: "https://$domain"
        val defaultMatchDetection = getAutofillDefaultMatchDetection().first()

        // Resolve equivalent domains for the active account so that
        // URIs like "www.github.com" match entries stored as "github.com".
        val eqDomains = equivalentDomains(sdi)

        val items = mutableListOf<AutofillItem>()
        for (secret in ciphers(sdi)) {
            if (secret.type != DSecret.Type.Login || secret.deleted || secret.archived) {
                continue
            }
            if (!matchesUri(secret, webUrl, defaultMatchDetection, cipherUrlCheck, eqDomains)) {
                continue
            }
            items += AutofillItem(
                itemId = secret.id,
                name = secret.name,
                username = secret.login?.username.orEmpty(),
                hasTotp = secret.login?.totp != null,
                hasPasskey = secret.login?.fido2Credentials?.isNotEmpty() == true,
            )
        }
        return QueryResult(locked = false, items = items)
    }

    override suspend fun getSecret(
        itemId: String,
    ): SecretResult {
        val session = session() ?: return SecretResult(locked = true)
        val sdi = session.di.direct
        val secret = ciphers(sdi).firstOrNull { it.id == itemId }
            ?: return SecretResult(locked = false)
        val login = secret.login
        val totp = login?.totp?.token
            ?.let { token ->
                sdi.instance<TotpService>()
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
