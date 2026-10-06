package com.artemchep.keyguard.apple.lists

import arrow.optics.Getter
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.apple.core.CoreContext
import app.cash.sqldelight.coroutines.asFlow
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The main app populates the QuickType index (ASCredentialIdentityStore) from this; the appex resolves a
 * selected credential.
 */
internal class AutofillController(
    private val ctx: CoreContext,
) {
    /** Whether URLs without their own match mode are left out of the system index. */
    private val excludeInheritedUris: Flow<Boolean> by lazy {
        ctx.koin.get<GetAutofillDefaultMatchDetection>()()
            .map { it == DSecret.Uri.MatchType.Never }
            .distinctUntilChanged()
    }

    fun observeChanges(onChange: (Boolean) -> Unit, onFailure: () -> Unit): KeyguardCancellable =
        ctx.launchObserver {
            try {
                ctx.unlockUseCase().collectLatest { state ->
                    when (state) {
                        is VaultState.Main -> {
                            val db = state.sessionKoin.get<VaultDatabaseManager>().get().bind()
                            combine(
                                db.cipherQueries.getCipherSnapshotKeys().asFlow(),
                                db.profileQueries.get().asFlow(),
                                excludeInheritedUris,
                            ) { _, _, _ -> Unit }
                                .collectOnMain { onChange(true) }
                        }
                        is VaultState.Create -> ctx.publishOnMain { onChange(true) }
                        else -> ctx.publishOnMain { onChange(false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ctx.publishOnMain { onFailure() }
            }
        }

    suspend fun loadIndex(): AutofillIndexSnapshot? = withContext(Dispatchers.Default) {
        val state = ctx.currentState()
        if (state is VaultState.Create) return@withContext AutofillIndexSnapshot(emptyList(), emptyList(), emptyList())
        if (state !is VaultState.Main) return@withContext null
        val ciphers = AutofillVaultReader(state.sessionKoin).read()
        ciphers.toAutofillIndex(excludeInheritedUris.first())
    }

    suspend fun loadAutofillCredential(recordId: String): AutofillCredentialSnapshot? {
        val parts = recordId.split('|', limit = 2)
        if (parts.size != 2) return null
        val (accountId, cipherId) = parts
        val state = ctx.currentState() as? VaultState.Main ?: return null
        val cipher = AutofillVaultReader(state.sessionKoin).read(cipherId)
            .firstOrNull { it.accountId == accountId }
            ?: return null
        val login = cipher.login ?: return null
        if (login.password.isNullOrEmpty()) return null
        return AutofillCredentialSnapshot(
            user = login.username.orEmpty(),
            password = login.password.orEmpty(),
        )
    }

    suspend fun loadAutofillSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        suggestions(serviceIdentifiers) { !it.login?.password.isNullOrEmpty() }

    suspend fun loadOneTimeCodeSuggestions(
        serviceIdentifiers: List<String>,
    ): List<AutofillSuggestionSnapshot> =
        suggestions(serviceIdentifiers) { it.hasAutofillOneTimeCode() }

    suspend fun loadPasskeyRegistrationSuggestions(serviceIdentifiers: List<String>) =
        suggestions(serviceIdentifiers) { it.login != null && it.service.canEdit() }

    private suspend fun suggestions(
        serviceIdentifiers: List<String>,
        predicate: (BitwardenCipher) -> Boolean,
    ): List<AutofillSuggestionSnapshot> {
        val state = ctx.currentState() as? VaultState.Main ?: return emptyList()
        val di = state.sessionKoin
        val accountNames = AutofillVaultReader(di).accountNames()
        val strength = di.get<GetPasswordStrength>()
        val ciphers = AutofillVaultReader(di).read()
            .filter(predicate)
            // Matching needs names and URIs, not password-strength/GPG enrichment.
            .map { it.copy(login = it.login?.copy(password = null)).toDomain(strength) }

        if (ciphers.isEmpty()) return emptyList()

        val getSuggestions = di.get<GetSuggestions<Any?>>()
        val equivalentDomainsBuilderFactory = di.get<EquivalentDomainsBuilderFactory>()
        val extractor = LinkInfoPlatformExtractor()
        val links = serviceIdentifiers
            .filter { it.isNotBlank() }
            .map { identifier ->
                extractor.extractInfo(DSecret.Uri(uri = identifier)).bind()
            }
        val target = AutofillTarget(
            links = links,
            hints = listOf(AutofillHint.USERNAME, AutofillHint.PASSWORD),
        )
        val matches = getSuggestions(
            ciphers,
            Getter { it as DSecret },
            target,
            equivalentDomainsBuilderFactory,
        ).bind()
            .mapNotNull { (it as? DSecret)?.toSuggestion(accountNames, suggested = true) }
        // Suggestions never limit the full-vault search.
        val matchedIds = matches.map { it.recordId }.toSet()
        return matches + ciphers.map { it.toSuggestion(accountNames) }.filter { it.recordId !in matchedIds }
    }

    suspend fun loadOneTimeCode(recordId: String): String? {
        val parts = recordId.split('|', limit = 2)
        if (parts.size != 2) return null
        val (accountId, cipherId) = parts
        val state = ctx.currentState() as? VaultState.Main ?: return null
        val di = state.sessionKoin
        val cipher = AutofillVaultReader(di).read(cipherId)
            .firstOrNull { it.accountId == accountId }
            ?: return null
        val token = cipher.login?.totp?.let { TotpToken.parse(it).getOrNull() } ?: return null
        return di.get<GetTotpCode>()(token).first().getOrNull()?.code
    }
}

private fun DSecret.toSuggestion(
    accountNames: Map<String, String>,
    suggested: Boolean = false,
) = AutofillSuggestionSnapshot(
    recordId = "$accountId|$id",
    name = name,
    user = login?.username.orEmpty(),
    suggested = suggested,
    accountName = accountNames[accountId].orEmpty(),
)

internal fun List<BitwardenCipher>.toIdentities(
    excludeInheritedUris: Boolean,
): List<AutofillIdentitySnapshot> = flatMap { cipher ->
    cipher.login?.uris.orEmpty()
        .filter { uri ->
            val never = uri.match?.let { it == BitwardenCipher.Login.Uri.MatchType.Never } ?: excludeInheritedUris
            !never && !uri.uri.isNullOrBlank()
        }
        .distinctBy { it.uri }
        .map { uri ->
            AutofillIdentitySnapshot(
                recordId = "${cipher.accountId}|${cipher.cipherId}",
                serviceIdentifier = requireNotNull(uri.uri),
                user = cipher.login?.username.orEmpty(),
                cipherId = cipher.cipherId,
                accountId = cipher.accountId,
            )
        }
}

internal fun List<BitwardenCipher>.toAutofillIndex(
    excludeInheritedUris: Boolean,
): AutofillIndexSnapshot {
    val passkeys = toPasskeyIdentities()
    return AutofillIndexSnapshot(
        passwords = filter { !it.login?.password.isNullOrEmpty() }.toIdentities(excludeInheritedUris),
        oneTimeCodes = filter { it.hasAutofillOneTimeCode() }.toIdentities(excludeInheritedUris),
        passkeys = passkeys,
        skippedPasskeys = sumOf { it.login?.fido2Credentials?.size ?: 0 } - passkeys.size,
    )
}

internal fun BitwardenCipher.hasAutofillOneTimeCode(): Boolean =
    login?.totp?.takeIf { it.isNotBlank() }?.let { TotpToken.parse(it).getOrNull() } != null
