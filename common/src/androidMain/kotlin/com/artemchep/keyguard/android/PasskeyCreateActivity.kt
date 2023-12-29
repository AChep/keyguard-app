package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddPasskeyCipherRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.AddPasskeyCipher
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnMain
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.platform.crashlyticsIsEnabled
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.CreatePasskey
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.kodein.di.*

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateActivity : BaseActivity(), DIAware {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, PasskeyCreateActivity::class.java)
    }

    private val getVaultSession by instance<GetVaultSession>()

    private val json by instance<Json>()

    private val passkeyBeginGetRequest by instance<PasskeyCreateRequest>()

    private val sdfsdff by lazy {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        requireNotNull(request) {
            "Create request from framework is empty."
        }
    }

    private val createPasskeyData by lazy {
        kotlin.runCatching {
            val r = sdfsdff.callingRequest as CreatePublicKeyCredentialRequest
            val data = json.decodeFromString<CreatePasskey>(r.requestJson)
            data
        }.getOrNull()
    }

    private val eheheState = mutableStateOf<Ahhehe>(Ahhehe.Loading)

    private sealed interface Ahhehe {
        data object Loading : Ahhehe

        /**
         * A screen that asks a user to
         * authenticate himself.
         */
        class PickCipher(
            val onComplete: (DSecret) -> Unit,
        ) : Ahhehe

        /**
         * A screen that shows an error to a user and
         * offers a button to close the app.
         */
        class Error(
            val title: String? = null,
            val message: String,
            val onFinish: () -> Unit,
        ) : Ahhehe
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = Clock.System.now()
        recordLog("Opened passkey create activity")

        // Observe the vault session to detect when a user
        // unlocks the vault.
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            val cipherState = MutableStateFlow<DSecret?>(null)
            eheheState.value = Ahhehe.PickCipher {
                cipherState.value = it
            }
            // Wait till the user picks a cipher to
            // save the passkey to.
            val cipher = cipherState
                .filterNotNull()
                .first()
            eheheState.value = Ahhehe.Loading

            val (response, local) = runCatching {
                PasskeyUtils.withProcessingMinTime {
                    processUnlockedVault(
                        session = session,
                        userVerified = true,
                    )
                }
            }.getOrElse {
                recordException(it)

                val intent = Intent().apply {
                    val e = it as? CreateCredentialException
                        ?: CreateCredentialUnknownException()
                    PendingIntentHandler.setCreateCredentialException(
                        intent = this,
                        exception = e,
                    )
                }
                setResult(Activity.RESULT_OK, intent)

                // Show the error to a user
                val uiState = Ahhehe.Error(
                    title = Res.strings.error_failed_create_passkey
                        .getString(this@PasskeyCreateActivity),
                    message = it.localizedMessage
                        ?: it.message
                        ?: "Something went wrong",
                    onFinish = {
                        finish()
                    },
                )
                eheheState.value = uiState
                return@launch // end
            }

            val request = AddPasskeyCipherRequest(
                cipherId = cipher.id,
                data = local,
            )
            val addpasskey = session.di.direct.instance<AddPasskeyCipher>()
            val addpasskeyResult = addpasskey(request)
                .attempt()
                .bind()
            addpasskeyResult.fold(
                ifLeft = {
                    val intent = Intent().apply {
                        val e = it as? CreateCredentialException
                            ?: CreateCredentialUnknownException("Failed to add the passkey to the vault.")
                        PendingIntentHandler.setCreateCredentialException(
                            intent = this,
                            exception = e,
                        )
                    }
                    setResult(Activity.RESULT_OK, intent)

                    // Show the error to a user
                    val uiState = Ahhehe.Error(
                        title = Res.strings.error_failed_create_passkey
                            .getString(this@PasskeyCreateActivity),
                        message = it.localizedMessage
                            ?: it.message
                            ?: "Something went wrong",
                        onFinish = {
                            finish()
                        },
                    )
                    eheheState.value = uiState
                },
                ifRight = {
                    val intent = Intent().apply {
                        PendingIntentHandler.setCreateCredentialResponse(
                            intent = this,
                            response = response,
                        )
                    }
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                },
            )
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
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    ) {
                        Text(
                            text = stringResource(Res.strings.passkey_create_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current
                                .combineAlpha(MediumEmphasisAlpha),
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                        val data = createPasskeyData
                        if (data != null) {
                            Row {
                                Text(
                                    text = data.user.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                )
                                Text(
                                    modifier = Modifier
                                        .weight(1f, fill = false),
                                    text = "@" + data.rp.id,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LocalContentColor.current
                                        .combineAlpha(MediumEmphasisAlpha),
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Text(
                                text = "Failed to parse a create request data",
                                style = MaterialTheme.typography.titleMedium,
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 2,
                            )
                        }
                    }

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
                            text = stringResource(Res.strings.cancel),
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
                        val state = eheheState.value
                        when (state) {
                            is Ahhehe.Loading -> {
                                val fakeLoadingState = VaultState.Loading
                                ManualAppScreenOnLoading(fakeLoadingState)
                            }

                            is Ahhehe.Error -> {
                                PasskeyError(
                                    title = state.title,
                                    message = state.message,
                                    onFinish = state.onFinish,
                                )
                            }

                            is Ahhehe.PickCipher -> {
                                val updatedOnAuthenticated by rememberUpdatedState(state.onComplete)
                                val appMode = remember {
                                    AppMode.SavePasskey(
                                        rpId = createPasskeyData?.rp?.id,
                                        onComplete = { cipher ->
                                            updatedOnAuthenticated.invoke(cipher)
                                        },
                                    )
                                }
                                CompositionLocalProvider(
                                    LocalAppMode provides appMode,
                                ) {
                                    ManualAppScreenOnMain(vaultState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun processUnlockedVault(
        session: MasterSession.Key,
        userVerified: Boolean,
    ): Pair<CreatePublicKeyCredentialResponse, AddPasskeyCipherRequest.Data> {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        requireNotNull(request) {
            "Create request from framework is empty."
        }

        return passkeyBeginGetRequest.processGetCredentialsRequest(
            request = request,
            userVerified = userVerified,
        )
    }
}
