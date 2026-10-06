package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.apple.generator.GeneratorController
import com.artemchep.keyguard.apple.generator.GeneratorSnapshot
import com.artemchep.keyguard.apple.generator.GeneratorSession
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent

/** One cipher or Send editor and its children. Methods and callbacks are main-confined. */
// Swift-facing actions stay on the same owner so no child can outlive its editor.
@Suppress("TooManyFunctions")
class AddFormSession internal constructor(
    ctx: CoreContext,
    private val controller: AddItemController,
    private val dialogController: DialogController,
    private val subscribe: (
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
        complete: () -> Unit,
    ) -> KeyguardCancellable,
    private val onDispose: () -> Unit,
) {
    val dialogs = com.artemchep.keyguard.apple.dialog.FormDialogsSession(dialogController)
    private val session = DetailSession<AddItemFormSnapshot, AddFormActions>(onDispose = ::dispose)
    private val autofillGeneratorController = GeneratorController(ctx).apply {
        navigationInterceptorProvider = { sessionKoin ->
            val dialogInterceptor = controller.navigationInterceptorProvider(sessionKoin)
            val interceptor: (NavigationIntent) -> Boolean = { intent ->
                controller.interceptDateTimePicker(intent) || dialogInterceptor?.invoke(intent) == true
            }
            interceptor
        }
    }
    private var autofillGenerator: GeneratorSession? = null
    private var autofillSubscription: KeyguardCancellable? = null

    fun observe(onChange: (AddItemFormSnapshot) -> Unit, onClose: () -> Unit): KeyguardCancellable =
        session.observe(onChange, onClose, subscribe)

    fun close() = session.close()

    private fun dispose() {
        controller.close()
        dialogs.close()
        autofillSubscription?.cancel()
        autofillSubscription = null
        autofillGenerator = null
        onDispose()
    }

    fun setAddFieldText(id: String, text: String) = session.withActions { it.setText[id]?.invoke(text) }

    fun setAddField(id: String, text: String) = session.withActions { it.fields[id]?.invoke(text) }

    fun setAddSwitch(id: String, value: Boolean) = session.withActions { it.switches[id]?.invoke(value) }

    fun invokeAddAction(id: String) = session.withActions { it.actions.invokeAction(id) }

    fun scanAddTotp(id: String, value: String) = session.withActions { it.totpScan[id]?.invoke(value) }

    fun submitAddItem() = session.withActions { it.save?.invoke() }

    fun invokeAddOwnership() = session.withActions { it.ownership?.invoke() }

    fun dropFileOnAddForm(uri: String, name: String?, size: Long) =
        session.withActions { it.formFileDrop?.invoke(filePickerResultOf(uri, name, size)) }

    fun dropFileOnAddItem(itemId: String, uri: String, name: String?, size: Long) =
        session.withActions { it.itemFileDrop[itemId]?.invoke(filePickerResultOf(uri, name, size)) }

    fun setFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) = session.runIfOpen {
        controller.setAddFilePickerRequestHandler(handler)
        dialogController.setConfirmationFilePickerRequestHandler(handler)
    }

    fun resolveFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String?) =
        session.withActions {
            controller.resolveAddFilePicker(requestId, uri, name, size, accessToken)
            dialogController.resolveConfirmationFilePicker(requestId, uri, name, size)
        }

    fun cancelFilePicker(requestId: String) =
        session.withActions {
            controller.cancelAddFilePicker(requestId)
            dialogController.cancelConfirmationFilePicker(requestId)
        }

    fun setDatePickerRequestHandler(handler: ((AddDatePickerRequest) -> Unit)?) =
        session.runIfOpen { controller.setAddDatePickerRequestHandler(handler) }

    fun resolveDatePicker(requestId: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        session.withActions { controller.resolveAddDatePicker(requestId, year, month, day, hour, minute) }

    fun cancelDatePicker(requestId: String) =
        session.withActions { controller.cancelAddDatePicker(requestId) }

    fun observeAddKeyGenerator(
        itemId: String,
        sessionId: String,
        onChange: (AddKeyGeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        var observation = KeyguardCancellable {}
        session.withActions { actions ->
            val target = actions.keyTargets[itemId]
            if (target == null) {
                onChange(AddKeyGeneratorSnapshot.empty)
                return@withActions
            }
            observation = controller.observeKeyGenerator(
                sessionId = sessionId,
                kind = target.kind,
                // Lands in the form's current target, never in a frame the form has moved past.
                apply = { result ->
                    var applied = false
                    session.withActions { applied = it.keyTargets[itemId]?.apply?.invoke(result) == true }
                    applied
                },
                onChange = onChange,
            )
        }
        return observation
    }

    fun useAddGeneratedKey(sessionId: String): Boolean {
        var applied = false
        session.withActions { applied = controller.useGeneratedKey(sessionId) }
        return applied
    }

    fun invokeAddKeyGeneratorAction(sessionId: String, id: String) =
        session.withActions { controller.invokeKeyGeneratorAction(sessionId, id) }

    fun setAddKeyGeneratorText(sessionId: String, key: String, text: String) =
        session.withActions { controller.setKeyGeneratorText(sessionId, key, text) }

    fun setAddKeyGeneratorSwitch(sessionId: String, key: String, value: Boolean) =
        session.withActions { controller.setKeyGeneratorSwitch(sessionId, key, value) }

    fun setAddKeyGeneratorCounter(sessionId: String, key: String, value: Int) =
        session.withActions { controller.setKeyGeneratorCounter(sessionId, key, value) }

    /** Emits [GeneratorSnapshot.empty] while the vault is locked; [onChange] runs on the main thread. */
    fun observeAutofillGenerator(
        username: Boolean,
        password: Boolean,
        uris: List<String>,
        onChange: (GeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        var observation: KeyguardCancellable = KeyguardCancellable {}
        session.runIfOpen { observation = startAutofillGenerator(username, password, uris, onChange) }
        return observation
    }

    private fun startAutofillGenerator(
        username: Boolean,
        password: Boolean,
        uris: List<String>,
        onChange: (GeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        autofillSubscription?.cancel()
        val k = when {
            password -> "password"
            username -> "username"
            else -> "generator"
        }
        val generator = autofillGeneratorController.makeSession(
            args = GeneratorRoute.Args(
                context = GeneratorRoute.Args.Context(uris = uris.toTypedArray()),
                username = username,
                password = password,
            ),
            scopeName = "autofill_$k",
            producerKey = k,
        )
        autofillGenerator = generator
        val observation = generator.observe(session.gated(onChange))
        autofillSubscription = observation
        return KeyguardCancellable {
            observation.cancel()
            if (autofillSubscription === observation) {
                autofillSubscription = null
                autofillGenerator = null
            }
        }
    }

    fun invokeAutofillGeneratorAction(id: String) =
        session.withActions { autofillGenerator?.invokeGeneratorAction(id) }

    fun setAutofillGeneratorSwitch(key: String, value: Boolean) =
        session.withActions { autofillGenerator?.setGeneratorSwitch(key, value) }

    fun setAutofillGeneratorText(key: String, text: String) =
        session.withActions { autofillGenerator?.setGeneratorText(key, text) }

    fun setAutofillGeneratorCounter(key: String, value: Int) =
        session.withActions { autofillGenerator?.setGeneratorCounter(key, value) }

    fun setAutofillGeneratorLength(value: Int) =
        session.withActions { autofillGenerator?.setGeneratorLength(value) }
}
