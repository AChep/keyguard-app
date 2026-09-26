package com.artemchep.keyguard.feature.navigation.state

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.WindowId
import com.artemchep.keyguard.platform.leBundleOf
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class RememberStateFlowScopeImplTest {
    @Test
    fun `screen executor reports an error exactly once while the screen is alive`() =
        checkFailureDelivery(disposeScreen = false)

    @Test
    fun `screen executor still delivers a toast after its screen is disposed`() =
        checkFailureDelivery(disposeScreen = true)

    private fun checkFailureDelivery(disposeScreen: Boolean) = runTest {
        val messages = mutableListOf<ToastMessage>()
        val messageHub = MessageHubImpl()
        val windowId = WindowId(1L)
        val unregister = messageHub.register(
            key = "App:Main",
            windowId = windowId,
            onMessage = { messages += it },
        )
        val windowContext = coroutineContext
        val screenScope = CoroutineScope(coroutineContext + Job())
        try {
            val producerScope = RememberStateFlowScopeImpl(
                key = "App:Main/setup",
                bundle = leBundleOf(),
                showMessage = messageHub,
                clipboardService = unused(),
                clipboardEventBus = ClipboardEventBus(),
                getScreenState = unused(),
                putScreenState = unused(),
                windowCoroutineScope = object : WindowCoroutineScope {
                    override val coroutineContext = windowContext
                },
                navigationController = unused(),
                backPressInterceptorHost = unused(),
                keyEventInterceptorHost = unused(),
                json = Json,
                scope = screenScope,
                screen = "setup",
                colorSchemeState = mutableStateOf(lightColorScheme()),
                windowIdState = mutableStateOf(windowId),
                screenName = "setup",
                context = LeContext(),
            )
            val executor = producerScope.screenExecutor()
            val release = CompletableDeferred<Unit>()
            assertTrue(executor.execute(io = {
                release.await()
                throw TestFailure()
            }))
            runCurrent()
            assertTrue(executor.isExecutingFlow.value)

            // Navigation destroys the originating screen's scope; its window and
            // root toast host remain alive while the operation finishes.
            if (disposeScreen) screenScope.cancel()
            release.complete(Unit)
            advanceUntilIdle()

            val message = messages.single()
            assertEquals(ToastMessage.Type.ERROR, message.type)
            assertEquals("Cannot open vault", message.title)
            assertEquals("Database unavailable", message.text)
            assertFalse(executor.isExecutingFlow.value)
        } finally {
            screenScope.cancel()
            unregister()
        }
    }

    private class TestFailure : RuntimeException(), Readable {
        override val title = TextHolder.Value("Cannot open vault")
        override val text = TextHolder.Value("Database unavailable")
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${T::class.simpleName}.${method.name}") } as T
}
