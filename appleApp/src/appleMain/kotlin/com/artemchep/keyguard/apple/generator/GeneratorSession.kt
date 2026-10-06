package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.common.model.GetPasswordResult

/** A presentation's generator, including the key and autofill editors. Main-confined. */
class GeneratorSession internal constructor(
    private val subscribe: (
        publish: (GeneratorSnapshot, GeneratorSessionActions) -> Unit,
        publishResult: (GetPasswordResult?) -> Unit,
    ) -> KeyguardCancellable,
) {
    private val session = DetailSession<GeneratorSnapshot, GeneratorSessionActions>()

    fun observe(onChange: (GeneratorSnapshot) -> Unit): KeyguardCancellable = observeWithResult(onChange) {}

    internal fun observeWithResult(
        onChange: (GeneratorSnapshot) -> Unit,
        onResult: (GetPasswordResult?) -> Unit,
    ): KeyguardCancellable = session.observe(onChange) { publish ->
        subscribe(publish, session.gated(onResult))
    }

    fun invokeGeneratorAction(id: String) = session.withActions { it.actions.invokeAction(id) }
    fun setGeneratorSwitch(key: String, value: Boolean) = session.withActions { it.switches[key]?.invoke(value) }
    fun setGeneratorText(key: String, text: String) = session.withActions { it.text[key]?.invoke(text) }
    fun setGeneratorCounter(key: String, value: Int) = session.withActions { it.counters[key]?.invoke(value.toLong()) }
    fun setGeneratorLength(value: Int) = session.withActions { it.length?.invoke(value) }
    fun close() = session.close()
}

internal data class GeneratorSessionActions(
    val actions: Map<String, () -> Unit> = emptyMap(),
    val switches: Map<String, (Boolean) -> Unit> = emptyMap(),
    val text: Map<String, (String) -> Unit> = emptyMap(),
    val counters: Map<String, (Long) -> Unit> = emptyMap(),
    val length: ((Int) -> Unit)? = null,
)
