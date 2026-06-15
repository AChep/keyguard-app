package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class ClipboardServiceJvm(
    private val getClipboardAutoClear: GetClipboardAutoClear,
    private val windowCoroutineScope: WindowCoroutineScope,
) : ClipboardService {
    constructor(
        directDI: DirectDI,
    ) : this(
        getClipboardAutoClear = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val autoClearRequestCounter = AtomicLong()

    private var autoClearJob: Job? = null

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        internalSetPrimaryClip(value, concealed = concealed)
        scheduleAutoClear(value)
    }

    override fun clearPrimaryClip() {
        cancelAutoClear()
        internalSetPrimaryClip("", concealed = false)
    }

    override fun hasCopyNotification(): Boolean = false

    private fun internalSetPrimaryClip(
        value: String,
        concealed: Boolean,
    ) {
        val selection = if (concealed && CurrentPlatform is Platform.Desktop.Linux) {
            SensitiveStringSelection(value)
        } else {
            StringSelection(value)
        }
        Toolkit.getDefaultToolkit()
            .systemClipboard
            .setContents(selection, null)
    }

    private fun cancelAutoClear() {
        autoClearRequestCounter.incrementAndGet()
        autoClearJob?.cancel()
        autoClearJob = null
    }

    private fun scheduleAutoClear(
        value: String,
    ) {
        val autoClearRequest = autoClearRequestCounter.incrementAndGet()
        autoClearJob?.cancel()
        autoClearJob = windowCoroutineScope.launch {
            val duration = getClipboardAutoClear()
                .first()
            internalScheduleAutoClear(
                value = value,
                autoClearRequest = autoClearRequest,
                duration = duration,
            )
        }
    }

    private suspend fun internalScheduleAutoClear(
        value: String,
        autoClearRequest: Long,
        duration: Duration,
    ) {
        if (duration == Duration.INFINITE) {
            cancelAutoClear()
            return
        }

        if (!duration.isPositive()) {
            clearPrimaryClipIfCurrent(
                value = value,
                autoClearRequest = autoClearRequest,
            )
            return
        }

        delay(duration)
        clearPrimaryClipIfCurrent(
            value = value,
            autoClearRequest = autoClearRequest,
        )
    }

    private fun clearPrimaryClipIfCurrent(
        value: String,
        autoClearRequest: Long,
    ) {
        if (autoClearRequestCounter.get() == autoClearRequest && getPrimaryClipOrNull() == value) {
            clearPrimaryClip()
        }
    }

    private fun getPrimaryClipOrNull(): String? = runCatching {
        Toolkit.getDefaultToolkit()
            .systemClipboard
            .getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}

private class SensitiveStringSelection(
    value: String,
) : Transferable {
    private val textSelection = StringSelection(value)

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        textSelection.transferDataFlavors + kdePasswordManagerHintFlavor

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        textSelection.isDataFlavorSupported(flavor) ||
                flavor == kdePasswordManagerHintFlavor

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor == kdePasswordManagerHintFlavor -> kdePasswordManagerHintValue.copyOf()
        else -> textSelection.getTransferData(flavor)
    }

    companion object {
        private const val KDE_PASSWORD_MANAGER_HINT_NATIVE = "x-kde-passwordManagerHint"
        private const val KDE_PASSWORD_MANAGER_HINT_MIME = "application/x-kde-password-manager-hint;class=\"[B\""

        private val kdePasswordManagerHintValue = "secret".encodeToByteArray()
        private val kdePasswordManagerHintFlavor = DataFlavor(
            KDE_PASSWORD_MANAGER_HINT_MIME,
            KDE_PASSWORD_MANAGER_HINT_NATIVE,
        ).also { flavor ->
            val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap
            flavorMap?.setNativesForFlavor(
                flavor,
                arrayOf(KDE_PASSWORD_MANAGER_HINT_NATIVE),
            )
        }
    }
}
