package com.artemchep.keyguard.platform

import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.type.TypeToken
import org.kodein.type.generic

actual inline fun <reified T : Any> DirectDI.leAllInstances(): List<T> {
    val key = DI.Key(
        contextType = TypeToken.Any,
        argType = TypeToken.Unit,
        type = generic<T>(),
        tag = null,
    )
    return try {
        container
            .allProviders(key, Any())
            .map { provider -> provider() }
    } catch (_: DI.NotFoundException) {
        emptyList()
    }
}
