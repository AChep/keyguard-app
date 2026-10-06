package com.artemchep.keyguard.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.util.getParcelableCompat
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.di.KeyguardKoinOwner
import com.artemchep.keyguard.di.keyguardKoin
import com.artemchep.keyguard.feature.auth.userverification.UserVerificationRoute
import com.artemchep.keyguard.feature.keyguard.ManualAppScreen
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnCreate
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnLoading
import com.artemchep.keyguard.feature.keyguard.ManualAppScreenOnUnlock
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.autofill
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the user to verify before it releases a dataset of
 * a cipher that has the re-prompt enabled.
 */
class AutofillVerifyActivity : BaseActivity(), KeyguardKoinOwner {
    companion object {
        private const val KEY_ARGS = "args"

        fun getIntent(
            context: Context,
            dataset: Dataset,
            cipherName: String,
        ): Intent = Intent(context, AutofillVerifyActivity::class.java).apply {
            val args = Args(
                dataset = dataset,
                cipherName = cipherName,
            )
            putExtra(KEY_ARGS, args)
            setExtrasClassLoader(Args::class.java.getClassLoader())
        }

        /**
         * Builds a dataset from the [builder]. If the user verification
         * is required, then returns a dataset from the [createEmptyBuilder],
         * that must not have any values set, that fills the fields after
         * the user verifies.
         */
        fun buildDatasetOrNull(
            context: Context,
            builder: Dataset.Builder,
            createEmptyBuilder: () -> Dataset.Builder,
            cipherName: String,
            requiresUserVerification: Boolean,
        ): Dataset? {
            val dataset = try {
                builder.build()
            } catch (_: Exception) {
                null // not a single value set
            }
            return when {
                dataset == null -> null
                !requiresUserVerification -> dataset
                else -> try {
                    val intent = getIntent(
                        context = context,
                        dataset = dataset,
                        cipherName = cipherName,
                    )
                    val code = PendingIntents.autofill.obtainId()
                    val pi = PendingIntent.getActivity(
                        context,
                        code,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
                    )
                    createEmptyBuilder()
                        .setAuthentication(pi.intentSender)
                        .build()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    @Parcelize
    data class Args(
        val dataset: Dataset,
        val cipherName: String,
    ) : Parcelable

    override val koin get() = this.keyguardKoin()

    override val isConsentSurface: Boolean
        get() = true

    private val args by lazy {
        val extras = intent.extras
        extras?.classLoader = Args::class.java.getClassLoader()
        extras?.getParcelableCompat<Args>(KEY_ARGS)
    }

    private val getVaultSession by lazy { koin.get<GetVaultSession>() }

    private val uiStateSink = mutableStateOf<UiState>(UiState.Loading)

    private sealed interface UiState {
        data object Loading : UiState

        class RequiresAuthentication(
            val onAuthenticated: () -> Unit,
        ) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened autofill verify activity")
        val dataset = args?.dataset
        if (dataset == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val startedAt = Clock.System.now()
        lifecycleScope.launch {
            val session = getVaultSession()
                .mapNotNull { it as? MasterSession.Key }
                .first()

            // Unlocking the vault from this screen
            // already verifies the user.
            val userVerifiedState = MutableStateFlow(
                session.createdAt > startedAt &&
                        session.origin is MasterSession.Key.Authenticated,
            )
            if (!userVerifiedState.value) {
                uiStateSink.value = UiState.RequiresAuthentication {
                    userVerifiedState.value = true
                }
                userVerifiedState.first { it }
            }

            val intent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    @Composable
    override fun Content() {
        val context by rememberUpdatedState(newValue = LocalContext.current)
        CredentialScaffold(
            onCancel = {
                context.closestActivityOrNull?.finish()
            },
            titleText = stringResource(Res.string.autofill),
            subtitle = {
                CredentialSubtitlePassword(
                    username = args?.cipherName.orEmpty(),
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
                                val fakeLoadingState = VaultState.Loading
                                ManualAppScreenOnLoading(fakeLoadingState)
                            }

                            is UiState.RequiresAuthentication -> {
                                val updatedOnAuthenticated by rememberUpdatedState(state.onAuthenticated)
                                val route = remember {
                                    UserVerificationRoute(
                                        onAuthenticated = {
                                            updatedOnAuthenticated.invoke()
                                        },
                                    )
                                }
                                NavigationNode(
                                    id = "user_verification",
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
