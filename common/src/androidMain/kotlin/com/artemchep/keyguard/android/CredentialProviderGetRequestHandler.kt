package com.artemchep.keyguard.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.autofill_open_keyguard
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import org.jetbrains.compose.resources.getString as getComposeString

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialProviderGetRequestHandler(
    private val context: Context,
    private val getVaultSession: GetVaultSession,
    private val passkeyBeginGetUnlockFlow: PasskeyBeginGetUnlockFlow,
    private val credentialProviderPlatformConfig: CredentialProviderPlatformConfig,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
        getVaultSession = directDI.instance(),
        passkeyBeginGetUnlockFlow = directDI.instance(),
        credentialProviderPlatformConfig = directDI.instance(),
    )

    suspend fun process(
        request: BeginGetCredentialRequest,
    ): BeginGetCredentialResponse {
        return when (val session = getVaultSession().first()) {
            is MasterSession.Key -> passkeyBeginGetUnlockFlow.processUnlockedVault(
                session = session,
                request = request,
                userVerified = false,
            )

            is MasterSession.Empty -> {
                val title = getComposeString(Res.string.autofill_open_keyguard)
                val pendingIntent = createGetUnlockCredentialPendingIntent()
                BeginGetCredentialResponse(
                    authenticationActions = listOf(
                        AuthenticationAction(
                            title = title,
                            pendingIntent = pendingIntent,
                        ),
                    ),
                )
            }

            else -> throw GetCredentialUnknownException()
        }
    }

    private fun createGetUnlockCredentialPendingIntent(): PendingIntent {
        val intent = Intent(
            context,
            credentialProviderPlatformConfig.getUnlockCredentialActivityClass,
        )
        return createCredentialProviderPendingIntent(
            context = context,
            intent = intent,
        )
    }
}
