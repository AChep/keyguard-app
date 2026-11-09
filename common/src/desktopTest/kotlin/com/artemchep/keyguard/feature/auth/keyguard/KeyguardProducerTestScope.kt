package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.keyguard.pairing.KeyguardPairing
import com.artemchep.keyguard.feature.auth.keyguard.pairing.KeyguardPairingLauncher
import com.artemchep.keyguard.provider.keyguard.KeyguardPasswordPrompt
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

internal const val TEST_TRANSLATED = "Translated"

/**
 * TEST ONLY: the screen lifecycle, persistence, navigation and translations
 * of a producer, faked on the background scope of a test.
 */
internal class KeyguardProducerTestScope(
    testScope: TestScope,
) {
    val errors = mutableListOf<Throwable>()
    val persisted = mutableMapOf<String, MutableStateFlow<Any?>>()
    val navigations = mutableListOf<NavigationIntent>()
    val copied = mutableListOf<String>()

    /** The resources the producer translated, in order. */
    val translated = mutableListOf<Any?>()

    /** The format arguments of each resource in [translated], in order. */
    val translatedArgs = mutableListOf<List<Any?>>()

    private val handler = CoroutineExceptionHandler { _, e -> errors += e }

    /** The scope of the screen; cancel it to close the screen. */
    val scope = CoroutineScope(
        testScope.backgroundScope.coroutineContext +
            SupervisorJob(testScope.backgroundScope.coroutineContext.job) +
            handler,
    )

    private val translator = keyguardTestProxy<TranslatorScope> { _, _ -> TEST_TRANSLATED }

    private val clipboardService = keyguardTestProxy<ClipboardService> { method, args ->
        when (method) {
            "setPrimaryClip" -> copied.add(args[0] as String)
            "hasCopyNotification" -> true
            else -> Unit
        }
    }

    fun create(): RememberStateFlowScope = keyguardTestProxy { method, args ->
        when (method) {
            "getCoroutineContext" -> scope.coroutineContext
            "getAppScope", "getScreenScope" -> scope
            "getScreenId" -> "screen"
            "translate" -> {
                translated += args.firstOrNull()
                translatedArgs += (args.getOrNull(1) as? Array<*>)?.toList().orEmpty()
                TEST_TRANSLATED
            }
            "screenExecutor" -> LoadingTask(translator = translator, scope = scope)
            "copier" -> CopyText(
                clipboardService = clipboardService,
                translator = translator,
                onMessage = {},
            )
            "navigate" -> navigations.add(args[0] as NavigationIntent)
            "interceptBackPress" -> {
                val unregister: () -> Unit = {}
                unregister
            }
            "action" -> {
                @Suppress("UNCHECKED_CAST")
                val block = args[0] as suspend () -> Unit
                scope.launch { block() }
                Unit
            }
            "mutablePersistedFlow" -> persisted.getOrPut(args[0] as String) {
                @Suppress("UNCHECKED_CAST")
                val initial = args.last() as () -> Any?
                MutableStateFlow(initial())
            }
            else -> error("Unexpected screen call: $method")
        }
    }
}

/** TEST ONLY: a pairing whose steps the test sets, and which records the commands. */
internal class FakePairing(
    val label: String? = null,
    val network: P2pNetwork? = null,
) : KeyguardPairing {
    val steps = MutableStateFlow<EnrollmentStep>(EnrollmentStep.Method)

    val calls = mutableListOf<String>()

    override val step: StateFlow<EnrollmentStep> get() = steps

    override fun showCode() {
        calls += "showCode"
    }

    override fun scanCode() {
        calls += "scanCode"
    }

    override fun submitCode(text: String) {
        calls += "submitCode:$text"
    }

    override fun approve() {
        calls += "approve"
    }

    override fun enterDigits(digits: String) {
        calls += "enterDigits:$digits"
    }

    override fun reject() {
        calls += "reject"
    }

    override fun cancel() {
        calls += "cancel"
    }
}

/** TEST ONLY: starts [FakePairing]s and keeps them. */
internal class FakePairingLauncher : KeyguardPairingLauncher {
    val pairings = mutableListOf<FakePairing>()

    val last: FakePairing get() = pairings.last()

    /** The password prompt each pairing got, in order. */
    val passwords = mutableListOf<KeyguardPasswordPrompt>()

    override fun pair(
        deviceLabel: String,
        network: P2pNetwork,
        password: KeyguardPasswordPrompt,
        scope: CoroutineScope,
    ): KeyguardPairing = FakePairing(label = deviceLabel, network = network)
        .also(pairings::add)
        .also { passwords += password }
}

// Interface default methods run as written;
// everything else goes to the handler.
internal inline fun <reified T> keyguardTestProxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        if (method.isDefault) {
            InvocationHandler.invokeDefault(proxy, method, *args.orEmpty())
        } else {
            call(method.name, args.orEmpty())
        }
    } as T
