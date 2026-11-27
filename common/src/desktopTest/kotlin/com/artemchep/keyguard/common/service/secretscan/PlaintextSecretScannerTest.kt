package com.artemchep.keyguard.common.service.secretscan

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentMountsState
import com.artemchep.keyguard.common.service.secretscan.impl.PlaintextSecretScannerImpl
import com.artemchep.keyguard.common.service.secretscan.impl.SecretScanTrash
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.NativeKeyPairGenerator
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.EnvironmentFormat
import com.artemchep.keyguard.crypto.NativeSshKeyImportService
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class PlaintextSecretScannerTest {
    private val home: Path = createTempDirectory("secret-scan-home").toRealPath()

    private val mounts = FakeEnvironmentMounts()

    private val trash = FakeTrash()

    private val crypto = CryptoGeneratorJvm()

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `finds env files`() = runTest {
        write("proj/.env", "API_KEY=secret\nOTHER=value\n")
        write("proj/.env.production", "API_KEY=secret\n")

        val items = scanner().findAll(SecretScanKind.DotEnv)

        assertEquals(
            setOf(
                dotEnv("proj/.env", variableCount = 2),
                dotEnv("proj/.env.production", variableCount = 1),
            ),
            items.toSet(),
        )
    }

    @Test
    fun `each kind finds only its own files`() = runTest {
        val keyPair = generateEd25519()
        write(".ssh/id_ed25519", keyPair.privateKey.ssh)
        write("proj/.env", "API_KEY=secret\n")
        val scanner = scanner()

        assertEquals(
            listOf(path(".ssh/id_ed25519")),
            scanner.findAll(SecretScanKind.SshKey).map { it.path },
        )
        assertEquals(
            listOf(path("proj/.env")),
            scanner.findAll(SecretScanKind.DotEnv).map { it.path },
        )
    }

    @Test
    fun `skips examples, dependency folders, links and mounts`() = runTest {
        write("proj/.env.example", "API_KEY=secret\n")
        write("proj/.envrc", "export API_KEY=secret\n")
        write("proj/node_modules/x/.env", "API_KEY=secret\n")
        write("proj/.hidden/.env", "API_KEY=secret\n")
        write("mounted/.env", "API_KEY=secret\n")
        Files.createSymbolicLink(home.resolve("proj/loop"), home.resolve("proj"))
        home.resolve("proj/sub").createDirectories()
        Files.createSymbolicLink(home.resolve("proj/sub/.env"), home.resolve("mounted/.env"))
        mounts.state.value = EnvironmentMountsState(
            mounts = listOf(
                EnvironmentMount(id = "1", environmentId = "e", path = path("mounted/.env")),
            ),
        )

        assertEquals(emptyList(), scanner().findAll(SecretScanKind.DotEnv))
    }

    @Test
    fun `skips a FIFO`() = runTest {
        val fifo = home.resolve("proj/.env")
        fifo.parent.createDirectories()
        // Unix only; mkfifo is part of coreutils and may be missing elsewhere.
        val created = runCatching {
            ProcessBuilder("mkfifo", fifo.toString())
                .start()
                .apply { waitFor(10, TimeUnit.SECONDS) }
                .exitValue() == 0
        }.getOrDefault(false)
        if (!created) return@runTest

        assertEquals(emptyList(), scanner().findAll(SecretScanKind.DotEnv))
    }

    @Test
    fun `skips env files without literal values`() = runTest {
        write("proj/.env", "# A comment\nEMPTY=\nUNSET\n")

        assertEquals(emptyList(), scanner().findAll(SecretScanKind.DotEnv))
    }

    @Test
    fun `finds an unencrypted OpenSSH key`() = runTest {
        val keyPair = generateEd25519()
        write(".ssh/id_ed25519", keyPair.privateKey.ssh)

        assertEquals(
            listOf(
                SecretScanItem.SshKey(
                    path = path(".ssh/id_ed25519"),
                    displayPath = "~/.ssh/id_ed25519",
                    sha256 = sha256Of(".ssh/id_ed25519"),
                    encrypted = false,
                    fingerprint = keyPair.publicKey.fingerprint,
                ),
            ),
            scanner().findAll(SecretScanKind.SshKey),
        )
    }

    @Test
    fun `finds an encrypted key`() = runTest {
        val pem = encryptedPkcs1Pem(passphrase = "secret")
        write("keys/server.pem", pem)
        // The fixture is a real key that opens with its passphrase.
        assertIs<SshKeyImportResult.Success>(
            NativeSshKeyImportService.import(SshKeyImportRequest(pem, passphrase = "secret")),
        )

        assertEquals(
            listOf(
                SecretScanItem.SshKey(
                    path = path("keys/server.pem"),
                    displayPath = "~/keys/server.pem",
                    sha256 = sha256Of("keys/server.pem"),
                    encrypted = true,
                    fingerprint = null,
                ),
            ),
            scanner().findAll(SecretScanKind.SshKey),
        )
    }

    @Test
    fun `skips public keys, known hosts and non-key files`() = runTest {
        val keyPair = generateEd25519()
        // Private key content in files that are never keys by name.
        write(".ssh/id_ed25519.pub", keyPair.privateKey.ssh)
        write(".ssh/known_hosts", keyPair.privateKey.ssh)
        write(".ssh/config", keyPair.privateKey.ssh)
        write("proj/notes.txt", keyPair.privateKey.ssh)
        // Key-like names without a private key.
        write(".ssh/id_rsa", "not a key\n")

        assertEquals(emptyList(), scanner().findAll(SecretScanKind.SshKey))
    }

    @Test
    fun `enforces the depth cap`() = runTest {
        // Depth 8: seven folders and the file.
        write("1/2/3/4/5/6/7/.env", "A=b\n")
        // Depth 9: eight folders and the file.
        write("1/2/3/4/5/6/7/8/.env", "A=b\n")

        assertEquals(
            listOf(path("1/2/3/4/5/6/7/.env")),
            scanner().findAll(SecretScanKind.DotEnv).map { it.path },
        )
    }

    @Test
    fun `enforces the size cap`() = runTest {
        write("proj/.env", "A=" + "b".repeat(EnvironmentFormat.MAX_PAYLOAD_BYTES) + "\n")

        assertEquals(emptyList(), scanner().findAll(SecretScanKind.DotEnv))
    }

    @Test
    fun `macOS protected folders are skipped unless added`() = runTest {
        write("Documents/proj/.env", "A=b\n")
        val scanner = scanner(platform = Platform.Desktop.MacOS.Jvm(majorVersion = 27))

        assertEquals(emptyList(), scanner.findAll(SecretScanKind.DotEnv))
        assertEquals(
            listOf(path("Documents/proj/.env")),
            scanner.findAll(SecretScanKind.DotEnv, path("Documents")).map { it.path },
        )
    }

    @Test
    fun `flatpak scans only added folders`() = runTest {
        write("proj/.env", "A=b\n")
        val scanner = scanner(platform = Platform.Desktop.Linux(isFlatpak = true))

        assertFalse(scanner.defaultRootsAvailable)
        val events = scanner.scan(SecretScanKind.DotEnv, emptyList()).toList()
        assertEquals(SecretScanEvent.Done(emptyList()), events.last())
        assertEquals(emptyList(), events.filterIsInstance<SecretScanEvent.Found>())

        val added = scanner.scan(SecretScanKind.DotEnv, listOf(path("proj"))).toList()
        assertEquals(SecretScanEvent.Done(listOf("~/proj")), added.last())
        assertEquals(1, added.filterIsInstance<SecretScanEvent.Found>().size)
    }

    @Test
    fun `reports progress and the scanned roots`() = runTest {
        write("proj/a.txt", "a")
        write("proj/b.txt", "b")

        val events = scanner().scan(SecretScanKind.DotEnv, emptyList()).toList()

        assertEquals(SecretScanEvent.Progress(checked = 2), events[events.lastIndex - 1])
        assertEquals(SecretScanEvent.Done(listOf("~")), events.last())
    }

    @Test
    fun `read returns null after the file changed`() = runTest {
        write("proj/.env", "A=b\n")
        val scanner = scanner()
        val item = scanner.findAll(SecretScanKind.DotEnv).single()

        assertEquals("A=b\n", assertNotNull(scanner.read(item)).decodeToString())

        write("proj/.env", "A=c\n")
        assertNull(scanner.read(item))
    }

    @Test
    fun `remove moves the unchanged files to the trash`() = runTest {
        write("a/.env", "A=b\n")
        write("b/.env", "A=b\n")
        write("c/.env", "A=b\n")
        val scanner = scanner()
        val items = scanner.findAll(SecretScanKind.DotEnv).sortedBy { it.path }
        write("b/.env", "A=c\n")

        assertEquals(
            mapOf(path("a/.env") to true, path("b/.env") to false, path("c/.env") to true),
            scanner.remove(items),
        )
        // One call for all the files.
        assertEquals(listOf(listOf(home.resolve("a/.env"), home.resolve("c/.env"))), trash.calls)
        assertTrue(home.resolve("a/.env").exists())
    }

    @Test
    fun `remove deletes the unchanged files without a trash`() = runTest {
        write("a/.env", "A=b\n")
        write("b/.env", "A=b\n")
        trash.supported = false
        val scanner = scanner()
        val items = scanner.findAll(SecretScanKind.DotEnv).sortedBy { it.path }
        write("b/.env", "A=c\n")

        assertFalse(scanner.trashSupported)
        assertEquals(
            mapOf(path("a/.env") to true, path("b/.env") to false),
            scanner.remove(items),
        )
        assertEquals(emptyList(), trash.calls)
        assertFalse(home.resolve("a/.env").exists())
        assertTrue(home.resolve("b/.env").exists())
    }

    @Test
    fun `a symlinked home still shows as a tilde`() = runTest {
        write("proj/.env", "A=b\n")
        val link = createTempDirectory("secret-scan-link").resolve("home")
        Files.createSymbolicLink(link, home)
        try {
            val scanner = scanner(homeDir = link)
            val events = scanner.scan(SecretScanKind.DotEnv, emptyList()).toList()

            assertEquals(SecretScanEvent.Done(listOf("~")), events.last())
            assertEquals(
                listOf("~/proj/.env"),
                events.filterIsInstance<SecretScanEvent.Found>().map { it.item.displayPath },
            )
        } finally {
            link.parent.toFile().deleteRecursively()
        }
    }

    private fun scanner(
        platform: Platform = Platform.Desktop.Linux(isFlatpak = false),
        homeDir: Path = home,
    ) = PlaintextSecretScannerImpl(
        sshKeyImportService = NativeSshKeyImportService,
        environmentMounts = mounts,
        cryptoGenerator = crypto,
        platform = platform,
        homeDir = homeDir,
        trash = trash,
    )

    private suspend fun PlaintextSecretScanner.findAll(
        kind: SecretScanKind,
        vararg extraRoots: String,
    ): List<SecretScanItem> = scan(kind, extraRoots.toList())
        .toList()
        .filterIsInstance<SecretScanEvent.Found>()
        .map { it.item }

    private fun write(relative: String, content: String) {
        val file = home.resolve(relative)
        file.parent.createDirectories()
        file.writeText(content)
    }

    private fun path(relative: String): String = home.resolve(relative).toString()

    private fun sha256Of(relative: String): String = crypto
        .hashSha256(Files.readAllBytes(home.resolve(relative)))
        .toHex()

    private fun dotEnv(relative: String, variableCount: Int) = SecretScanItem.DotEnv(
        path = path(relative),
        displayPath = "~/$relative",
        sha256 = sha256Of(relative),
        variableCount = variableCount,
        literalDigest = assertNotNull(
            EnvironmentDotenv.parse(home.resolve(relative).readText())
                .secretScanLiteralDigest(crypto),
        ),
    )

    private fun generateEd25519(): KeyPair {
        val generator = NativeKeyPairGenerator(Base64ServiceImpl())
        return generator.populate(generator.ed25519())
    }

    /**
     * An RSA key in the legacy encrypted PEM form that `ssh-keygen -m PEM`
     * and OpenSSL write: PKCS#1 under AES-128-CBC with an MD5 key derivation.
     */
    private fun encryptedPkcs1Pem(passphrase: String): String {
        val pkcs8 = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .private
            .encoded
        // A JCA RSA PKCS#8 key wraps PKCS#1 after a fixed 26-byte header:
        // SEQUENCE, version, algorithm identifier and the OCTET STRING header.
        check(pkcs8[22] == 0x04.toByte() && pkcs8[23] == 0x82.toByte())
        val pkcs1 = pkcs8.copyOfRange(26, pkcs8.size)
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = MessageDigest.getInstance("MD5")
            .apply {
                update(passphrase.toByteArray())
                update(iv, 0, 8)
            }
            .digest()
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(pkcs1)
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encrypted)
        val ivHex = iv.joinToString(separator = "") { "%02X".format(it) }
        return "-----BEGIN RSA PRIVATE KEY-----\n" +
            "Proc-Type: 4,ENCRYPTED\n" +
            "DEK-Info: AES-128-CBC,$ivHex\n" +
            "\n" +
            "$body\n" +
            "-----END RSA PRIVATE KEY-----\n"
    }

    private class FakeEnvironmentMounts : EnvironmentMounts {
        override val state = MutableStateFlow(EnvironmentMountsState())

        override suspend fun create(
            environmentId: String,
            path: String,
            bookmark: String?,
        ): EnvironmentMount = error("unused")

        override suspend fun disable(id: String) = error("unused")
    }

    private class FakeTrash : SecretScanTrash {
        val calls = mutableListOf<List<Path>>()

        override var supported: Boolean = true

        override fun moveToTrash(paths: List<Path>): Set<Path> {
            calls.add(paths)
            return paths.toSet()
        }
    }
}
