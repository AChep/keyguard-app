package com.artemchep.keyguard.common.service.icon.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.common.service.icon.ResolvedIcon
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.provider.bitwarden.transport.AccountHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IconRequestFetcherTest {
    private val directory = Files.createTempDirectory("icons")

    private val png = ByteArrayOutputStream().use { stream ->
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", stream)
        stream.toByteArray()
    }

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `downloads an icon once and then reads it from the disk`(): Unit = runBlocking {
        val resolver = FakeResolver()
        val loader = imageLoader(resolver)

        val first = loader.execute(request(FaviconUrl(url = "https://example.com/login")))
        val second = loader.execute(request(FaviconUrl(url = "https://example.com/other")))

        assertEquals(DataSource.NETWORK, assertIs<SuccessResult>(first).dataSource)
        assertEquals(DataSource.DISK, assertIs<SuccessResult>(second).dataSource)
        assertEquals(1, resolver.resolves.get())
        assertEquals(listOf("https://example.com/favicon.ico"), resolver.downloads)
    }

    @Test
    fun `a disabled icon is not read from the disk`(): Unit = runBlocking {
        val resolver = FakeResolver()
        val loader = imageLoader(resolver)
        val request = request(FaviconUrl(url = "https://example.com"))
        assertIs<SuccessResult>(loader.execute(request))

        resolver.allowed = false
        val result = loader.execute(request)

        assertIs<IconDisabledException>(assertIs<ErrorResult>(result).throwable)
        assertEquals(1, resolver.resolves.get())
    }

    @Test
    fun `an icon that can not be resolved fails`(): Unit = runBlocking {
        val resolver = FakeResolver(resolvable = false)

        val result = imageLoader(resolver).execute(request(FaviconUrl(url = "https://example.com")))

        assertIs<IconUnavailableException>(assertIs<ErrorResult>(result).throwable)
    }

    @Test
    fun `store icons go through the resolver on desktop`(): Unit = runBlocking {
        val resolver = FakeResolver()

        val result = imageLoader(resolver).execute(request(AppIconUrl("com.example")))

        assertEquals(DataSource.NETWORK, assertIs<SuccessResult>(result).dataSource)
        assertEquals(listOf<IconRequest>(AppIconUrl("com.example")), resolver.resolved)
    }

    @Test
    fun `ios and android store icons have separate disk entries`(): Unit = runBlocking {
        val resolver = FakeResolver()
        val loader = imageLoader(resolver)
        val android = AppIconUrl("com.example")
        val ios = IosAppIconUrl("com.example")

        assertEquals(DataSource.NETWORK, assertIs<SuccessResult>(loader.execute(request(android))).dataSource)
        assertEquals(DataSource.NETWORK, assertIs<SuccessResult>(loader.execute(request(ios))).dataSource)
        assertEquals(DataSource.DISK, assertIs<SuccessResult>(loader.execute(request(ios))).dataSource)
        assertEquals(listOf<IconRequest>(android, ios), resolver.resolved)
    }

    private fun imageLoader(resolver: IconResolver) = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            installIconRequestComponents(resolver)
        }
        .diskCache {
            DiskCache.Builder()
                .directory(directory.toOkioPath())
                .build()
        }
        .memoryCache(null)
        .build()

    private fun request(data: IconRequest) = ImageRequest.Builder(PlatformContext.INSTANCE)
        .data(data)
        .build()

    private inner class FakeResolver(
        private val resolvable: Boolean = true,
    ) : IconResolver {
        @Volatile
        var allowed = true

        val resolves = AtomicInteger()
        val resolved = mutableListOf<IconRequest>()
        val downloads = mutableListOf<String>()

        private val httpClient = HttpClient(
            MockEngine { request ->
                downloads += request.url.toString()
                respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            },
        )

        override suspend fun isAllowed(request: IconRequest) = allowed

        override suspend fun resolveAllowed(request: IconRequest): ResolvedIcon? {
            resolves.incrementAndGet()
            resolved += request
            if (!resolvable) {
                return null
            }
            return ResolvedIcon(
                url = "https://example.com/favicon.ico",
                client = AccountHttpClient(httpClient),
            )
        }
    }
}
