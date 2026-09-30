package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri

/**
 * A file the user picked or dropped on the Swift side, as the shared producers
 * expect it. Swift reports an unknown [size] as a negative number.
 */
internal fun filePickerResultOf(
    uri: String,
    name: String?,
    size: Long,
    accessToken: String? = null,
) = FilePickerResult(
    uri = leParseUri(uri),
    name = name,
    size = size.takeIf { it >= 0L },
    accessToken = accessToken,
)
