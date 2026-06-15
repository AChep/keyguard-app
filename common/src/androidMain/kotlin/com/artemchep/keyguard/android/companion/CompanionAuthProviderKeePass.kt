package com.artemchep.keyguard.android.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.prepareKeePassDatabase
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionKeePassPayload
import com.artemchep.keyguard.feature.auth.keepass.LoginContent
import com.artemchep.keyguard.feature.auth.keepass.LoginContentSkeleton as KeePassLoginContentSkeleton
import com.artemchep.keyguard.feature.auth.keepass.produceKeePassLoginScreenState
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import kotlinx.coroutines.Dispatchers
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

internal data class CompanionKeePassLoginRoute(
    val request: CompanionAuthActivity.Request,
) : RouteForResult<Unit> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<Unit>,
    ) {
        CompanionKeePassLoginScreen(
            request = request,
            transmitter = transmitter,
        )
    }
}

@Composable
private fun CompanionKeePassLoginScreen(
    request: CompanionAuthActivity.Request,
    transmitter: RouteResultTransmitter<Unit>,
) = with(localDI().direct) {
    val companionAddKeePassAccount = remember(request) {
        CompanionKeePassAddAccount(
            request = request,
            companionAuthBridge = instance(),
            fileService = instance(),
        )
    }
    val state = produceKeePassLoginScreenState(
        addKeepassAccount = companionAddKeePassAccount,
        screenKey = "keepasslogin.companion",
    )
    when (state) {
        Loadable.Loading -> {
            KeePassLoginContentSkeleton()
        }

        is Loadable.Ok -> {
            val okState = state.value
            LaunchedEffect(okState) {
                okState.sideEffects.onSuccessFlow.collect {
                    transmitter(Unit)
                }
            }
            LaunchedEffect(okState) {
                okState.sideEffects.onErrorFlow.collect {
                    // The shared screen handles visual error state already.
                }
            }
            FilePickerEffect(okState.sideEffects.filePickerIntentFlow)
            LoginContent(
                loginState = okState,
            )
        }
    }
}

internal class CompanionKeePassAddAccount(
    private val requestId: String,
    private val preflight: suspend (AddKeePassAccountParams) -> Unit,
    private val complete: suspend (
        payload: CompanionKeePassPayload,
        databaseUri: String,
        keyUri: String?,
    ) -> Unit,
    private val notifyError: (CompanionAuthError, String?) -> Unit,
) : AddKeePassAccount {
    constructor(
        request: CompanionAuthActivity.Request,
        companionAuthBridge: CompanionAuthBridgeAndroid,
        fileService: FileService,
    ) : this(
        requestId = request.requestId,
        preflight = { params ->
            prepareKeePassDatabase(
                fileService = fileService,
                params = params,
            )
        },
        complete = { payload, databaseUri, keyUri ->
            companionAuthBridge.completeKeePassOnPhone(
                requestId = request.requestId,
                payload = payload,
                databaseUri = databaseUri,
                keyUri = keyUri,
            )
        },
        notifyError = { error, message ->
            companionAuthBridge.notifyErrorFromPhone(
                requestId = request.requestId,
                provider = request.provider,
                error = error,
                message = message,
            )
        },
    )

    override fun invoke(
        params: AddKeePassAccountParams,
    ) = ioEffect(Dispatchers.IO) {
        preflight(params)
        val payload = CompanionKeePassPayload(
            databaseFileName = params.dbFileName,
            keyFileName = params.keyUri
                ?.toUri()
                ?.lastPathSegment,
            password = params.password,
        )
        runCompanionTransfer(
            notifyError = notifyError,
        ) {
            complete(
                payload,
                params.dbUri,
                params.keyUri,
            )
        }
        AccountId(requestId)
    }
}
