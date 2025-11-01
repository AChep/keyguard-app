package com.artemchep.keyguard.copy

import android.app.Application
import android.view.autofill.AutofillManager
import androidx.core.content.getSystemService
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
import com.artemchep.keyguard.android.util.launchAutofillSettingsOrThrow
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import kotlinx.coroutines.FlowPreview

class AutofillServiceAndroid(
    private val application: Application,
    private val showMessage: ShowMessage,
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
        showMessage = directDI.instance(),
    )

    @OptIn(FlowPreview::class)
    override fun status(): Flow<AutofillServiceStatus> = merge(
        autofillChangedSink,
        autofillChangedFlow,
    )
        .debounce(100L)
        .map {
            val status = getAutofillServiceStatus()
            status
        }
        .flowOn(Dispatchers.Main)

    private fun getAutofillServiceStatus(): AutofillServiceStatus {
        val am = application.getSystemService<AutofillManager>()
            ?: return AutofillServiceStatus.Disabled(
                onEnable = null,
            )
        val enabled = am.hasEnabledAutofillServices()
        return if (enabled) {
            AutofillServiceStatus.Enabled(
                onDisable = {
                    am.disableAutofillServices()
                    // Refresh a status of the autofill services
                    // after we disabled ourselves.
                    autofillChangedSink.emit(Unit)
                },
            )
        } else {
            AutofillServiceStatus.Disabled(
                onEnable = { activity ->
                    try {
                        activity.launchAutofillSettingsOrThrow()
                    } catch (e: Exception) {
                        val msg = ToastMessage(
                            type = ToastMessage.Type.ERROR,
                            title = "Failed to launch the Autofill settings",
                            text = e.message,
                        )
                        showMessage.copy(msg)
                    }
                },
            )
        }
    }
}
