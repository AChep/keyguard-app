package com.artemchep.keyguard.android.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.artemchep.keyguard.android.CredentialProviderGetRequestHandler
import com.artemchep.keyguard.android.CredentialProviderPlatformConfig
import com.artemchep.keyguard.android.createCredentialProviderPendingIntent
import com.artemchep.keyguard.android.filterCredentialProviderBeginGetOptions
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combineIo
import com.artemchep.keyguard.common.io.dispatchOn
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

@RequiresApi(34)
open class KeyguardCredentialService : CredentialProviderService(), DIAware {
    private val job = Job()

    private val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = Dispatchers.Main + job
    }

    override val di by closestDI { this }

    private val getCanWrite by instance<GetCanWrite>()

    private val getPasskeysEnabled by instance<GetAutofillPasskeysEnabled>()

    private val getPasswordsEnabled by instance<GetAutofillPasswordsEnabled>()

    private val credentialProviderPlatformConfig by instance<CredentialProviderPlatformConfig>()

    private val credentialProviderGetRequestHandler by instance<CredentialProviderGetRequestHandler>()

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) {
            return
        }

        val requestJob = ioEffect {
            recordLog("Got begin create credential request")
            val shouldSkip = shouldSkipCreateRequest(request)
            if (shouldSkip) {
                val e = CreateCredentialUnknownException()
                throw e
            }

            ioEffect { processCreateCredentialsRequest(request) }
                .crashlyticsTap { e ->
                    e.takeIf { it !is CreateCredentialException }
                }
                .bind()
        }
            .dispatchOn(Dispatchers.Default)
            .effectTap(Dispatchers.Main) { response ->
                if (cancellationSignal.isCanceled) {
                    return@effectTap
                }

                callback.onResult(response)
            }
            // Something went wrong, report back the
            // status to the credentials manager.
            .handleErrorTap {
                if (cancellationSignal.isCanceled) {
                    return@handleErrorTap
                }

                val e = it as? CreateCredentialException
                    ?: CreateCredentialUnknownException()
                callback.onError(e)
            }
            .attempt()
            .launchIn(scope)
        cancellationSignal.setOnCancelListener {
            requestJob.cancel()
        }
    }

    private suspend fun processCreateCredentialsRequest(
        request: BeginCreateCredentialRequest,
    ): BeginCreateCredentialResponse {
        val createCredentialActivityClass =
            credentialProviderPlatformConfig.createCredentialActivityClass
                ?: throw CreateCredentialUnknownException()
        return when (request) {
            is BeginCreatePublicKeyCredentialRequest,
            is BeginCreatePasswordCredentialRequest,
                -> {
                val pendingIntent =
                    createCreateCredentialPendingIntent(createCredentialActivityClass)
                val entries = listOf(
                    CreateEntry(
                        accountName = getString(R.string.app_name),
                        pendingIntent = pendingIntent,
                    ),
                )
                BeginCreateCredentialResponse(
                    createEntries = entries,
                )
            }

            else -> {
                throw CreateCredentialUnknownException()
            }
        }
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) {
            return
        }

        val requestJob = ioEffect {
            recordLog("Got begin get credential request")
            val shouldSkip = shouldSkipGetRequest(request)
            if (shouldSkip) {
                val e = GetCredentialUnknownException()
                throw e
            }

            ioEffect {
                credentialProviderGetRequestHandler.process(request)
            }
                .crashlyticsTap { e ->
                    e.takeIf { it !is GetCredentialException }
                }
                .bind()
        }
            .dispatchOn(Dispatchers.Default)
            .effectTap(Dispatchers.Main) { response ->
                if (cancellationSignal.isCanceled) {
                    return@effectTap
                }

                callback.onResult(response)
            }
            // Something went wrong, report back the
            // status to the credentials manager.
            .handleErrorTap {
                if (cancellationSignal.isCanceled) {
                    return@handleErrorTap
                }

                val e = it as? GetCredentialException
                    ?: GetCredentialUnknownException()
                callback.onError(e)
            }
            .attempt()
            .launchIn(scope)
        cancellationSignal.setOnCancelListener {
            requestJob.cancel()
        }
    }

    private suspend fun shouldSkipCreateRequest(request: BeginCreateCredentialRequest): Boolean {
        val canCreate = getCanWrite().toIO().bind()
        if (!canCreate) {
            return true
        }

        return when (request) {
            is BeginCreatePublicKeyCredentialRequest -> !getPasskeysEnabled().toIO().bind()
            is BeginCreatePasswordCredentialRequest -> !getPasswordsEnabled().toIO().bind()
            else -> true
        }
    }

    private suspend fun shouldSkipGetRequest(request: BeginGetCredentialRequest): Boolean {
        val passkeysEnabled = getPasskeysEnabled().toIO().bind()
        val passwordsEnabled = getPasswordsEnabled().toIO().bind()
        val remainingOptions = filterCredentialProviderBeginGetOptions(
            options = request.beginGetCredentialOptions,
            passkeysEnabled = passkeysEnabled,
            passwordsEnabled = passwordsEnabled,
        )
        return remainingOptions.isEmpty()
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        recordLog("Got clear credential state request")
        callback.onError(ClearCredentialUnknownException())
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    //
    // Intents
    //

    private fun createCreateCredentialPendingIntent(
        activityClass: Class<out android.app.Activity>,
    ): PendingIntent {
        val intent = Intent(this, activityClass)
        return createCredentialProviderPendingIntent(
            context = this,
            intent = intent,
        )
    }
}
