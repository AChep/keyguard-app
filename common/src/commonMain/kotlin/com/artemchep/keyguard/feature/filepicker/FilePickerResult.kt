package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.platform.LeUri

data class FilePickerResult(
    val uri: LeUri,
    val name: String?,
    val size: Long?,
)