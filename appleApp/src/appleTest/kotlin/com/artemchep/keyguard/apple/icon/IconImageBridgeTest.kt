package com.artemchep.keyguard.apple.icon

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.GetIconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.toIconKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class IconImageBridgeTest {
    private val request = FaviconUrl(serverId = "account", url = "https://example.com")

    @Test
    fun loadsTheImageOfAKey() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        val requests = mutableListOf<IconRequest>()
        val bridge = IconImageBridge(
            getIconImage = getIconImage { request ->
                requests += request
                bytes
            },
        )

        assertContentEquals(bytes, bridge.load(request.toIconKey()))
        assertEquals(listOf<IconRequest>(request), requests)
    }

    @Test
    fun aKeyThatIsNotARequestLoadsNothing() = runTest {
        val bridge = IconImageBridge(getIconImage { error("Not called") })

        assertNull(bridge.load("https://example.com/favicon.ico"))
    }

    @Test
    fun aDisabledIconLoadsNothing() = runTest {
        val bridge = IconImageBridge(getIconImage { null })

        assertNull(bridge.load(request.toIconKey()))
    }

    @Test
    fun aFailedDownloadThrows() = runTest {
        val bridge = IconImageBridge(getIconImage { throw IllegalStateException("Offline") })

        assertFailsWith<IllegalStateException> { bridge.load(request.toIconKey()) }
    }

    @Test
    fun limitsTheConcurrentDownloads() = runTest {
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        val bridge = IconImageBridge(
            getIconImage = getIconImage {
                running++
                maxRunning = maxOf(maxRunning, running)
                gate.await()
                running--
                null
            },
            permits = 2,
        )

        repeat(5) {
            launch { bridge.load(request.toIconKey()) }
        }
        runCurrent()
        assertEquals(2, maxRunning)
        gate.complete(Unit)
    }

    private fun getIconImage(
        block: suspend (IconRequest) -> ByteArray?,
    ) = object : GetIconImage {
        override fun invoke(request: IconRequest): IO<ByteArray?> = ioEffect { block(request) }
    }
}
