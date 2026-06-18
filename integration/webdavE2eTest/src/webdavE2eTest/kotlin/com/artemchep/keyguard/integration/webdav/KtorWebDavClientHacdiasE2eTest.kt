package com.artemchep.keyguard.integration.webdav

import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlinx.io.readByteArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorWebDavClientHacdiasE2eTest {
    @Test
    fun `open validates auth no auth and client close`() = runTest {
        withServer { server ->
            server.client().use { client ->
                val result = client.open()

                assertNotNull(result.dav)
                assertNotNull(result.allow)
            }

            server.client(password = "wrong").use { client ->
                assertFailsWith<WebDavException.AuthenticationFailed> {
                    client.open()
                }
            }
        }

        withServer(
            ServerConfig(
                users = emptyList(),
            ),
        ) { server ->
            server.client(username = null, password = null).use { client ->
                client.open()
                client.close()
            }
        }
    }

    @Test
    fun `open maps unreachable server to transient failure`() = runTest {
        client(
            baseUrl = HacdiasWebDavServer.unusedBaseUrl(),
        ).use { client ->
            val failure = assertFailsWith<WebDavException.Transient> {
                client.open()
            }

            assertTrue(failure.retryable)
        }
    }

    @Test
    fun `stat reads files collections missing paths and encoded path segments`() = runTest {
        withServer { server ->
            server.root.resolve("dir/file #.txt").writeBytes("metadata".encodeToByteArray())
            Files.createDirectories(server.root.resolve("folder"))

            server.client().use { client ->
                val file = assertNotNull(client.stat("dir/file #.txt"))
                assertEquals("dir/file #.txt", file.path)
                assertFalse(file.isCollection)
                assertEquals("metadata".encodeToByteArray().size.toLong(), file.size)
                assertNotNull(file.lastModified)
                assertNotNull(file.etag)

                val folder = assertNotNull(client.stat("folder"))
                assertEquals("folder", folder.path)
                assertTrue(folder.isCollection)

                assertNull(client.stat("missing.txt"))
            }
        }
    }

    @Test
    fun `read supports full and ranged reads and maps failures`() = runTest {
        withServer { server ->
            server.root.resolve("data.bin").writeBytes("0123456789".encodeToByteArray())
            Files.createDirectories(server.root.resolve("folder"))

            server.client().use { client ->
                assertContentEquals(
                    "0123456789".encodeToByteArray(),
                    client.read("data.bin").readBytesAndClose(),
                )
                assertContentEquals(
                    "2345".encodeToByteArray(),
                    client.read(
                        "data.bin",
                        WebDavByteRange(
                            offset = 2L,
                            length = 4L,
                        ),
                    ).readBytesAndClose(),
                )
                assertContentEquals(
                    "56789".encodeToByteArray(),
                    client.read(
                        "data.bin",
                        WebDavByteRange(offset = 5L),
                    ).readBytesAndClose(),
                )

                assertFailsWith<WebDavException.NotFound> {
                    client.read("missing.bin")
                }
                assertFailsWith<WebDavException.NotFound> {
                    client.read("folder")
                }
                assertFailsWith<WebDavException.InvalidRange> {
                    client.read(
                        "data.bin",
                        WebDavByteRange(
                            offset = 100L,
                            length = 1L,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `write creates replaces reports conflicts and cleans temp files`() = runTest {
        withServer { server ->
            server.client().use { client ->
                val created = client.write(
                    path = "nested/a/file.txt",
                    mode = WebDavWriteMode.Create,
                    bytes = "one".encodeToByteArray(),
                )
                assertEquals("nested/a/file.txt", created.path)
                assertContentEquals(
                    "one".encodeToByteArray(),
                    server.root.resolve("nested/a/file.txt").readBytes(),
                )

                client.write(
                    path = "nested/a/file.txt",
                    mode = WebDavWriteMode.CreateOrReplace,
                    bytes = "two".encodeToByteArray(),
                )
                assertContentEquals(
                    "two".encodeToByteArray(),
                    client.read("nested/a/file.txt").readBytesAndClose(),
                )

                assertFailsWith<WebDavException.AlreadyExists> {
                    client.write(
                        path = "nested/a/file.txt",
                        mode = WebDavWriteMode.Create,
                        bytes = "three".encodeToByteArray(),
                    )
                }
                assertFalse(
                    Files.list(server.root.resolve("nested/a")).use { paths ->
                        paths.anyMatch { path -> path.fileName.toString().endsWith(".tmp") }
                    },
                )

                server.root.resolve("blocked-parent").writeBytes("file".encodeToByteArray())
                assertFailsWith<WebDavException.AlreadyExists> {
                    client.write(
                        path = "blocked-parent/child.txt",
                        bytes = "child".encodeToByteArray(),
                    )
                }
            }
        }
    }

    @Test
    fun `write and delete honor read only permissions`() = runTest {
        withServer { server ->
            server.root.resolve("existing.txt").writeBytes("keep".encodeToByteArray())

            server.client(username = "reader", password = "reader").use { client ->
                assertFailsWith<WebDavException.PermissionDenied> {
                    client.write(
                        path = "new.txt",
                        bytes = "blocked".encodeToByteArray(),
                    )
                }
                assertFailsWith<WebDavException.PermissionDenied> {
                    client.delete("existing.txt")
                }
            }

            server.client().use { client ->
                client.delete("missing.txt")

                Files.createDirectories(server.root.resolve("collection"))
                server.root.resolve("collection/child.txt").writeBytes("child".encodeToByteArray())
                client.delete("collection")
                assertTrue(Files.exists(server.root.resolve("collection/child.txt")))

                client.delete("existing.txt")
                assertFalse(Files.exists(server.root.resolve("existing.txt")))
            }
        }
    }

    @Test
    fun `list recursively filters sorts and handles empty prefixes`() = runTest {
        withServer { server ->
            server.root.resolve("snapshots/b.txt").writeBytes("b".encodeToByteArray())
            server.root.resolve("snapshots/a/two.bin").writeBytes("two".encodeToByteArray())
            server.root.resolve("snapshots/a/one.txt").writeBytes("one".encodeToByteArray())
            Files.createDirectories(server.root.resolve("snapshots/empty"))
            server.root.resolve("other.txt").writeBytes("other".encodeToByteArray())
            server.root.resolve("plain-file").writeBytes("plain".encodeToByteArray())

            server.client().use { client ->
                assertEquals(
                    listOf(
                        "snapshots/a/one.txt",
                        "snapshots/a/two.bin",
                        "snapshots/b.txt",
                    ),
                    client.list("snapshots/").map { it.path },
                )
                assertEquals(emptyList(), client.list("missing/"))
                assertEquals(emptyList(), client.list("plain-file/"))
            }
        }
    }

    @Test
    fun `client works with non root webdav prefix`() = runTest {
        withServer(
            ServerConfig(
                prefix = "/dav/",
            ),
        ) { server ->
            server.client().use { client ->
                client.open()
                client.write(
                    path = "prefixed/file.txt",
                    bytes = "prefix".encodeToByteArray(),
                )

                assertContentEquals(
                    "prefix".encodeToByteArray(),
                    client.read("prefixed/file.txt").readBytesAndClose(),
                )
                assertEquals(
                    listOf("prefixed/file.txt"),
                    client.list("prefixed/").map { it.path },
                )
            }
        }
    }

    @Test
    fun `per user directories isolate client roots`() = runTest {
        withServer(
            ServerConfig(
                users = listOf(
                    UserConfig(
                        username = "admin",
                        password = "admin",
                        permissions = "CRUD",
                    ),
                    UserConfig(
                        username = "john",
                        password = "john",
                        permissions = "CRUD",
                        directoryName = "john-root",
                    ),
                ),
            ),
        ) { server ->
            server.client(username = "john", password = "john").use { client ->
                client.write(
                    path = "owned.txt",
                    bytes = "john".encodeToByteArray(),
                )
                assertContentEquals(
                    "john".encodeToByteArray(),
                    client.read("owned.txt").readBytesAndClose(),
                )
            }

            assertTrue(Files.exists(server.root.resolve("john-root/owned.txt")))
            assertFalse(Files.exists(server.root.resolve("owned.txt")))
        }
    }

    @Test
    fun `user rules deny configured paths`() = runTest {
        withServer(
            ServerConfig(
                users = listOf(
                    UserConfig(
                        username = "limited",
                        password = "limited",
                        permissions = "CRUD",
                        rules = listOf(
                            RuleConfig(
                                path = "/blocked/",
                                permissions = "none",
                            ),
                        ),
                    ),
                ),
            ),
        ) { server ->
            server.client(username = "limited", password = "limited").use { client ->
                assertFailsWith<WebDavException.PermissionDenied> {
                    client.write(
                        path = "blocked/file.txt",
                        bytes = "blocked".encodeToByteArray(),
                    )
                }
            }
        }
    }

    private suspend fun withServer(
        config: ServerConfig = ServerConfig(),
        block: suspend (HacdiasWebDavServer) -> Unit,
    ) {
        val server = HacdiasWebDavServer.start(config)
        try {
            block(server)
        } catch (e: Throwable) {
            throw AssertionError("${e.message}\n${server.diagnostics()}", e)
        } finally {
            server.close()
        }
    }

    private fun client(
        baseUrl: String,
    ): KtorWebDavClient = KtorWebDavClient(
        httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
        config = com.artemchep.keyguard.util.webdav.WebDavClientConfig(
            baseUrl = baseUrl,
            authorization = com.artemchep.keyguard.util.webdav.WebDavAuthorization.Basic(
                username = "admin",
                password = "admin",
            ),
            userAgent = "KeyguardWebDavE2eTest",
        ),
        closeHttpClient = true,
    )

    private suspend fun <T> KtorWebDavClient.use(
        block: suspend (KtorWebDavClient) -> T,
    ): T = try {
        block(this)
    } finally {
        close()
    }

    private fun Path.writeBytes(
        bytes: ByteArray,
    ) {
        parent?.let(Files::createDirectories)
        Files.write(this, bytes)
    }

    private fun Path.readBytes(): ByteArray = Files.readAllBytes(this)

    private fun Source.readBytesAndClose(): ByteArray = try {
        readByteArray()
    } finally {
        close()
    }
}
