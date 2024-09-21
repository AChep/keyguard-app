package com.artemchep.keyguard.common.model

import kotlin.uuid.Uuid

data class ToastMessage(
    val id: String = Uuid.random().toString(),
    val type: Type? = null,
    val title: String,
    val text: String? = null,
) {
    enum class Type {
        INFO,
        ERROR,
        SUCCESS,
    }
}
