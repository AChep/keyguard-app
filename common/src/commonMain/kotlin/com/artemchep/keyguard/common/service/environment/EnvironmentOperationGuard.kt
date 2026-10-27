package com.artemchep.keyguard.common.service.environment

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the original MCP authorization through vault-scoped asynchronous work and mutex waits. */
internal class EnvironmentOperationGuard(
    private val origin: CoroutineContext,
    private val validate: () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EnvironmentOperationGuard>

    fun check() {
        origin.ensureActive()
        validate()
    }
}

internal suspend fun checkEnvironmentOperation() = currentCoroutineContext().checkEnvironmentOperation()

/** Checks the operation of a [CoroutineContext] captured before switching to other work. */
internal fun CoroutineContext.checkEnvironmentOperation() {
    ensureActive()
    this[EnvironmentOperationGuard]?.check()
}
