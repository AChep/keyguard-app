package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.service.state.impl.toSchema
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.IOException

class PutScreenStateImpl(
    private val stateRepository: StateRepository,
) : PutScreenState {
    constructor(directDI: DirectDI) : this(
        stateRepository = directDI.instance(),
    )

    private class FailedToSaveScreenStateException(
        message: String,
        e: Throwable,
    ) : IOException(message, e)

    override fun invoke(key: String, state: Map<String, Any?>): IO<Unit> = stateRepository
        .put(key, state)
        // We have tried to save something that is not
        // serializable. Notify the developer so he has
        // a chance to fix it.
        .crashlyticsTap { e ->
            val schema = Json.encodeToString(state.toSchema())
            FailedToSaveScreenStateException(
                message = schema,
                e = e,
            )
        }
}
