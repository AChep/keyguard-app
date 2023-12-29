package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.core.content.getSystemService
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.artemchep.keyguard.common.service.autofill.AutofillService
import com.artemchep.keyguard.common.service.autofill.AutofillServiceStatus
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AutofillServiceAndroid(
    private val application: Application,
) : AutofillService {
    private val autofillChangedSink = EventFlow<Unit>()

    private val autofillChangedFlow = channelFlow<Unit> {
        send(Unit)

        val lifecycle = ProcessLifecycleOwner.get()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            send(Unit)
        }
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance(),
    )

    override fun status(): Flow<AutofillServiceStatus> = merge(
        autofillChangedSink,
        autofillChangedFlow,
    )
        .debounce(100L)
        .map {
            val am = application.getSystemService<AutofillManager>()
                ?: return@map AutofillServiceStatus.Disabled(
                    onEnable = null,
                )
            val cm = CredentialManager.create(application)
            //.createSettingsPendingIntent()

            val enabled = am.hasEnabledAutofillServices()
            if (enabled) {
                AutofillServiceStatus.Enabled(
                    onDisable = {
                        am.disableAutofillServices()
                        // Refresh a status of the autofill services
                        // after we disabled ourselves.
                        autofillChangedSink.emit(Unit)
                    },
                )
            } else {
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                AutofillServiceStatus.Disabled(
                    onEnable = { activity ->
                        val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                            val packageName = application.packageName
                            data = Uri.parse("package:$packageName")
                        }
                        activity.startActivity(intent)
                    },
                )
            }
        }
        .flowOn(Dispatchers.Main)
}
