package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.toWebDavAuthorization
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.util.foundation.io.readByteArrayAndClose
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import io.ktor.client.HttpClient
import kotlin.random.Random
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CheckWebDavConnectionImpl internal constructor(
    private val clientFactory: WebDavClientFactory,
) : CheckWebDavConnection {
    constructor(
        httpClient: HttpClient,
    ) : this(
        clientFactory = KtorWebDavClientFactory(httpClient),
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        httpClient = directDI.instance(),
    )

    override fun invoke(
        location: WebDavLocation,
    ): IO<Unit> = ioEffect {
        val client = clientFactory.create(
            WebDavClientConfig(
                baseUrl = location.url,
                authorization = location.toWebDavAuthorization(),
            ),
        )
        when (location) {
            is WebDavLocation.Collection -> testReadWrite(client)
            is WebDavLocation.File -> testRead(client)
        }
    }

    private suspend fun testReadWrite(
        client: WebDavClient,
    ) {
        val probePath = createWebDavConnectionProbePath()
        val payload = WEBDAV_CONNECTION_CHECK_PAYLOAD
        try {
            client.open()
            client.write(
                path = probePath,
                mode = WebDavWriteMode.Create,
                bytes = payload,
            )
            val read = client.read(probePath).readByteArrayAndClose()
            check(payload.contentEquals(read)) {
                "WebDAV probe read returned different bytes."
            }
        } finally {
            try {
                client.delete(probePath)
            } catch (_: Exception) {
                // Best-effort cleanup must not hide the primary probe failure.
            }
            client.close()
        }
    }

    private suspend fun testRead(
        client: WebDavClient,
    ) {
        try {
            client.open()
            // The client should already point us to
            // the right resource.
            client.stat("")
        } finally {
            client.close()
        }
    }
}

private fun createWebDavConnectionProbePath(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val nonce = Random.nextLong().toString().replace("-", "n")
    return "health-check/$timestamp-$nonce.probe"
}

private val WEBDAV_CONNECTION_CHECK_PAYLOAD =
    "keyguard-webdav-test\n".encodeToByteArray()
