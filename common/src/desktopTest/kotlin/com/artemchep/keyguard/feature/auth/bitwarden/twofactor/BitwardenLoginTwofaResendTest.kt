package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The email code is requested as soon as the email provider is shown. A
 * failed request must leave the resend action available, otherwise the
 * user has to restart the login to get a code.
 */
class BitwardenLoginTwofaResendTest {
    @Test
    fun `failed first email request keeps resend available`() = runTest {
        val harness = Harness(this)
        harness.requestFails = true
        val states = harness.emailStates()
        runCurrent()

        assertEquals(1, harness.requests)
        assertEquals(1, harness.errors.size, "The failure reaches the screen scope")
        val resend = assertNotNull(states.email().emailResend, "Resend must be available after a failure")

        // A failed manual resend keeps the action too.
        resend()
        runCurrent()
        assertEquals(2, harness.requests)
        assertEquals(2, harness.errors.size)
        assertNotNull(states.email().emailResend)

        // A later success starts the cool-down as usual.
        harness.requestFails = false
        states.email().emailResend!!.invoke()
        runCurrent()
        assertEquals(3, harness.requests)
        assertNull(states.email().emailResend)
    }

    @Test
    fun `successful email request holds resend for the cool-down`() = runTest {
        val harness = Harness(this)
        val states = harness.emailStates()
        runCurrent()

        assertEquals(1, harness.requests)
        assertEquals(emptyList(), harness.errors)
        assertEquals(1, harness.messages.size, "The success is reported to the user")
        assertNull(states.email().emailResend)

        advanceTimeBy(31.seconds)
        runCurrent()
        assertNotNull(states.email().emailResend)
    }

    private fun StateFlow<TwoFactorState?>.email() =
        assertNotNull(value).state as BitwardenLoginTwofaState.Email
}

// Runs the real producer with the email provider only. The screen scope
// reports uncaught request failures the way the app scope shows them.
private class Harness(
    testScope: TestScope,
) {
    val errors = mutableListOf<Throwable>()
    val messages = mutableListOf<ToastMessage>()
    var requests = 0
    var requestFails = false

    private val handler = CoroutineExceptionHandler { _, e -> errors += e }
    private val scope = CoroutineScope(
        testScope.backgroundScope.coroutineContext +
                SupervisorJob(testScope.backgroundScope.coroutineContext.job) +
                handler,
    )

    private var uuid = 0
    private val cryptoGenerator = proxy<CryptoGenerator> { method, _ ->
        check(method == "uuid") { "Unexpected CryptoGenerator.$method" }
        "uuid-${uuid++}"
    }
    private val requestEmailTfa = proxy<RequestEmailTfa> { method, _ ->
        check(method == "invoke") { "Unexpected RequestEmailTfa.$method" }
        val io: IO<Unit> = {
            requests++
            if (requestFails) throw IOException("offline")
        }
        io
    }

    suspend fun emailStates(): StateFlow<TwoFactorState?> = testScreenScope()
        .bitwardenLoginTwofaStateProducer(
            cryptoGenerator = cryptoGenerator,
            base64Service = unused(),
            deeplinkService = unused(),
            json = Json,
            addAccount = unused(),
            requestEmailTfa = requestEmailTfa,
            args = BitwardenLoginTwofaRoute.Args(
                providers = listOf(TwoFactorProviderArgument.Email(email = "u***@example.com")),
                clientSecret = null,
                email = "user@example.com",
                password = "password",
                env = ServerEnv(baseUrl = "https://vault.example.com"),
            ),
            transmitter = unused(),
        )
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Only the screen lifecycle, persistence and translations are faked.
    private fun testScreenScope(): RememberStateFlowScope {
        val disk = object : DiskHandle {
            override val restoredState = emptyMap<String, Any?>()
            override fun link(key: String, flow: Flow<Any?>) = Unit
            override fun unlink(key: String) = Unit
        }
        val translator = proxy<TranslatorScope> { _, _ -> "Test" }
        val persisted = mutableMapOf<String, MutableStateFlow<Any?>>()
        return proxy { method, args ->
            when (method) {
                "getCoroutineContext" -> scope.coroutineContext
                "getAppScope", "getScreenScope" -> scope
                "isStartedFlow" -> flowOf(false)
                "loadDiskHandle" -> disk
                "translate" -> "Test"
                "screenExecutor" -> LoadingTask(translator = translator, scope = scope)
                "message" -> {
                    messages += args[0] as ToastMessage
                    Unit
                }
                "action" -> {
                    @Suppress("UNCHECKED_CAST")
                    val action = args[0] as suspend () -> Unit
                    scope.launch { action() }
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
}

private inline fun <reified T> unused(): T = proxy { method, _ ->
    error("Unexpected ${T::class.simpleName}.$method")
}

// Interface default methods, such as the sharing helpers of the
// screen scope, run as written; everything else goes to the handler.
private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        if (method.isDefault) {
            InvocationHandler.invokeDefault(proxy, method, *args.orEmpty())
        } else {
            call(method.name, args.orEmpty())
        }
    } as T
