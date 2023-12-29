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
import com.artemchep.keyguard.android.PasskeyBeginGetRequest
import com.artemchep.keyguard.android.PasskeyCreateActivity
import com.artemchep.keyguard.android.PasskeyGetUnlockActivity
import com.artemchep.keyguard.android.PendingIntents
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import com.artemchep.keyguard.platform.recordLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

@RequiresApi(34)
class KeyguardCredentialService : CredentialProviderService(), DIAware {
    private val job = Job()

    private val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = Dispatchers.Main + job
    }

    override val di by closestDI { this }

    private val getCanWrite by instance<GetCanWrite>()

    private val sessionFlow by lazy {
        val getVaultSession: GetVaultSession by di.instance()
        getVaultSession()
            .distinctUntilChanged()
            .shareIn(
                scope,
                SharingStarted.WhileSubscribed(KeyguardAutofillService.KEEP_CIPHERS_IN_MEMORY_FOR),
                replay = 1,
            )
    }

    private val passkeyBeginGetRequest by instance<PasskeyBeginGetRequest>()

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        ioEffect {
            recordLog("Got begin create credential request")
            // Check if you can modify items in the vault, if
            // no then ignore the passkeys request.
            val canWrite = getCanWrite()
                .first()
            if (!canWrite) {
                val e = CreateCredentialUnknownException()
                throw e
            }

            val response = ioEffect { processCreateCredentialsRequest(request) }
                .crashlyticsTap { e ->
                    e.takeIf { it !is CreateCredentialException }
                }
                .bind()
            callback.onResult(response)
        }
            // Something went wrong, report back the
            // status to the credentials manager.
            .handleErrorTap {
                val e = it as? CreateCredentialException
                    ?: CreateCredentialUnknownException()
                callback.onError(e)
            }
            .attempt()
            .launchIn(scope)
    }

    private suspend fun processCreateCredentialsRequest(
        request: BeginCreateCredentialRequest,
    ): BeginCreateCredentialResponse {
        return when (request) {
            is BeginCreatePublicKeyCredentialRequest -> {
                val title = getString(R.string.app_name)
                val pi = createCreatePasskeyPendingIntent()
                val entries = listOf(
                    CreateEntry(
                        accountName = title,
                        pendingIntent = pi,
                    ),
                )
                BeginCreateCredentialResponse(
                    createEntries = entries,
                )
            }

            is BeginCreatePasswordCredentialRequest -> {
                // TODO: Add a support for storing the passwords. This
                //  requires implementing:
                //  https://github.com/AChep/keyguard-app/issues/5
                throw CreateCredentialUnknownException()
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
        ioEffect {
            recordLog("Got begin get credential request")

            when (val session = sessionFlow.first()) {
                is MasterSession.Key -> {
                    val cipherHistoryOpenedRepository =
                        session.di.direct.instance<CipherHistoryOpenedRepository>()
                    val getCiphers = session.di.direct.instance<GetCiphers>()

                    val ciphers = getCiphers()
                        .map { ciphers ->
                            ciphers
                                .filter { it.deletedDate == null }
                        }
                        .first()
                    val response = ioEffect {
                        passkeyBeginGetRequest.processGetCredentialsRequest(
                            cipherHistoryOpenedRepository = cipherHistoryOpenedRepository,
                            request = request,
                            ciphers = ciphers,
                        )
                    }
                        .crashlyticsTap { e ->
                            e.takeIf { it !is GetCredentialException }
                        }
                        .bind()
                    callback.onResult(response)
                }

                // Need to authenticate a user to unlock
                // the database first.
                is MasterSession.Empty -> {
                    val title = getString(R.string.autofill_open_keyguard)
                    val pi = createGetUnlockPasskeyPendingIntent()
                    val actions = listOf(
                        AuthenticationAction(
                            title = title,
                            pendingIntent = pi,
                        ),
                    )
                    callback.onResult(
                        BeginGetCredentialResponse(
                            authenticationActions = actions,
                        ),
                    )
                }
            }
        }
            // Something went wrong, report back the
            // status to the credentials manager.
            .handleErrorTap {
                val e = it as? GetCredentialException
                    ?: GetCredentialUnknownException()
                callback.onError(e)
            }
            .attempt()
            .launchIn(scope)
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

    private fun createGetUnlockPasskeyPendingIntent(
    ): PendingIntent {
        val intent = PasskeyGetUnlockActivity.getIntent(
            context = this,
        )
        return createPendingIntent(intent)
    }

    private fun createCreatePasskeyPendingIntent(
    ): PendingIntent {
        val intent = PasskeyCreateActivity.getIntent(
            context = this,
        )
        return createPendingIntent(intent)
    }

    private fun createPendingIntent(
        intent: Intent,
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val rc = getNextPendingIntentRequestCode()
        return PendingIntent.getActivity(this, rc, intent, flags)
    }

    private fun getNextPendingIntentRequestCode() = PendingIntents.credential.obtainId()

}
