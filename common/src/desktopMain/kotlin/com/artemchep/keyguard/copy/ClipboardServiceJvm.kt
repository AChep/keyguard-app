package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import org.kodein.di.DirectDI
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class ClipboardServiceJvm(
) : ClipboardService {
    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun setPrimaryClip(value: String, concealed: Boolean) {
        val selection = StringSelection(value)
        Toolkit.getDefaultToolkit()
            .systemClipboard
            .setContents(selection, null)
    }

    override fun clearPrimaryClip() {
        setPrimaryClip("", concealed = false)
    }

    override fun hasCopyNotification(): Boolean = false
}
