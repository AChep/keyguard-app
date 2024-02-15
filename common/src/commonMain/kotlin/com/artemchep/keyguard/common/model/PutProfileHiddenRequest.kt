package com.artemchep.keyguard.common.model

data class PutProfileHiddenRequest(
    val patch: Map<String, Boolean>,
)
