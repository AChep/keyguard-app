package com.artemchep.keyguard.test.companion

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.Main
import com.artemchep.keyguard.android.CompanionAuthActivity
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProtocol
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequest
import com.artemchep.keyguard.test.BaseTest
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * Plays the watch: sends a sign-in request to the phone,
 * then opens the screen the watch would open on the phone.
 *
 * Run against a fresh or locked target app: the phone signs
 * the watch in without unlocking its own vault.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class CompanionAuthActivityTest : BaseTest() {
    @Test
    fun bitwardenRequestOpensLoginScreen() = assertLoginScreenOpens(CompanionAuthProvider.BITWARDEN)

    @Test
    fun keePassRequestOpensLoginScreen() = assertLoginScreenOpens(CompanionAuthProvider.KEEPASS)

    private fun assertLoginScreenOpens(
        provider: CompanionAuthProvider,
    ) {
        val application = ApplicationProvider.getApplicationContext<Main>()
        // A phone has Wearable services only with the Wear OS app,
        // so run this on a watch emulator or on a paired phone.
        val hasWearable = runCatching {
            Tasks.await(Wearable.getNodeClient(application).localNode)
        }.isSuccess
        assumeTrue("Wearable services are not available", hasWearable)

        val koin = application.koin
        val requestId = UUID.randomUUID().toString()
        val request = CompanionAuthRequest(
            requestId = requestId,
            provider = provider,
            protocolVersion = CompanionAuthProtocol.VERSION,
            watchPublicKey = createWatchPublicKey(),
        )
        val event = WatchMessageEvent(
            path = CompanionAuthProtocol.REQUEST_PATH,
            data = koin.get<Json>()
                .encodeToString(CompanionAuthRequest.serializer(), request)
                .encodeToByteArray(),
        )
        // Sending the reply fails, as no watch is
        // connected. The phone stores the session first.
        runBlocking {
            runCatching {
                koin.get<CompanionAuthBridgeAndroid>().onMessageReceived(event)
            }
        }

        val intent = CompanionAuthActivity.getIntent(
            context = application,
            requestId = requestId,
            provider = provider,
        )
        ActivityScenario.launch<CompanionAuthActivity>(intent).use { scenario ->
            // The activity finishes when it does not find the request, and
            // the app crashes when the login screen fails to compose.
            SystemClock.sleep(SETTLE_MS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(
                "The $provider login screen is not shown",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    private fun createWatchPublicKey(): String {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val publicKey = generator.generateKeyPair().public
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    private class WatchMessageEvent(
        private val path: String,
        private val data: ByteArray,
    ) : MessageEvent {
        override fun getRequestId(): Int = 0

        override fun getPath(): String = path

        override fun getData(): ByteArray = data

        override fun getSourceNodeId(): String = WATCH_NODE_ID
    }

    private companion object {
        const val WATCH_NODE_ID = "companion-auth-test-watch"
        const val SETTLE_MS = 3_000L
    }
}
