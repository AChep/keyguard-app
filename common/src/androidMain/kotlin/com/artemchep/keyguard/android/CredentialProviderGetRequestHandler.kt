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
import com.artemchep.keyguard.common.exception.credential.CallingAppNotPrivilegedException
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.autofill_authorize_app
import com.artemchep.keyguard.res.autofill_open_keyguard
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString as getComposeString

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialProviderGetRequestHandler(
    private val context: Context,
    private val getVaultSession: GetVaultSession,
    private val passkeyBeginGetUnlockFlow: PasskeyBeginGetUnlockFlow,
    private val credentialProviderPlatformConfig: CredentialProviderPlatformConfig,
) {

    suspend fun process(
        request: BeginGetCredentialRequest,
    ): BeginGetCredentialResponse {
        return when (val session = getVaultSession().first()) {
            is MasterSession.Key -> processUnlockedVault(
                session = session,
                request = request,
            )

            is MasterSession.Empty -> {
                val title = getComposeString(Res.string.autofill_open_keyguard)
                createUnlockResponse(title)
            }

            else -> throw GetCredentialUnknownException()
        }
    }

    private suspend fun processUnlockedVault(
        session: MasterSession.Key,
        request: BeginGetCredentialRequest,
    ): BeginGetCredentialResponse = try {
        passkeyBeginGetUnlockFlow.processUnlockedVault(
            session = session,
            request = request,
            userVerified = false,
        )
    } catch (_: CallingAppNotPrivilegedException) {
        // The calling app is not on the privileged apps list. Instead of
        // silently returning no entries, offer to open Keyguard where a user
        // can grant the privilege. The activity then re-runs the request
        // and returns the entries.
        recordLog("Begin get credential request from a non-privileged app")
        val title = getComposeString(Res.string.autofill_authorize_app)
        createUnlockResponse(title)
    }

    private fun createUnlockResponse(
        title: String,
    ): BeginGetCredentialResponse {
        val pendingIntent = createGetUnlockCredentialPendingIntent()
        return BeginGetCredentialResponse(
            authenticationActions = listOf(
                AuthenticationAction(
                    title = title,
                    pendingIntent = pendingIntent,
                ),
            ),
        )
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
