package com.artemchep.keyguard.common.model

data class CipherFieldSwitchToggleRequest(
    val fieldIndex: Int,
    val fieldName: String?,
    val value: Boolean,
)
