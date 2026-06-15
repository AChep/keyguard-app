package com.artemchep.keyguard.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.PasskeyBeginGetUnlockFlow
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.wear.feature.WearCreateVaultScreen
import com.artemchep.keyguard.wear.feature.WearLoadingScreen
import com.artemchep.keyguard.wear.feature.WearUnlockVaultScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.kodein.di.instance

@RequiresApi(34)
class WearPasskeyGetUnlockActivity : WearCredentialProviderActivity() {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, WearPasskeyGetUnlockActivity::class.java)
    }

    private val getVaultSession by instance<GetVaultSession>()

    private val passkeyBeginGetUnlockFlow by instance<PasskeyBeginGetUnlockFlow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened wear passkey get-unlock activity")
        val startedAt = Clock.System.now()

        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()
            val userVerified = session.createdAt > startedAt &&
                    session.origin is MasterSession.Key.Authenticated
            val response = runCatching {
                withContext(Dispatchers.Default) {
                    processUnlockedVault(
                        session = session,
                        userVerified = userVerified,
                    )
                }
            }.getOrElse {
                recordException(it)

                val intent = Intent().apply {
                    val exception = it as? GetCredentialException
                        ?: GetCredentialUnknownException()
                    PendingIntentHandler.setGetCredentialException(
                        intent = this,
                        exception = exception,
                    )
                }
                setResult(Activity.RESULT_OK, intent)
                finish()
                return@launch
            }
            val intent = Intent().apply {
                PendingIntentHandler.setBeginGetCredentialResponse(
                    intent = this,
                    response = response,
                )
            }
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    @Composable
    override fun Content() {
        ManualAppScreen { vaultState ->
            when (vaultState) {
                is VaultState.Create -> WearCreateVaultScreen(vaultState)
                is VaultState.Unlock -> WearUnlockVaultScreen(vaultState)
                is VaultState.Loading -> WearLoadingScreen()
                is VaultState.Main -> WearLoadingScreen()
            }
        }
    }

    private suspend fun processUnlockedVault(
        session: MasterSession.Key,
        userVerified: Boolean,
    ) = passkeyBeginGetUnlockFlow.processUnlockedVault(
        session = session,
        request = requireNotNull(
            PendingIntentHandler.retrieveBeginGetCredentialRequest(intent),
        ) {
            "Begin get request from framework is empty."
        },
        userVerified = userVerified,
    )
}
