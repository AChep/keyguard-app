package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.generator.GeneratorController
import com.artemchep.keyguard.apple.generator.GeneratorSnapshot
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent

data class AddKeyGeneratorSnapshot(
    val generator: GeneratorSnapshot,
    val canUseKey: Boolean,
    val userId: String?,
) {
    companion object {
        val empty = AddKeyGeneratorSnapshot(GeneratorSnapshot.empty, false, null)
    }
}

/** One isolated generator per editor step; stale UI commands cannot reach a subsequent step. */
internal class AddKeyGeneratorController(
    private val ctx: CoreContext,
    private val interceptNavigation: (NavigationIntent) -> Boolean,
) {
    private class Session(
        val id: String,
        val generator: GeneratorController,
        val selection: AddKeyGeneration,
        val onChange: (AddKeyGeneratorSnapshot) -> Unit,
        var snapshot: GeneratorSnapshot = GeneratorSnapshot.empty,
        var subscription: KeyguardCancellable? = null,
    )

    private var session: Session? = null

    fun observe(
        id: String,
        kind: AddItemKind,
        apply: (GetPasswordResult) -> Boolean,
        onChange: (AddKeyGeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        close()
        val controller = GeneratorController(ctx).apply {
            navigationInterceptorProvider = { interceptNavigation }
        }
        val active = Session(id, controller, AddKeyGeneration(kind, apply), onChange)
        session = active
        var source: GetPasswordResult? = null
        active.subscription = controller.observeGenerator(
            args = GeneratorRoute.Args(
                sshKey = kind == AddItemKind.SSH_KEY,
                gpgKey = kind == AddItemKind.GPG_KEY,
                storageKey = "add_key_${kind.name}",
            ),
            scopeName = "add_key_generator",
            producerKey = kind.name,
            recordHistory = false,
            onResult = { source = it },
            onChange = { snapshot ->
                if (session === active) {
                    active.snapshot = snapshot
                    active.selection.update(snapshot.loaded, source)
                    active.publish()
                }
            },
        )
        return KeyguardCancellable {
            if (session === active) close()
        }
    }

    fun close() {
        val previous = session
        session = null
        previous?.selection?.close()
        previous?.subscription?.cancel()
    }

    private fun Session.publish() = onChange(
        AddKeyGeneratorSnapshot(snapshot, selection.canUse, selection.userId),
    )

    private fun mutate(id: String, block: (GeneratorController) -> Unit) {
        val active = session?.takeIf { it.id == id } ?: return
        active.selection.invalidate()
        active.publish()
        block(active.generator)
    }

    fun invoke(id: String, action: String) {
        if (action == "value:refresh") {
            val active = session?.takeIf { it.id == id } ?: return
            active.selection.generate()
            active.publish()
            active.generator.invokeGeneratorAction(action)
        } else {
            mutate(id) { it.invokeGeneratorAction(action) }
        }
    }
    fun setText(id: String, key: String, text: String) = mutate(id) { it.setGeneratorText(key, text) }
    fun setSwitch(id: String, key: String, value: Boolean) = mutate(id) { it.setGeneratorSwitch(key, value) }
    fun setCounter(id: String, key: String, value: Int) = mutate(id) { it.setGeneratorCounter(key, value) }

    fun use(id: String): Boolean {
        val active = session?.takeIf { it.id == id } ?: return false
        return active.selection.use().also { applied ->
            if (applied) close()
        }
    }
}
