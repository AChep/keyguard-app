package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.kodein.di.*

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyGetUnlockActivity : BaseActivity(), DIAware {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, PasskeyGetUnlockActivity::class.java)
    }

    private val getVaultSession by instance<GetVaultSession>()

    private val passkeyBeginGetRequest by instance<PasskeyBeginGetRequest>()

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened passkey get-unlock activity")
        val startedAt = Clock.System.now()

        // Observe the vault session to detect when a user
        // unlocks the vault.
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()
            val userVerified = session.createdAt > startedAt &&
                    session.origin is MasterSession.Key.Authenticated
            val response = runCatching {
                processUnlockedVault(
                    session = session,
                    userVerified = userVerified,
                )
            }.getOrElse {
                recordException(it)

                // Something went wrong, finish with the
                // exception.
                val intent = Intent().apply {
                    val e = it as? GetCredentialException
                        ?: GetCredentialUnknownException()
                    PendingIntentHandler.setGetCredentialException(
                        intent = this,
                        exception = e,
                    )
                }
                setResult(Activity.RESULT_OK, intent)
                finish()
                return@launch // end
            }
            // If the provider does not have a credential, or an exception to return,
            // provider must call android.app.Activity.setResult with the result code
            // as android.app.Activity.RESULT_CANCELED.
            val status = if (response.credentialEntries.isEmpty()) {
                Activity.RESULT_CANCELED
            } else {
                Activity.RESULT_OK
            }
            val intent = Intent().apply {
                PendingIntentHandler.setBeginGetCredentialResponse(
                    intent = this,
                    response = response,
                )
            }
            setResult(status, intent)
            finish()
        }
    }

    @Composable
    override fun Content() {
        ExtensionScaffold(
            header = {
                Row(
                    modifier = Modifier
                        .padding(
                            start = Dimens.horizontalPadding,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                        text = stringResource(Res.string.autofill_unlock_keyguard),
                        style = MaterialTheme.typography.titleMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )

                    val context by rememberUpdatedState(newValue = LocalContext.current)
                    TextButton(
                        onClick = {
                            context.closestActivityOrNull?.finish()
                        },
                    ) {
                        Icon(Icons.Outlined.Close, null)
                        Spacer(
                            modifier = Modifier
                                .width(Dimens.buttonIconPadding),
                        )
                        Text(
                            text = stringResource(Res.string.cancel),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            },
        ) {
            // Instead of showing a vault to a user, we continue showing the
            // loading screen until we automatically form a response and close
            // the activity.
            ManualAppScreen { vaultState ->
                when (vaultState) {
                    is VaultState.Create -> ManualAppScreenOnCreate(vaultState)
                    is VaultState.Unlock -> ManualAppScreenOnUnlock(vaultState)
                    is VaultState.Loading -> ManualAppScreenOnLoading(vaultState)
                    is VaultState.Main -> {
                        val fakeLoadingState = VaultState.Loading
                        ManualAppScreenOnLoading(fakeLoadingState)
                    }
                }
            }
        }
    }

    private suspend fun processUnlockedVault(
        session: MasterSession.Key,
        userVerified: Boolean,
    ): BeginGetCredentialResponse {
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        requireNotNull(request) {
            "Begin get request from framework is empty."
        }
        val ciphers = kotlin.run {
            val getCiphers = session.di.direct.instance<GetCiphers>()
            val getProfiles = session.di.direct.instance<GetProfiles>()

            val ciphersRawFlow = filterHiddenProfiles(
                getProfiles = getProfiles,
                getCiphers = getCiphers,
                filter = null,
            )
            ciphersRawFlow
                .first()
        }

        return passkeyBeginGetRequest.processGetCredentialsRequest(
            cipherHistoryOpenedRepository = session.di.direct.instance(),
            request = request,
            ciphers = ciphers,
            userVerified = userVerified,
        )
    }
}
