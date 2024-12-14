package com.artemchep.keyguard.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import androidx.appcompat.app.AppCompatActivity
import arrow.core.flatMap
import arrow.core.toOption
import com.artemchep.keyguard.android.autofill.AutofillStructure2
import com.artemchep.keyguard.android.clipboard.KeyguardClipboardService
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.AddCipherOpenedHistoryRequest
import com.artemchep.keyguard.common.model.AddUriCipherRequest
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.common.usecase.AddUriCipher
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.recordLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.parcelize.Parcelize
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class AutofillFakeAuthActivity : AppCompatActivity(), DIAware {
    companion object {
        private const val KEY_ARGS = "argsv2"

        fun getIntent(
            context: Context,
            dataset: Dataset,
            cipher: DSecret,
            forceAddUri: Boolean,
            structure: AutofillStructure2? = null,
        ): Intent = Intent(context, AutofillFakeAuthActivity::class.java).apply {
            val args = Args(
                dataset = dataset,
                accountId = cipher.accountId,
                cipherId = cipher.id,
                cipherName = cipher.name,
                cipherTotpRaw = cipher.login?.totp?.token?.raw,
                forceAddUri = forceAddUri,
                structure = structure,
            )
            putExtra(KEY_ARGS, args)
            setExtrasClassLoader(Args::class.java.getClassLoader())
        }
    }

    @Parcelize
    data class Args(
        val dataset: Dataset,
        val accountId: String,
        val cipherId: String,
        val cipherName: String,
        val cipherTotpRaw: String?,
        /**
         * `true` if the uri should be added to a cipher even if the
         * option to do so is disabled in the settings, `false` otherwise.
         */
        val forceAddUri: Boolean,
        val structure: AutofillStructure2? = null,
    ) : Parcelable

    override val di: DI by closestDI { this }

    private val args by lazy {
        val extras = intent.extras
        extras?.classLoader = Args::class.java.getClassLoader()
        extras?.getParcelable<Args>(KEY_ARGS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened autofill fake auth activity")

        val result = args?.dataset
        if (result != null) {
            // We want to copy to OTP code when you autofill an
            // entry, so launch a totp service.
            val mayNeedToCopyTotp = args?.structure?.items
                ?.none { it.hint == AutofillHint.APP_OTP } == true
            if (mayNeedToCopyTotp) {
                launchCopyTotpService()
            }
            launchEditService()
            launchHistoryService()

            val intent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result)
            }
            setResult(RESULT_OK, intent)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun launchCopyTotpService() {
        val windowCoroutineScope: WindowCoroutineScope by instance()
        val getVaultSession: GetVaultSession by instance()

        getVaultSession()
            .toIO()
            .effectMap { session ->
                val a = session as? MasterSession.Key
                    ?: return@effectMap io(false)

                val getAutofillCopyTotp: GetAutofillCopyTotp by a.di.instance()
                getAutofillCopyTotp()
                    .toIO()
            }
            .flatten()
            .flatMap { enabled ->
                if (!enabled) {
                    return@flatMap ioUnit()
                }

                args?.cipherTotpRaw
                    .toOption()
                    .toEither {
                        NullPointerException()
                    }
                    .flatMap { url ->
                        TotpToken.parse(url)
                    }
                    .toIO()
                    .effectMap(Dispatchers.Main) {
                        val intent = KeyguardClipboardService.getIntent(this, args?.cipherName, it)
                        startForegroundService(intent)
                    }
            }
            .handleErrorWith {
                ioUnit()
            }
            .launchIn(windowCoroutineScope)
    }

    private fun launchEditService() {
        val windowCoroutineScope: WindowCoroutineScope by instance()
        val getVaultSession: GetVaultSession by instance()

        getVaultSession()
            .toIO()
            .effectMap { session ->
                val a = session as? MasterSession.Key
                    ?: return@effectMap ioUnit()

                val getAutofillSaveUri: GetAutofillSaveUri by a.di.instance()
                // Check if the option to save uris is actually
                // enabled.
                val shouldSaveUri = args!!.forceAddUri ||
                        getAutofillSaveUri().first()
                if (!shouldSaveUri) {
                    return@effectMap ioUnit()
                }

                val addUriCipher: AddUriCipher by a.di.instance()
                val request = AddUriCipherRequest(
                    cipherId = args!!.cipherId,
                    applicationId = args?.structure?.applicationId,
                    webDomain = args?.structure?.webDomain,
                    webScheme = args?.structure?.webScheme,
                    webView = args?.structure?.webView,
                )
                addUriCipher
                    .invoke(request)
            }
            .flatten()
            .launchIn(windowCoroutineScope)
    }

    private fun launchHistoryService() {
        val windowCoroutineScope: WindowCoroutineScope by instance()
        val getVaultSession: GetVaultSession by instance()

        getVaultSession()
            .toIO()
            .effectMap { session ->
                val a = session as? MasterSession.Key
                    ?: return@effectMap ioUnit()

                val addCipherUsedAutofillHistory: AddCipherUsedAutofillHistory by a.di.instance()
                val request = AddCipherOpenedHistoryRequest(
                    accountId = args!!.accountId,
                    cipherId = args!!.cipherId,
                )
                addCipherUsedAutofillHistory
                    .invoke(request)
            }
            .flatten()
            .launchIn(windowCoroutineScope)
    }
}
