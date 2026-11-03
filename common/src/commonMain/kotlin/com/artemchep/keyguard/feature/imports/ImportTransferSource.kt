package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransport
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportService
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.credentialexchange.imports.debugPayloadOrNull
import com.artemchep.keyguard.feature.credentialexchange.imports.launchSaveCxfImportPayload
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The CXF credential types requested from the source provider: everything the
 * import mapper can represent.
 */
private val requestedCredentialTypes = CxfCredentialType.IMPORTABLE
    .mapTo(mutableSetOf()) { it.serialName }

/**
 * The credential-transfer side of the import flow: asks the platform broker
 * for the credentials of another provider on this device and parses what it
 * hands over.
 */
internal class ImportTransferFlow(
    private val scope: RememberStateFlowScope,
    private val steps: ImportSteps,
    private val cxfImportService: CxfImportService,
    private val transport: CredentialExchangeImportTransport?,
    private val dirsService: DirsService,
    private val dateFormatter: DateFormatter,
) {
    /**
     * On GMS-free Android builds the source stays listed and the flow
     * degrades to an explanatory error; elsewhere there is no broker at all.
     */
    val isOffered = CurrentPlatform is Platform.Mobile.Android

    // Debug-only. Held beside the step rather than inside it: `Step.Review` is a data
    // class that `claimCommit` copies and compare-and-sets, so a payload member would
    // put a whole plaintext vault into its `toString()` — one stray log away from the
    // leak the "never logged" rule exists to prevent. Empty in a release build.
    private val debugPayloadSink = MutableStateFlow<String?>(null)

    /**
     * Writes the received payload to the downloads folder, or `null` while no
     * payload is held, which is always the case in a release build.
     */
    val savePayloadFlow: Flow<(() -> Unit)?> = debugPayloadSink
        .map { payload ->
            payload?.let {
                // lambda
                {
                    scope.launchSaveCxfImportPayload(
                        payload = payload,
                        dirsService = dirsService,
                        dateFormatter = dateFormatter,
                    )
                }
            }
        }

    fun start(context: LeContext) {
        val loading = Step.Loading()
        steps.set(loading)
        // Don't let the previous attempt's vault sit in memory across the next one.
        dropPayload()
        scope.screenScope.launch {
            // Anything that escapes here would leave the steps on
            // `Step.Loading` for good, so every outcome has to resolve to a
            // step — including the ones the transport and the parser do not
            // promise to handle.
            val step = with(scope) {
                runCatchingNonFatal {
                    if (transport == null) {
                        Step.Error(
                            message = translate(Res.string.credential_exchange_import_error_unavailable),
                            retry = null,
                        )
                    } else {
                        val result = transport.importCredentials(
                            context = context,
                            credentialTypes = requestedCredentialTypes,
                        )
                        debugPayloadSink.value = result.debugPayloadOrNull()
                        handleTransportResult(
                            result = result,
                            cxfImportService = cxfImportService,
                        )
                    }
                }.getOrElse {
                    Step.Error(
                        message = translate(Res.string.credential_exchange_import_error_unknown),
                        retry = Retry.Transfer,
                    )
                }
            }
            steps.move(loading, step)
        }
    }

    /**
     * Forgets the received payload, so a review that is over does not keep a
     * plaintext vault in memory.
     */
    fun dropPayload() {
        debugPayloadSink.value = null
    }
}

/**
 * Turns the transport outcome into the next flow step: a cancellation quietly
 * returns to the idle step, a failure explains itself, and a payload is
 * parsed off the main thread into either the review or the empty step.
 */
private suspend fun RememberStateFlowScope.handleTransportResult(
    result: CredentialExchangeImportTransportResult,
    cxfImportService: CxfImportService,
): Step = when (result) {
    is CredentialExchangeImportTransportResult.Cancelled -> Step.Start

    is CredentialExchangeImportTransportResult.Failure -> Step.Error(
        message = translate(result.kind.messageRes()),
        retry = Retry.Transfer,
    )

    is CredentialExchangeImportTransportResult.Success -> {
        val parsed = withContext(Dispatchers.Default) {
            cxfImportService.parse(
                payload = result.payload,
                now = Clock.System.now(),
            )
        }
        when (parsed) {
            is ImportResult.Failure -> Step.Error(
                message = translate(Res.string.credential_exchange_import_error_parse),
                retry = Retry.Transfer,
            )

            is ImportResult.Success -> planStep(
                plan = parsed.plan,
                sourcePackageName = result.sourcePackageName,
                emptyMessage = translate(Res.string.credential_exchange_import_empty_label),
            )
        }
    }
}

private fun CredentialExchangeImportTransportResult.Failure.Kind.messageRes() = when (this) {
    CredentialExchangeImportTransportResult.Failure.Kind.NoExportingProviders ->
        Res.string.credential_exchange_import_error_no_providers

    CredentialExchangeImportTransportResult.Failure.Kind.Unavailable ->
        Res.string.credential_exchange_import_error_unavailable

    CredentialExchangeImportTransportResult.Failure.Kind.InvalidData ->
        Res.string.credential_exchange_import_error_parse

    CredentialExchangeImportTransportResult.Failure.Kind.Unknown ->
        Res.string.credential_exchange_import_error_unknown
}
