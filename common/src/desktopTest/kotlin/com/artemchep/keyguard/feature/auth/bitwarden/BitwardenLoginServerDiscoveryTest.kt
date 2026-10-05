package com.artemchep.keyguard.feature.auth.bitwarden

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.feature.loading.LoadingTask
import com.artemchep.keyguard.feature.navigation.state.DiskHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.model.ServerDiscoveryCandidate
import com.artemchep.keyguard.provider.bitwarden.usecase.DiscoverBitwardenServer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val DOMAIN = "example.com"
private const val EMAIL = "user@$DOMAIN"
private const val SERVER_URL = "https://vault.example.com"
private const val BASE_URL_ITEM_ID = "login.server.base.url"
private const val API_URL_ITEM_ID = "login.server.custom.api_url"
private const val IDENTITY_URL_ITEM_ID = "login.server.custom.identity_url"

/**
 * The login form offers to look up the server published for the email
 * domain. Nothing is sent until the user asks; the server found is filled
 * in and reported with a message.
 */
class BitwardenLoginServerDiscoveryTest {
    @Test
    fun `offers the lookup once the email has a domain`() = runTest {
        val harness = Harness(this, mapOf(DOMAIN to listOf(custom(SERVER_URL))))
        val states = harness.states()
        runCurrent()
        assertNull(states.login().serverDiscovery, "No lookup without an email")

        states.login().typeEmail(EMAIL)
        advanceTimeBy(1.seconds)
        runCurrent()

        val discovery = states.login().discovery()
        assertFalse(discovery.isLoading)
        assertNotNull(discovery.onClick)
        assertTrue(harness.queries.isEmpty(), "Nothing is looked up before a tap")
    }

    @Test
    fun `does not offer the lookup for an invalid email or a public provider`() = runTest {
        val harness = Harness(this, emptyMap())
        val states = harness.states()
        runCurrent()

        states.login().typeEmail("user@")
        runCurrent()
        assertNull(states.login().serverDiscovery)

        states.login().typeEmail("user@gmail.com")
        runCurrent()
        assertNull(states.login().serverDiscovery)
    }

    @Test
    fun `never offers the lookup when the environment is not editable`() = runTest {
        val harness = Harness(
            this,
            mapOf(DOMAIN to listOf(custom(SERVER_URL))),
            args = BitwardenLoginRoute.Args(
                env = ServerEnv(baseUrl = "https://old.example.com"),
                envEditable = false,
            ),
        )
        val states = harness.states()
        runCurrent()

        states.login().typeEmail(EMAIL)
        runCurrent()
        assertNull(states.login().serverDiscovery)
    }

    @Test
    fun `keeps the form busy while the lookup runs`() = runTest {
        val harness = Harness(
            this,
            mapOf(DOMAIN to listOf(custom(SERVER_URL))),
            lookupDelayMs = 1_000L,
        )
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        runCurrent()
        val busy = states.login()
        assertTrue(busy.discovery().isLoading)
        assertNull(busy.discovery().onClick, "A running lookup can not be started again")
        assertNull(busy.email.onChange, "The email can not change under the lookup")

        advanceTimeBy(2.seconds)
        runCurrent()
        val done = states.login()
        assertFalse(done.discovery().isLoading)
        assertNotNull(done.discovery().onClick, "The lookup can be repeated")
    }

    @Test
    fun `fills in a custom server and reports it`() = runTest {
        val harness = Harness(this, mapOf(DOMAIN to listOf(custom(SERVER_URL))))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(listOf(DOMAIN), harness.queries)
        val state = states.login()
        assertTrue(state.showCustomEnv)
        assertEquals(ServerEnv.Region.selfhosted, state.checkedRegionKey())
        assertEquals(SERVER_URL, state.urlText(BASE_URL_ITEM_ID))
        val msg = harness.messages.single()
        assertEquals(ToastMessage.Type.SUCCESS, msg.type)
        assertNull(msg.text, "A server of the email domain needs no warning")
    }

    @Test
    fun `clears the custom endpoints of another server`() = runTest {
        val harness = Harness(this, mapOf(DOMAIN to listOf(custom(SERVER_URL))))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        states.login().typeUrl(BASE_URL_ITEM_ID, "https://old.example.com")
        states.login().typeUrl(API_URL_ITEM_ID, "https://api.old.example.com")
        states.login().typeUrl(IDENTITY_URL_ITEM_ID, "https://id.old.example.com")
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        val state = states.login()
        assertEquals(SERVER_URL, state.urlText(BASE_URL_ITEM_ID))
        assertEquals("", state.urlText(API_URL_ITEM_ID))
        assertEquals("", state.urlText(IDENTITY_URL_ITEM_ID))
    }

    @Test
    fun `keeps the custom endpoints of the same server`() = runTest {
        val harness = Harness(this, mapOf(DOMAIN to listOf(custom(SERVER_URL))))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        states.login().typeUrl(BASE_URL_ITEM_ID, "vault.example.com/")
        states.login().typeUrl(IDENTITY_URL_ITEM_ID, "https://id.example.com")
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        val state = states.login()
        assertEquals("vault.example.com/", state.urlText(BASE_URL_ITEM_ID))
        assertEquals("https://id.example.com", state.urlText(IDENTITY_URL_ITEM_ID))
    }

    @Test
    fun `selects the region of an official host`() = runTest {
        val official = ServerDiscoveryCandidate(
            env = ServerEnv(region = ServerEnv.Region.EU),
            sameDomain = true,
        )
        val harness = Harness(this, mapOf(DOMAIN to listOf(official)))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        val state = states.login()
        assertFalse(state.showCustomEnv)
        assertEquals(ServerEnv.Region.EU.name, state.checkedRegionKey())
        assertEquals(ToastMessage.Type.SUCCESS, harness.messages.single().type)
    }

    @Test
    fun `warns when the server is outside the email domain`() = runTest {
        val foreign = custom("https://vault.hosting.net", sameDomain = false)
        val harness = Harness(this, mapOf(DOMAIN to listOf(foreign)))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals("https://vault.hosting.net", states.login().urlText(BASE_URL_ITEM_ID))
        val msg = harness.messages.single()
        assertEquals(ToastMessage.Type.SUCCESS, msg.type)
        assertNotNull(msg.text, "The message warns about the other domain")
    }

    @Test
    fun `reports when no server is found`() = runTest {
        val harness = Harness(this, mapOf(DOMAIN to emptyList()))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(ToastMessage.Type.INFO, harness.messages.single().type)
        assertEquals(ServerEnv.Region.US.name, states.login().checkedRegionKey())
    }

    @Test
    fun `reports a failed lookup`() = runTest {
        val harness = Harness(this, emptyMap(), failures = setOf(DOMAIN))
        val states = harness.states()
        runCurrent()
        states.login().typeEmail(EMAIL)
        runCurrent()

        states.login().discover()
        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(ToastMessage.Type.ERROR, harness.messages.single().type)
        assertEquals(ServerEnv.Region.US.name, states.login().checkedRegionKey())
    }
}

private fun custom(
    url: String,
    sameDomain: Boolean = true,
) = ServerDiscoveryCandidate(
    env = ServerEnv(baseUrl = url),
    sameDomain = sameDomain,
)

private fun StateFlow<Loadable<LoginState>?>.login(): LoginState =
    assertNotNull(value?.getOrNull(), "The login state is available")

private fun LoginState.typeEmail(text: String) {
    assertNotNull(email.onChange, "The email is editable").invoke(text)
}

private fun LoginState.discovery(): LoginServerDiscovery =
    assertNotNull(serverDiscovery, "The lookup is offered")

private fun LoginState.discover() {
    assertNotNull(discovery().onClick, "The lookup can be started").invoke()
}

private fun LoginState.checkedRegionKey(): String? = regionItems
    .singleOrNull { it.checked }
    ?.key

private fun LoginState.urlText(id: String): String =
    urlItem(id).state.flow.value.text.state.text

private fun LoginState.typeUrl(id: String, text: String) {
    assertNotNull(urlItem(id).state.flow.value.text.onChange, "The URL is editable").invoke(text)
}

private fun LoginState.urlItem(id: String): LoginStateItem.Url = items
    .filterIsInstance<LoginStateItem.Url>()
    .single { it.id == id }

private class Harness(
    testScope: TestScope,
    private val candidates: Map<String, List<ServerDiscoveryCandidate>>,
    private val args: BitwardenLoginRoute.Args = BitwardenLoginRoute.Args(),
    private val lookupDelayMs: Long = 0L,
    private val failures: Set<String> = emptySet(),
) {
    val queries = mutableListOf<String>()
    val messages = mutableListOf<ToastMessage>()
    val errors = mutableListOf<Throwable>()

    private val handler = CoroutineExceptionHandler { _, e -> errors += e }
    private val scope = CoroutineScope(
        testScope.backgroundScope.coroutineContext +
                SupervisorJob(testScope.backgroundScope.coroutineContext.job) +
                handler,
    )

    private val discoverBitwardenServer = object : DiscoverBitwardenServer {
        override fun invoke(domain: String): IO<List<ServerDiscoveryCandidate>> = {
            queries += domain
            delay(lookupDelayMs)
            if (domain in failures) {
                throw IllegalStateException("Lookup failed")
            }
            candidates[domain].orEmpty()
        }
    }
    private val cipherUnsecureUrlCheck = proxy<CipherUnsecureUrlCheck> { _, _ -> false }

    suspend fun states(): StateFlow<Loadable<LoginState>?> = testScreenScope()
        .bitwardenLoginStateProducer(
            addAccount = unused(),
            cipherUnsecureUrlCheck = cipherUnsecureUrlCheck,
            confirmationRouteFactory = unused(),
            discoverBitwardenServer = discoverBitwardenServer,
            args = args,
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
