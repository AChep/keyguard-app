package com.artemchep.keyguard.copy

import com.artemchep.jna.macos.setMacClipboardText
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
        val platform = CurrentPlatform
        if (
            platform is Platform.Desktop.MacOS &&
            setMacClipboardText(value, concealed)
        ) {
            return
        }

        val selection = if (concealed && platform is Platform.Desktop) {
            SensitiveStringSelection(value, platform)
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

internal class SensitiveStringSelection(
    value: String,
    platform: Platform.Desktop,
) : Transferable {
    private val textSelection = StringSelection(value)

    private val markers = sensitiveClipboardMarkers(platform)

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        textSelection.transferDataFlavors +
                markers.map(SensitiveClipboardMarker::flavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        textSelection.isDataFlavorSupported(flavor) ||
                markers.any { marker -> marker.flavor == flavor }

    override fun getTransferData(flavor: DataFlavor): Any = markers
        .firstOrNull { marker -> marker.flavor == flavor }
        ?.value
        ?.copyOf()
        ?: textSelection.getTransferData(flavor)

    private companion object {
        // KDE Klipper treats this MIME type/value pair as a password-manager hint and skips
        // storing the clipboard item in its history. This is a KDE convention, not access control.
        // https://bugs.kde.org/show_bug.cgi?id=508326
        private val kdePasswordManagerHint = SensitiveClipboardMarker(
            native = "x-kde-passwordManagerHint",
            mime = "application/x-kde-password-manager-hint;class=\"[B\"",
            value = "secret".encodeToByteArray(),
        )

        // Fallback for when the native AppKit write fails. ConcealedType is an ecosystem marker
        // asking clipboard-history tools to conceal or avoid persisting the item.
        // https://nspasteboard.org/
        private val macOsConcealed = SensitiveClipboardMarker(
            native = "org.nspasteboard.ConcealedType",
            mime = "application/x-keyguard-nspasteboard-concealed;class=\"[B\"",
            value = byteArrayOf(),
        )

        // Windows recognizes these registered formats as controls for Clipboard History and
        // Cloud Clipboard. ExcludeClipboardContentFromMonitorProcessing accepts any payload;
        // the other two require a serialized DWORD zero (four zero bytes) to opt out.
        // https://learn.microsoft.com/en-us/windows/win32/dataxchg/clipboard-formats#cloud-clipboard-and-clipboard-history-formats
        private val windowsExcludeFromMonitorProcessing = SensitiveClipboardMarker(
            native = "ExcludeClipboardContentFromMonitorProcessing",
            mime = "application/x-keyguard-windows-clipboard-monitor-exclusion;class=\"[B\"",
            value = byteArrayOf(1),
        )

        private val windowsCanIncludeInClipboardHistory = SensitiveClipboardMarker(
            native = "CanIncludeInClipboardHistory",
            mime = "application/x-keyguard-windows-clipboard-history-inclusion;class=\"[B\"",
            value = ByteArray(Int.SIZE_BYTES),
        )

        private val windowsCanUploadToCloudClipboard = SensitiveClipboardMarker(
            native = "CanUploadToCloudClipboard",
            mime = "application/x-keyguard-windows-cloud-clipboard-upload;class=\"[B\"",
            value = ByteArray(Int.SIZE_BYTES),
        )

        private fun sensitiveClipboardMarkers(
            platform: Platform.Desktop,
        ): List<SensitiveClipboardMarker> = when (platform) {
            is Platform.Desktop.Linux -> listOf(kdePasswordManagerHint)
            Platform.Desktop.MacOS -> listOf(macOsConcealed)
            Platform.Desktop.Windows -> listOf(
                windowsExcludeFromMonitorProcessing,
                windowsCanIncludeInClipboardHistory,
                windowsCanUploadToCloudClipboard,
            )

            Platform.Desktop.Other -> emptyList()
        }
    }
}

private class SensitiveClipboardMarker(
    native: String,
    mime: String,
    val value: ByteArray,
) {
    val flavor = DataFlavor(
        mime,
        native,
    ).also { flavor ->
        val flavorMap = SystemFlavorMap.getDefaultFlavorMap() as? SystemFlavorMap
        flavorMap?.setNativesForFlavor(
            flavor,
            arrayOf(native),
        )
    }
}
