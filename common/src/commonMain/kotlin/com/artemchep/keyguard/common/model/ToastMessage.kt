package com.artemchep.keyguard.common.model

import java.util.UUID

data class ToastMessage(
    val id: String = UUID.randomUUID().toString(),
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
