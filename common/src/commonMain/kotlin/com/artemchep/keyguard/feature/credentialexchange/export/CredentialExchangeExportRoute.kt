package com.artemchep.keyguard.feature.credentialexchange.export

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.feature.navigation.Route

/**
 * The consent/review screen shown when another application requests a copy of
 * the vault credentials through the FIDO Credential Exchange flow.
 *
 * The route owns the review only, not the transport: on confirmation it hands
 * the already-selected [accounts][CxfAccount] back to its host through
 * [Args.onComplete], and the host (an Android activity) wraps them into a
 * document, encodes it, and answers the platform request.
 */
class CredentialExchangeExportRoute(
    val args: Args,
) : Route {
    data class Args(
        /**
         * The account the picked picker entry addresses, and the only account this
         * transfer may read. Resolved from the request's entry id before the vault
         * is unlocked, so it may name an account that is no longer available.
         */
        val accountId: AccountId,
        /**
         * The credential kinds the importer requested, already parsed down to
         * the kinds Keyguard recognizes. An exact filter: an empty set means
         * nothing may be exported (the review screen then shows its empty
         * state), and an unrestricted export must be requested with
         * [CxfCredentialType.ALL] (CXP v1.0 §3.2).
         */
        val requestedTypes: Set<CxfCredentialType>,
        /**
         * `true` when the host already knows the user is present, which skips the
         * screen's own verification gate.
         *
         * The host sets this when the vault was unlocked *as part of this request* —
         * the user typed their master password seconds ago, so the gate would only
         * ask the same question twice. It stays `false` for a session restored from
         * disk, where nobody has proven presence.
         */
        val userVerified: Boolean = false,
        /**
         * Invoked exactly once when the user resolves the flow, either by
         * confirming the transfer or by denying/cancelling it.
         */
        val onComplete: (CredentialExchangeExportResult) -> Unit,
    )

    @Composable
    override fun Content() {
        CredentialExchangeExportScreen(
            args = args,
        )
    }
}

/**
 * The outcome of the review screen.
 */
sealed interface CredentialExchangeExportResult {
    /**
     * The user denied the transfer (or navigated back).
     */
    data object Cancel : CredentialExchangeExportResult

    /**
     * The user confirmed the transfer of the given [accounts].
     */
    data class Complete(
        val accounts: List<CxfAccount>,
    ) : CredentialExchangeExportResult

    /**
     * The export could not be prepared. Distinct from [Cancel]: the transport
     * must answer the requesting app with an error, not with "the user
     * declined".
     */
    data object Fail : CredentialExchangeExportResult
}
