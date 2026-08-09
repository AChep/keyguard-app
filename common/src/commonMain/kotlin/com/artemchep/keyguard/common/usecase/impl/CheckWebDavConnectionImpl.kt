package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.parseWebDavKeePassFileUrl
import com.artemchep.keyguard.common.service.webdav.toWebDavAuthorization
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.util.io.readByteArrayAndClose
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
        when (location) {
            is WebDavLocation.Collection -> testReadWrite(
                client = createClient(
                    baseUrl = location.url,
                    location = location,
                ),
            )

            is WebDavLocation.File -> {
                val fileUrl = parseWebDavKeePassFileUrl(location.url)
                testRead(
                    client = createClient(
                        baseUrl = fileUrl.baseUrl,
                        location = location,
                    ),
                    path = fileUrl.path,
                )
            }
        }
    }

    private fun createClient(
        baseUrl: String,
        location: WebDavLocation,
    ): WebDavClient = clientFactory.create(
        WebDavClientConfig(
            baseUrl = baseUrl,
            authorization = location.toWebDavAuthorization(),
        ),
    )

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
        path: String,
    ) {
        try {
            client.open()
            val resource = client.stat(path)
                ?: throw IllegalStateException("The WebDAV resource does not exist.")
            check(!resource.isCollection) {
                "The WebDAV resource is not a file."
            }
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
