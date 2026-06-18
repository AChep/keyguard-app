package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.readByteArrayAndClose
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.common.usecase.CheckWebDavConnectionRequest
import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavAuthorization
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import io.ktor.client.HttpClient
import kotlin.random.Random
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CheckWebDavConnectionImpl internal constructor(
    private val clientFactory: (WebDavClientConfig) -> WebDavClient,
) : CheckWebDavConnection {
    constructor(
        httpClient: HttpClient,
    ) : this(
        clientFactory = { config ->
            KtorWebDavClient(
                httpClient = httpClient,
                config = config,
            )
        },
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        httpClient = directDI.instance(),
    )

    override fun invoke(
        request: CheckWebDavConnectionRequest,
    ): IO<Unit> = ioEffect {
        val client = clientFactory(
            WebDavClientConfig(
                baseUrl = request.url,
                authorization = request.toWebDavAuthorization(),
            ),
        )
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
}

private fun CheckWebDavConnectionRequest.toWebDavAuthorization(): WebDavAuthorization? {
    val username = username
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return WebDavAuthorization.Basic(
        username = username,
        password = password.orEmpty(),
    )
}

private fun createWebDavConnectionProbePath(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val nonce = Random.nextLong().toString().replace("-", "n")
    return "health-check/$timestamp-$nonce.probe"
}

private val WEBDAV_CONNECTION_CHECK_PAYLOAD =
    "keyguard-webdav-test\n".encodeToByteArray()
