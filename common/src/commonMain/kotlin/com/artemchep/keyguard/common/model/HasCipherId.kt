package com.artemchep.keyguard.common.model

interface HasCipherId {
    fun cipherId(): String
}

fun <T : HasCipherId> List<T>.firstOrNull(cipherId: CipherId) = this
    .firstOrNull { it.cipherId() == cipherId.id }
