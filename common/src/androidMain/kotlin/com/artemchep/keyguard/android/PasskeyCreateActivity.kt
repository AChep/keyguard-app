package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnMain
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskey
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.getString as getComposeString
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import org.kodein.di.*
import kotlin.String

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateActivity : BaseActivity(), DIAware {
    companion object {
        fun getIntent(
            context: Context,
        ): Intent = Intent(context, PasskeyCreateActivity::class.java)
    }

    private val getVaultSession by instance<GetVaultSession>()

    private val json by instance<Json>()

    private val createCredentialRequestUtils by instance<PasskeyCreateRequest>()

    private val createCredentialRequest by lazy {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        requireNotNull(request) {
            "Create request from framework is empty."
        }
    }

    private val createCredentialData by lazy {
        kotlin.runCatching {
            when (val callingRequest = createCredentialRequest.callingRequest) {
                is CreatePublicKeyCredentialRequest -> {
                    val data = json.decodeFromString<CreatePasskey>(callingRequest.requestJson)
                    CreateCredentialData.PublicKey(data = data)
                }

                is CreatePasswordRequest -> {
                    val uri = if (!createCredentialRequest.callingAppInfo.isOriginPopulated()) {
                        val packageName = createCredentialRequest.callingAppInfo.packageName
                        "androidapp://$packageName"
                    } else {
                        null
                    }
                    CreateCredentialData.Password(
                        id = callingRequest.id,
                        value = callingRequest.password,
                        uri = uri,
                    )
                }

                else -> null
            }
        }.getOrNull()
    }


    private sealed interface CreateCredentialData {
        data class Password(
            val id: String,
            val value: String,
            val uri: String?,
        ) : CreateCredentialData

        data class PublicKey(
            val data: CreatePasskey,
        ) : CreateCredentialData
    }

    private val uiStateSink = mutableStateOf<UiState>(UiState.Loading)

    private sealed interface UiState {
        data object Loading : UiState

        /**
         * A screen that asks a user to
         * authenticate himself.
         */
        class PickCipher(
            val onComplete: (DSecret) -> Unit,
        ) : UiState

        /**
         * A screen that shows an error to a user and
         * offers a button to close the app.
         */
        class Error(
            val data: UiStateError,
        ) : UiState
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startedAt = Clock.System.now()
        recordLog("Opened credential create activity")

        // Observe the vault session to detect when a user
        // unlocks the vault.
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            val cipherState = MutableStateFlow<DSecret?>(null)
            uiStateSink.value = UiState.PickCipher {
                cipherState.value = it
            }
            // Wait till the user picks a cipher to
            // save the passkey to.
            val cipher = cipherState
                .filterNotNull()
                .first()
            uiStateSink.value = UiState.Loading

            // An attempt of handling the cipher.
            val retrySink = MutableStateFlow(0)
            retrySink
                .onEach { attempt ->
                    handleCredentialRequest(
                        session = session,
                        cipher = cipher,
                        onRetry = {
                            retrySink.value = attempt + 1
                        },
                    )
                }
                .collect()
        }
    }

    private suspend fun handleCredentialRequest(
        session: MasterSession.Key,
        cipher: DSecret,
        onRetry: () -> Unit,
    ) {
        val (response, local) = runCatching {
            PasskeyUtils.withProcessingMinTime {
                processUnlockedVault(
                    session = session,
                    userVerified = true,
                )
            }
        }.getOrElse {
            recordException(it)

            val uiState = getCredentialErrorUiState(
                session = session,
                exception = it,
                onRetry = onRetry,
            )
            uiStateSink.value = uiState
            return // end
        }

        // Add the credential
        val result = kotlin.run {
            val request = AddCredentialCipherRequest(
                cipherId = cipher.id,
                data = local,
            )
            val addCredential = session.di.direct.instance<AddCredentialCipher>()
            addCredential(request)
                .attempt()
                .bind()
        }
        result.fold(
            ifLeft = {
                val uiState = getCredentialErrorUiState(
                    session = session,
                    exception = it,
                    onRetry = onRetry,
                )
                uiStateSink.value = uiState
            },
            ifRight = {
                val intent = Intent().apply {
                    PendingIntentHandler.setCreateCredentialResponse(
                        intent = this,
                        response = response,
                    )
                }
                setResult(RESULT_OK, intent)
                finish()
            },
        )
    }

    private suspend fun getCredentialErrorUiState(
        session: MasterSession.Key,
        exception: Throwable,
        onRetry: () -> Unit,
    ): UiState.Error {
        val intent = Intent().apply {
            val e = exception as? CreateCredentialException
                ?: CreateCredentialUnknownException()
            PendingIntentHandler.setCreateCredentialException(
                intent = this,
                exception = e,
            )
        }
        setResult(RESULT_OK, intent)

        // Get the UI model of an error

        val title = when (createCredentialData) {
            is CreateCredentialData.PublicKey -> getComposeString(Res.string.error_failed_create_passkey)
            is CreateCredentialData.Password -> getComposeString(Res.string.error_failed_create_password)
            null -> null
        }
        val data = getCredentialErrorUiState(
            session = session,
            callingAppInfo = createCredentialRequest.callingAppInfo,
            title = title,
            exception = exception,
            beforeRetry = {
                uiStateSink.value = UiState.Loading
            },
            onRetry = onRetry,
        )
        return UiState.Error(data)
    }

    @Composable
    override fun Content() {
        val title = when (val data = createCredentialData) {
            is CreateCredentialData.PublicKey -> stringResource(Res.string.passkey_create_header)
            is CreateCredentialData.Password -> {
                val res = if (data.id != null) {
                    Res.string.credential_login_create_header
                } else Res.string.credential_password_create_header
                stringResource(res)
            }

            null -> ""
        }
        val context by rememberUpdatedState(newValue = LocalContext.current)
        CredentialScaffold(
            onCancel = {
                context.closestActivityOrNull?.finish()
            },
            titleText = title,
            // Render the subtitle basing on the create
            // credential type.
            subtitle = {
                when (val data = createCredentialData) {
                    is CreateCredentialData.PublicKey -> {
                        CredentialSubtitlePublicKey(
                            username = data.data.user.displayName,
                            rpId = data.data.rp.id.orEmpty(),
                        )
                    }

                    is CreateCredentialData.Password -> {
                        CredentialSubtitlePassword(
                            username = data.id,
                        )
                    }

                    null -> {
                        Text(
                            text = "Failed to parse the create credential request data",
                            style = MaterialTheme.typography.titleMedium,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
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
                        when (val state = uiStateSink.value) {
                            is UiState.Loading -> {
                                val fakeLoadingState = VaultState.Loading
                                ManualAppScreenOnLoading(fakeLoadingState)
                            }

                            is UiState.Error -> {
                                val data = state.data
                                CredentialError(
                                    title = data.title,
                                    message = data.message,
                                    advanced = data.advanced,
                                    onFinish = data.onFinish,
                                )
                            }

                            is UiState.PickCipher -> {
                                val updatedOnAuthenticated by rememberUpdatedState(state.onComplete)
                                val appMode = remember {
                                    when (val data = createCredentialData) {
                                        is CreateCredentialData.PublicKey ->
                                            AppMode.SavePasskey(
                                                rpId = data.data.rp.id,
                                                onComplete = { cipher ->
                                                    updatedOnAuthenticated.invoke(cipher)
                                                },
                                            )

                                        is CreateCredentialData.Password ->
                                            AppMode.SavePassword(
                                                id = data.id,
                                                uri = data.uri,
                                                onComplete = { cipher ->
                                                    updatedOnAuthenticated.invoke(cipher)
                                                },
                                            )

                                        null -> AppMode.Main
                                    }
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
    ): Pair<CreateCredentialResponse, AddCredentialCipherRequestData> {
        val privilegedApps = kotlin.run {
            val getPrivilegedApps = session.di.direct.instance<GetPrivilegedApps>()
            getPrivilegedApps()
                .first()
        }
        return createCredentialRequestUtils.processCreateCredentialsRequest(
            request = createCredentialRequest,
            userVerified = userVerified,
            privilegedApps = privilegedApps,
        )
    }
}
