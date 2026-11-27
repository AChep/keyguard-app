package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.apple.model.TextFieldSnapshot
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.feature.masterkdf.MasterKdfFormState

/**
 * The key derivation (Argon2id) form, shared by the vault setup and the key derivation
 * settings. Reset rewrites the fields, so they carry a text revision for SwiftUI to adopt.
 */
data class MasterKdfSnapshot(
    val iterations: TextFieldSnapshot,
    /** In MiB. */
    val memory: TextFieldSnapshot,
    val parallelism: TextFieldSnapshot,
    /** False while the entered values are the defaults. */
    val canReset: Boolean,
    val canTest: Boolean,
    val isTesting: Boolean,
    /** How long unlocking takes with the entered values, if tested. */
    val testResultText: String?,
    /** A warning about the entered values, if any. */
    val warning: String?,
)

/**
 * Edits the key derivation form of a session: the one handle of both the vault setup and
 * the key derivation settings. Main-confined; edits are ignored once the session closes.
 */
class MasterKdfActions internal constructor(
    private val withHandlers: ((MasterKdfHandlers) -> Unit) -> Unit,
) {
    fun setIterations(text: String) = withHandlers { it.setIterations?.invoke(text) }

    /** In MiB. */
    fun setMemory(text: String) = withHandlers { it.setMemory?.invoke(text) }

    fun setParallelism(text: String) = withHandlers { it.setParallelism?.invoke(text) }

    fun reset() = withHandlers { it.reset?.invoke() }

    fun test() = withHandlers { it.test?.invoke() }
}

internal data class MasterKdfHandlers(
    val setIterations: ((String) -> Unit)? = null,
    val setMemory: ((String) -> Unit)? = null,
    val setParallelism: ((String) -> Unit)? = null,
    val reset: (() -> Unit)? = null,
    val test: (() -> Unit)? = null,
)

internal fun MasterKdfFormState.toMasterKdfSnapshot(): MasterKdfSnapshot = MasterKdfSnapshot(
    iterations = iterations.toFieldSnapshot(),
    memory = memory.toFieldSnapshot(),
    parallelism = parallelism.toFieldSnapshot(),
    canReset = onReset != null,
    canTest = test.onTest != null,
    isTesting = test.isRunning,
    testResultText = test.resultText,
    warning = warning,
)

internal fun MasterKdfFormState.toMasterKdfHandlers(): MasterKdfHandlers = MasterKdfHandlers(
    setIterations = iterations.onChange,
    setMemory = memory.onChange,
    setParallelism = parallelism.onChange,
    reset = onReset,
    test = test.onTest,
)
