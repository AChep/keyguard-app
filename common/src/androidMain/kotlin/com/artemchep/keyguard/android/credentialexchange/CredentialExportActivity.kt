package com.artemchep.keyguard.android.credentialexchange

import android.content.Intent
import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.credentials.providerevents.IntentHandler
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.credentials.providerevents.exception.ImportCredentialsUnknownCallerException
import androidx.credentials.providerevents.exception.ImportCredentialsUnknownErrorException
import androidx.credentials.providerevents.transfer.ImportCredentialsResponse
import androidx.credentials.providerevents.transfer.ProviderImportCredentialsRequest
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.BaseActivity
import com.artemchep.keyguard.android.CredentialScaffold
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRepository
import com.artemchep.keyguard.common.service.credentialexchange.CxfExportService
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAccount
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.credentialexchange.export.CredentialExchangeExportResult
import com.artemchep.keyguard.feature.credentialexchange.export.CredentialExchangeExportRoute
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DIAware
import org.kodein.di.compose.withDI
import org.kodein.di.instance

/**
 * Handles the Android 15+/GMS "Transfer passwords & passkeys" import request
 * ([androidx.credentials.providerevents]) by answering with an unencrypted CXF
 * v1.0 document built from the vault.
 *
 * The shared [CredentialExchangeExportRoute] screen owns the review (unlock →
 * user verification → review) and hands back the selected accounts; this activity
 * owns the transport, building and encoding the document and writing it to the
 * caller. The payload contents are never logged.
 */
class CredentialExportActivity : BaseActivity(), DIAware {
    companion object {
        private const val TAG = "CredentialExportActivity"

        /**
         * The expected caller of this exported activity — the GMS
         * credential-transfer broker.
         *
         * Only the cheap half of the caller check: a third party can present this
         * package name to an exported activity by relaying through GMS with
         * `FLAG_ACTIVITY_FORWARD_RESULT`. The check that actually holds is the
         * random per-account credential id the request carries, resolved in
         * [onCreate].
         */
        private const val CALLER_PACKAGE_GMS = "com.google.android.gms"
    }

    private val cxfExportService by instance<CxfExportService>()

    private val logRepository by instance<LogRepository>()

    private val exposedAccountRepository by instance<ExposedAccountRepository>()

    private val getVaultSession by instance<GetVaultSession>()

    private val importRequest: ProviderImportCredentialsRequest? by lazy {
        // The activity is exported, so the extras may be a crafted third-party
        // payload. The androidx parser only guards against JSONException — an
        // empty credentialTypes list, say, throws IllegalArgumentException — so
        // any non-fatal parse failure folds into the same "nothing to answer"
        // path as a missing request.
        parseUntrustedRequest(logRepository, TAG) {
            IntentHandler.retrieveProviderImportCredentialsRequest(intent)
        }
    }

    /**
     * `true` once a final result has been written, so any later completion (e.g.
     * a deny after a confirm, or a back press) becomes a no-op.
     */
    private var responded = false

    private val uiStateSink = mutableStateOf<UiState>(UiState.Loading)

    /**
     * The display name of the application the vault would be handed to, shown
     * on the consent screen. `null` until it has been resolved.
     */
    private val importerNameSink = mutableStateOf<String?>(null)

    /**
     * The label of the account the picked entry addresses, shown on the consent
     * screen so the user knows which account is about to be read.
     *
     * `null` when the entry's account is no longer mirrored, i.e. hidden or deleted —
     * the screen renders an explanation in that case, so the subtitle simply omits
     * the name rather than inventing one.
     */
    private val accountNameSink = mutableStateOf<String?>(null)

    private sealed interface UiState {
        data object Loading : UiState

        data class Review(
            val args: CredentialExchangeExportRoute.Args,
        ) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened credential export activity")

        // Default to a cancellation result, so any exit path (including the
        // activity being finished by the system) still answers the caller.
        setCancelledResult()

        // Read before anything can unlock the vault, so a session created after it
        // is provably a session this request caused.
        val startedAt = Clock.System.now()

        val request = importRequest
        if (request == null) {
            // There is nothing to answer; finish silently.
            recordLog("Credential export request from framework is empty.")
            finish()
            return
        }

        // The activity is exported, so validating the caller is mandatory.
        if (callingPackage != CALLER_PACKAGE_GMS) {
            recordLog("Rejected credential export request from an unknown caller.")
            respondWithException(ImportCredentialsUnknownCallerException())
            finish()
            return
        }

        // The second half of the platform's caller check: the request carries
        // back the secret id the export entry was registered with, and only a
        // request originating from that registration can know it. The id also
        // identifies WHICH account's entry the user picked. Resolving it touches
        // disk, so the rest of the setup continues in a coroutine.
        //
        // This read has to work with the vault still locked, which is why the
        // mapping lives in the exposed database rather than the vault.
        lifecycleScope.launch {
            val entry = runCatching { exposedAccountRepository.resolveEntry(request.credId).bind() }
                .getOrElse { e ->
                    e.throwIfFatalOrCancellation()
                    recordException(e)
                    null
                }
            if (entry == null) {
                recordLog("Rejected credential export request with an unknown credential id.")
                respondWithException(ImportCredentialsUnknownCallerException())
                finish()
                return@launch
            }

            // The androidx ImportCredentialsRequest guarantees a non-empty
            // credentialTypes set, of which unknown values must be ignored and
            // only the requested types returned (CXP §3.2). `parseAll` does the
            // ignoring; when nothing survives it, the empty set acts as an
            // export-nothing filter rather than a full-vault fallback.
            val requestedTypes = CxfCredentialType.parseAll(request.request.credentialTypes)

            // Suspends until the vault is unlocked; until then `uiStateSink` stays
            // Loading and only the unlocked branch of `ManualAppScreen` ever reads
            // it, so the user sees the unlock screen regardless. It has to come
            // after the entry check above — that one must be able to reject an
            // unknown credential id without making anyone unlock first.
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            val routeArgs = CredentialExchangeExportRoute.Args(
                requestedTypes = requestedTypes,
                accountId = AccountId(entry.accountId),
                userVerified = isUserVerifiedBySession(
                    session = session,
                    startedAt = startedAt,
                ),
                onComplete = ::onComplete,
            )
            importerNameSink.value = resolveImporterName(request.callingAppInfo.packageName)
            // `null` when the entry is ours but its account is no longer mirrored —
            // hidden or deleted. The screen explains that; the subtitle just omits
            // the account name.
            accountNameSink.value = entry.account?.label
            uiStateSink.value = UiState.Review(routeArgs)
        }
    }

    /**
     * A human-readable name for the application
     * that will receive the vault.
     */
    private suspend fun resolveImporterName(
        packageName: String,
    ): String = withContext(Dispatchers.IO) {
        runCatchingNonFatal {
            packageManager
                .getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
                .toString()
                .takeIf { it.isNotBlank() }
        }.getOrNull() ?: packageName
    }

    private fun onComplete(
        result: CredentialExchangeExportResult,
    ) {
        when (result) {
            is CredentialExchangeExportResult.Cancel -> {
                respondWithException(ImportCredentialsCancellationException())
                finish()
            }

            is CredentialExchangeExportResult.Complete -> {
                lifecycleScope.launch {
                    respondWithAccounts(result.accounts)
                }
            }

            is CredentialExchangeExportResult.Fail -> {
                // Unlike Cancel, this arrives from a flow transform on
                // Dispatchers.Default, so hop back to the main thread before
                // touching the result Intent. `markResponded = false` leaves the
                // screen up for the user to read the error while the failure
                // result stays armed for whatever finally dismisses the activity.
                lifecycleScope.launch {
                    respondWithException(
                        exception = ImportCredentialsUnknownErrorException(),
                        markResponded = false,
                    )
                }
            }
        }
    }

    private suspend fun respondWithAccounts(
        accounts: List<CxfAccount>,
    ) {
        val request = importRequest
            ?: return
        // A `null` result means the build failed and the failure exception has
        // already been reported and the activity finished.
        val responseJson = buildResponseJson(accounts)
            ?: return

        // Never log the payload; counts only.
        logRepository.post(
            tag = TAG,
            message = "Exported ${accounts.size} account(s) with " +
                "${accounts.sumOf { it.items.size }} item(s).",
            level = LogLevel.INFO,
        )

        if (!responded) {
            respondWithResponseJson(request, responseJson)
        }
    }

    /**
     * Hands the encoded document [responseJson] to the caller by writing it into
     * the FileProvider uri of [request]. Reports a failure to the caller instead
     * of letting it escape; fatal errors and cancellation are rethrown to
     * preserve the surrounding coroutine semantics.
     */
    private suspend fun respondWithResponseJson(
        request: ProviderImportCredentialsRequest,
        responseJson: String,
    ) {
        val resultIntent = Intent()
        // This writes the whole document through a ContentResolver into the
        // importer's FileProvider uri — a multi-megabyte cross-process write for
        // a large vault, hence the IO dispatcher. A revoked grant, a full disk or
        // a dead peer all raise here, and nothing upstream catches: `onComplete`
        // launches this in `lifecycleScope`, so an escape would crash the app
        // instead of answering the caller.
        runCatching {
            withContext(Dispatchers.IO) {
                IntentHandler.setImportCredentialsResponse(
                    this@CredentialExportActivity,
                    request.uri,
                    resultIntent,
                    ImportCredentialsResponse(responseJson),
                )
            }
        }.getOrElse { e ->
            e.throwIfFatalOrCancellation()
            recordException(e)
            // `responded` is still `false` here, so this failure replaces the
            // armed cancellation instead of the caller being told the user
            // backed out.
            respondWithException(ImportCredentialsUnknownErrorException())
            finish()
            return
        }
        // Marked only now that the response is in the caller's hands: any
        // earlier and `respondWithException` is locked out of the failure path
        // above, the only way to report the write going wrong.
        responded = true
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Builds the encoded CXF document for [accounts], or returns `null` when the
     * build fails. On failure the user-facing exception is reported and the
     * activity is finished before returning; fatal errors and cancellation are
     * rethrown to preserve the surrounding coroutine semantics.
     */
    private suspend fun buildResponseJson(
        accounts: List<CxfAccount>,
    ): String? = runCatching {
        withContext(Dispatchers.Default) {
            val exporterDisplayName = applicationInfo
                .loadLabel(packageManager)
                .toString()
            val document = cxfExportService.buildDocument(
                accounts = accounts,
                exporterRpId = packageName,
                exporterDisplayName = exporterDisplayName,
                timestamp = Clock.System.now(),
            )
            cxfExportService.encode(document)
        }
    }.getOrElse { e ->
        e.throwIfFatalOrCancellation()
        recordException(e)
        respondWithException(ImportCredentialsUnknownErrorException())
        finish()
        null
    }

    private class CredentialExportHostRoute(
        private val args: CredentialExchangeExportRoute.Args,
    ) : Route {
        @Composable
        override fun Content() {
            val route = remember(args) {
                CredentialExchangeExportRoute(
                    args = args,
                )
            }
            NavigationRouter(
                id = "credential_exchange_export:stack",
                initial = route,
            ) { entries ->
                NavigationNode(entries)
            }
        }
    }

    private fun setCancelledResult() {
        respondWithException(
            exception = ImportCredentialsCancellationException(),
            markResponded = false,
        )
    }

    private fun respondWithException(
        exception: ImportCredentialsException,
        markResponded: Boolean = true,
    ) {
        if (responded) {
            return
        }
        if (markResponded) {
            responded = true
        }
        val resultIntent = Intent()
        IntentHandler.setImportCredentialsException(resultIntent, exception)
        setResult(RESULT_OK, resultIntent)
    }

    @Composable
    override fun Content() {
        val context by rememberUpdatedState(newValue = LocalContext.current)
        CredentialScaffold(
            onCancel = {
                context.closestActivityOrNull?.finish()
            },
            titleText = stringResource(Res.string.credential_exchange_export_header_title),
            subtitle = {
                val importerName = importerNameSink.value
                val accountName = accountNameSink.value
                Text(
                    text = when {
                        importerName != null -> stringResource(
                            Res.string.credential_exchange_export_header_text_named,
                            importerName,
                        )

                        else -> stringResource(Res.string.credential_exchange_export_header_text)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 3,
                )
            },
        ) {
            ManualAppScreen { vaultState ->
                when (vaultState) {
                    is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                    is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                    is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                    is VaultState.Main -> {
                        when (val state = uiStateSink.value) {
                            is UiState.Loading -> {
                                ManualAppScreenOnLoading()
                            }

                            is UiState.Review -> {
                                withDI(vaultState.di) {
                                    val route = remember(state.args) {
                                        CredentialExportHostRoute(
                                            args = state.args,
                                        )
                                    }
                                    NavigationNode(
                                        id = "credential_exchange_export",
                                        route = route,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether the vault session itself already proves the user is present, which lets
 * the export screen skip its own verification gate.
 *
 * True only when the session was created *after* this request arrived **and** the
 * master password was typed rather than restored: the user authenticated seconds
 * ago, in response to this very transfer, so asking again asks the same question
 * twice. A session restored from disk ([MasterSession.Key.Persisted]) proves
 * nothing about who is holding the phone, and one that predates [startedAt] was not
 * created for this request — both keep the gate.
 *
 * Mirrors the rule `PasskeyGetActivity` applies to `requiresUserVerification`.
 */
internal fun isUserVerifiedBySession(
    session: MasterSession.Key,
    startedAt: Instant,
): Boolean = session.createdAt > startedAt &&
        session.origin is MasterSession.Key.Authenticated

/**
 * Runs [parse] over input another application handed over, folding a non-fatal
 * failure into `null` and a line in the local log.
 *
 * Deliberately not a [com.artemchep.keyguard.platform.recordException] non-fatal.
 * [CredentialExportActivity] is exported, so any installed app can start it with a
 * crafted request — an `ImportCredentialsRequest` carrying no credential types, say,
 * fails an `init` requirement that the androidx parser does not catch — and the parse
 * runs before the credential id, the check that actually gates this activity, has
 * been read. Reporting the failure would let any installed app drive one crash-report
 * non-fatal per launch, and a request that does not parse is a property of the
 * crafted input rather than a Keyguard defect worth a report.
 *
 * Fatal errors and cancellation still propagate.
 */
internal inline fun <T> parseUntrustedRequest(
    logRepository: LogRepository,
    tag: String,
    parse: () -> T,
): T? = runCatchingNonFatal(parse)
    .getOrElse { e ->
        logRepository.post(
            tag = tag,
            message = "Failed to parse the request: ${e::class.simpleName}",
            level = LogLevel.WARNING,
        )
        null
    }
