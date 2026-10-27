package com.artemchep.keyguard.feature.cli.ui

import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.feature.cli.cliUsage
import com.artemchep.keyguard.presentation.operation.SingleOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine

/**
 * Produces the CLI settings; one operation runs at a time. Stopping
 * the collection cancels the running operation.
 */
fun cliSettingsStateProducer(
    service: CliService,
): Flow<CliSettingsState> = channelFlow {
    val operation = SingleOperation(this)
    val onEnabledChange: (Boolean) -> Unit = { enabled ->
        operation.launch {
            service.setEnabled(enabled)
        }
    }
    combine(
        service.state,
        operation.state,
    ) { state, operationState ->
        CliSettingsState(
            enabled = state.enabled,
            path = state.path.takeIf { state.supported },
            usage = cliUsage(state.socketPath),
            busy = operationState.busy,
            failed = operationState.failed,
            onEnabledChange = onEnabledChange
                .takeUnless { operationState.busy },
        )
    }.collect(::send)
}
