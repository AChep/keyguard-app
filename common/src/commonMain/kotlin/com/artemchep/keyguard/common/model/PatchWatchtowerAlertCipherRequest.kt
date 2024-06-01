package com.artemchep.keyguard.common.model

data class PatchWatchtowerAlertCipherRequest(
    val patch: Map<String, Map<DWatchtowerAlertType, Boolean>>,
)
