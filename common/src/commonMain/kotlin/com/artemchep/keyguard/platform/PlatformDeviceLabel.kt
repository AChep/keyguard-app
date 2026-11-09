package com.artemchep.keyguard.platform

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.feature.p2psync.model.DeviceRecordRules

/** A local system name or model suitable for a device record, or null when unavailable. */
internal expect suspend fun platformDeviceLabel(context: LeContext): String?

/** Lookups are lazy: an unavailable or invalid name advances to the next source. */
internal suspend fun firstDeviceLabel(vararg candidates: suspend () -> String?): String? {
    for (candidate in candidates) {
        val label = runCatchingNonFatal { candidate()?.normalizedDeviceLabel() }.getOrNull()
        if (label != null) return label
    }
    return null
}

/** Keeps the protocol's code-point limit without cutting a UTF-16 surrogate pair. */
internal fun String.normalizedDeviceLabel(): String? {
    val value = trim()
    if (value.isEmpty() || value.lowercase() in unavailableDeviceLabels) return null
    var end = 0
    var codePoints = 0
    while (end < value.length && codePoints < DeviceRecordRules.MAX_LABEL_LENGTH) {
        val paired = value[end].isHighSurrogate() && value.getOrNull(end + 1)?.isLowSurrogate() == true
        end += if (paired) 2 else 1
        codePoints++
    }
    val label = value.substring(0, end).trimEnd()
    return label.takeIf(DeviceRecordRules::isValidLabel)
}

/** Android models sometimes already include their manufacturer. */
internal fun manufacturerModelDeviceLabel(manufacturer: String, model: String): String? {
    val modelName = model.normalizedDeviceLabel() ?: return null
    val maker = manufacturer.normalizedDeviceLabel()
    return if (maker == null || modelName.startsWith(maker, ignoreCase = true)) {
        modelName
    } else {
        "$maker $modelName"
    }
}

private val unavailableDeviceLabels = setOf("unknown", "localhost", "localhost.localdomain", "(none)")
