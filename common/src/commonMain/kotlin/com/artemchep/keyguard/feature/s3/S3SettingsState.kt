package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.MutableState

data class S3SettingsState(
    val endpoint: MutableState<String>,
    val region: MutableState<String>,
    val bucket: MutableState<String>,
    val path: MutableState<String>,
    val accessKeyId: MutableState<String>,
    val secretAccessKey: MutableState<String>,
    val pathStyle: MutableState<Boolean>,
    val error: S3FormError?,
    val isTestingConnection: Boolean,
    val onBrowse: () -> Unit,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
    val validation: S3FormValidation = S3FormValidation(),
    val onFieldEdited: (String) -> Unit = {},
    val onFieldBlurred: (String) -> Unit = {},
)
