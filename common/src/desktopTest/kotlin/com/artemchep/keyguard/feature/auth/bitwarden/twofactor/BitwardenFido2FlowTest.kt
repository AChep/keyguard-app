package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.deeplink.impl.DeeplinkServiceImpl
import com.artemchep.keyguard.feature.fido2.Fido2Prompt
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerTwoFactorToken
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderArgument
import com.artemchep.keyguard.provider.bitwarden.model.TwoFactorProviderType
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.util.fido2.Fido2AssertionResult
import com.artemchep.keyguard.util.fido2.Fido2Operation
import com.artemchep.keyguard.util.fido2.encode
import io.ktor.http.HttpStatusCode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json

class BitwardenFido2FlowTest {
    @Test
    fun cancellationRefreshesChallengeAndIgnoresLateCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val harness = Harness(this)
            harness.start()
            runCurrent()
            assertFalse(harness.state().isLoading)
            harness.state().primaryAction!!.onClick!!.invoke()
            runCurrent()
            assertTrue(harness.state().isLoading)
            assertEquals(BitwardenLoginTwofaState.PrimaryAction.Icon.LOADING, harness.state().primaryAction?.icon)
            assertNull(harness.state().primaryAction?.onClick)
            val first = harness.prompts.single()
            first.cancel()
            runCurrent()
            assertFalse(harness.state().isLoading)
            assertEquals(BitwardenLoginTwofaState.PrimaryAction.Icon.LOGIN, harness.state().primaryAction?.icon)
            assertNotNull(harness.state().primaryAction?.onClick)
            harness.state().primaryAction!!.onClick!!.invoke()
            runCurrent()
            assertEquals(1, harness.refreshes)
            assertEquals(2, harness.prompts.size)
            assertContentEquals(byteArrayOf(2), (harness.prompts.last().operation as Fido2Operation.Assert).challenge)

            val stale = harness.result(first)
            first.complete(Result.success(stale))
            runCurrent()
            assertContentEquals(ByteArray(stale.size), stale)
            assertEquals(emptyList(), harness.tokens)

            harness.state().rememberMe.onChange!!.invoke(true)
            val current = harness.prompts.last()
            current.complete(Result.success(harness.result(current)))
            runCurrent()
            assertEquals(1, harness.completions)
            assertEquals(TwoFactorProviderType.Fido2WebAuthn, harness.tokens.single().provider)
            assertEquals(true, harness.tokens.single().remember)
            assertFalse(harness.state().isLoading)
            assertNull(harness.state().primaryAction?.onClick)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun leavingScreenCancelsPendingPromptWithoutSubmitting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val harness = Harness(this)
            val collection = harness.start()
            runCurrent()
            harness.state().primaryAction!!.onClick!!.invoke()
            runCurrent()
            val prompt = harness.prompts.single()
            collection.cancel()
            runCurrent()
            assertFalse(prompt.active.value)
            prompt.complete(Result.success(harness.result(prompt)))
            runCurrent()
            assertEquals(emptyList(), harness.tokens)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Harness(private val scope: TestScope) {
        val prompts = mutableListOf<Fido2Prompt>()
        val tokens = mutableListOf<ServerTwoFactorToken>()
        var refreshes = 0
        var completions = 0
        private val state = MutableStateFlow<BitwardenLoginTwofaState?>(null)
        fun state() = assertNotNull(state.value) as BitwardenLoginTwofaState.Fido2WebAuthn

        suspend fun start() = scope.backgroundScope.launch {
            val screen = proxy<RememberStateFlowScope> { name, _ ->
                when (name) {
                    "mutablePersistedFlow" -> MutableStateFlow(false)
                    "translate" -> "Test"
                    else -> error("Unexpected screen call: $name")
                }
            }
            val account = proxy<AddAccount> { _, args ->
                val token = args[2] as ServerTwoFactorToken?
                val io: IO<AccountId> = {
                    if (token == null) {
                        refreshes++
                        throw ApiException(
                            title = TextHolder.Value("Two factor required"),
                            text = null,
                            exception = IllegalStateException("Test challenge refresh"),
                            code = HttpStatusCode.BadRequest,
                            error = "invalid_grant",
                            type = ApiException.Type.TwoFaRequired(listOf(provider("Ag"))),
                            message = null,
                        )
                    }
                    tokens += token
                    AccountId("test-account")
                }
                io
            }
            val flow = screen.createStateFlowForFido2WebAuthn(
                base64Service = proxy { _, _ -> error("Browser is not used in this test") },
                deeplinkService = DeeplinkServiceImpl(),
                json = Json,
                addAccount = account,
                args = BitwardenLoginTwofaRoute.Args(
                    providers = listOf(provider("AQ")), clientSecret = null,
                    email = "user@example.com", password = "password",
                    env = ServerEnv(baseUrl = "https://vault.example.com"),
                ),
                provider = provider("AQ"),
                transmitter = object : RouteResultTransmitter<Unit> {
                    override fun invoke(value: Unit) { completions++ }
                },
                defaultRememberMe = false,
            ).stateIn(this, SharingStarted.Eagerly, null)
            launch {
                flow.filterNotNull()
                    .map { (it as BitwardenLoginTwofaState.Fido2WebAuthn).prompts }
                    .distinctUntilChanged()
                    .flatMapLatest { it }
                    .collect { prompts += it }
            }
            flow.collect { state.value = it }
        }

        fun result(prompt: Fido2Prompt): ByteArray {
            val request = (prompt.operation as Fido2Operation.Assert).request
            return Fido2AssertionResult(
                credentialId = byteArrayOf(3),
                authenticatorData = NativeCrypto.primitives.sha256(request.rpId.encodeToByteArray()) +
                    byteArrayOf(1, 0, 0, 0, 1),
                signature = byteArrayOf(1),
                userHandle = null,
                appIdUsed = false,
                clientDataJson = request.clientDataJson,
            ).encode()
        }

        private fun provider(challenge: String) = TwoFactorProviderArgument.Fido2WebAuthn(
            Json.parseToJsonElement(
                """{"challenge":"$challenge","rpId":"vault.example.com","userVerification":"discouraged",
                    "allowCredentials":[{"id":"Aw","type":"public-key"}]}""",
            ),
        )
    }
}

private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { target, method, args ->
        if (method.isDefault) InvocationHandler.invokeDefault(target, method, *args.orEmpty())
        else call(method.name, args.orEmpty())
    } as T
