package com.artemchep.keyguard.feature.credentialexchange.export

import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/**
 * The screen state for a vault that has not passed the user-verification gate.
 *
 * Must never be `Loadable.Loading`: this stage *carries* the verification form, and
 * a spinner cannot collect a password. It needs no deny of its own — the gate is
 * rendered in place rather than as a dialog, so there is nothing to dismiss, and the
 * host's consent header answers the requesting app on every exit.
 */
internal fun lockedLoadableState(
    onAuthenticated: () -> Unit,
): Loadable<CredentialExchangeExportState> = Loadable.Ok(
    CredentialExchangeExportState(
        stage = CredentialExchangeExportState.Stage.Locked(
            onAuthenticated = onAuthenticated,
        ),
    ),
)

/**
 * The screen state for a passed gate whose mapping has not produced a review yet.
 *
 * The gate must hand over to this the moment it opens: the verification form is a
 * whole surface of its own, so leaving it up for the length of the mapping reads as
 * a prompt that did not take.
 */
internal fun mappingLoadableState(
    complete: (CredentialExchangeExportResult) -> Unit,
): Loadable<CredentialExchangeExportState> = Loadable.Ok(
    CredentialExchangeExportState(
        stage = CredentialExchangeExportState.Stage.Mapping(
            onDeny = {
                complete(CredentialExchangeExportResult.Cancel)
            },
        ),
    ),
)

/**
 * Prefixes the review flow with [mappingLoadableState].
 *
 * The review's own `combine` cannot emit until every vault source has emitted and
 * the mapping has decoded each passkey and converted each SSH key of the account;
 * until then the screen keeps rendering whatever it last held, which is the
 * verification form. This is what makes the wait visible instead.
 */
internal fun Flow<Loadable<CredentialExchangeExportState>>.startWithMapping(
    complete: (CredentialExchangeExportResult) -> Unit,
): Flow<Loadable<CredentialExchangeExportState>> = onStart {
    emit(mappingLoadableState(complete = complete))
}
