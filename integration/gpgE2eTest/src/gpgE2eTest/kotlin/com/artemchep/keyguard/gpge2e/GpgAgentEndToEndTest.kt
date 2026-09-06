package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.gpgagent.authorizedAgentKeys
import com.artemchep.keyguard.common.service.gpgagent.isWindowsNamedPipePath
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.io.path.deleteRecursively
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpgAgentEndToEndTest {

    private enum class GpgErrorCode(val code: Int) {
        InvalidValue(5),
        NoSecretKey(17),
        NotFound(27),
        NotSupported(60),
        Truncated(74),
        UnsupportedAlgorithm(84),
        AssLineTooLong(263),
        AssTooMuchData(273),
        AssUnexpectedCommand(274),
        AssUnknownCommand(275),
        AssSyntax(276),
        AssCanceled(277),
        AssParameter(280),
    }

    companion object {
        private const val MISSING_KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
        private const val VALID_SHA256_HEX =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        private lateinit var workRoot: Path
        private lateinit var serverHome: Path
        private lateinit var clientHome: Path
        private lateinit var gpgAgentSocket: String
        private lateinit var launcher: KeyguardAgentLauncher
        private lateinit var keys: GpgKeyFactory.GeneratedKeys
        private lateinit var generatedModern: TestGpgKey
        private lateinit var generatedRsaKeys: Map<GpgKeyConfig.RsaLength, TestGpgKey>
        private lateinit var generatedKeys: List<GeneratedKeyCase>
        private lateinit var clientGpg: GpgCli
        private lateinit var binaryPath: Path

        @JvmStatic
        @BeforeClass
        fun setup() {
            val repoRoot = Path.of(requireNotNull(System.getProperty("keyguard.repoRoot")) {
                "Missing system property keyguard.repoRoot"
            })
            GpgToolchain.current.verifyCompatibleForE2e()

            // Short base path so the Unix socket paths stay well under the ~104 char limit.
            val token = randomHex(4)
            workRoot = if (isWindows()) {
                Files.createTempDirectory("kg-gpge2e-$token-")
            } else {
                Path.of("/tmp", "kg-gpge2e-$token")
            }
            Files.createDirectories(workRoot)
            restrict(workRoot)

            serverHome = workRoot.resolve("s")
            clientHome = workRoot.resolve("c")

            binaryPath = buildAndLocateBinary(repoRoot)

            // 1) Generate the test keys with a real gpg-agent in the server home.
            keys = GpgKeyFactory.generate(serverHome)
            // Don't leave the server agent lingering.
            GpgCli(serverHome).gpgconf("--kill", "gpg-agent")

            // 1b) Generate keys with Keyguard's OWN generator. These carry Kotlin-computed
            // keygrips in their metadata; the whole point is that gpg's keygrip lookup for
            // the imported public keys must match those, or the agent can never find them.
            generatedModern = keyguardGeneratedKey(
                GpgKeyConfig.Modern(
                    userId = "Keyguard Generated Modern <gen-modern@keyguard.test.invalid>",
                ),
            )
            generatedRsaKeys = GpgKeyConfig.RsaLength.entries.associateWith { length ->
                keyguardGeneratedKey(
                    GpgKeyConfig.Rsa(
                        userId = "Keyguard Generated RSA ${length.size} <gen-rsa-${length.size}@keyguard.test.invalid>",
                        length = length,
                    ),
                )
            }
            generatedKeys = listOf(GeneratedKeyCase(generatedModern, "gen-modern")) +
                generatedRsaKeys.map { (length, key) ->
                    GeneratedKeyCase(key, "gen-rsa-${length.size}")
                }
            val allKeys = keys.all + generatedKeys.map { it.key }

            // 2) Prepare the client home: it must use OUR socket, not its own agent.
            prepareClientHome(clientHome)
            clientGpg = GpgCli(clientHome)
            importPublicKeys(clientGpg, allKeys)
            GpgCli(clientHome).gpgconf("--kill", "gpg-agent")

            // 3) Stand up the Kotlin IPC server + Rust binary at the same
            // endpoint that the real gpg client resolves for this GNUPGHOME.
            val authToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val processor = TestGpgAgentRequestProcessor(keys = allKeys)
            launcher = KeyguardAgentLauncher(
                binaryPath = binaryPath,
                processor = processor,
                authToken = authToken,
            )
            val ipcEndpoint = if (isWindows()) {
                AgentIpcEndpoint.WindowsPipe("\\\\.\\pipe\\kg-gpge2e-$token-ipc")
            } else {
                val ipcSocketPath = workRoot.resolve("ipc.sock")
                AgentIpcEndpoint.UnixSocket(
                    socketPath = ipcSocketPath,
                    directory = workRoot,
                )
            }
            gpgAgentSocket = gpgAgentSocketForClientHome(clientHome)
            prepareGpgAgentSocketDirectory(clientHome, gpgAgentSocket)
            launcher.start(ipcEndpoint = ipcEndpoint, gpgSocket = gpgAgentSocket)
        }

        @JvmStatic
        @AfterClass
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fun teardown() {
            runCatching { if (::launcher.isInitialized) launcher.stop() }
            runCatching { if (::serverHome.isInitialized) GpgCli(serverHome).gpgconf("--kill", "gpg-agent") }
            runCatching { if (::clientHome.isInitialized) GpgCli(clientHome).gpgconf("--kill", "gpg-agent") }
            runCatching { if (::clientHome.isInitialized) GpgCli(clientHome).gpgconf("--remove-socketdir") }
            runCatching { if (::workRoot.isInitialized) workRoot.deleteRecursively() }
        }

        private fun buildAndLocateBinary(repoRoot: Path): Path {
            val agentDir = repoRoot.resolve("desktopGpgAgent").resolve("src")
            val build = ProcessBuilder("cargo", "build", "--release")
                .directory(agentDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            require(build.waitFor() == 0) {
                "cargo build --release failed for $agentDir"
            }
            val binary = agentDir
                .resolve("target")
                .resolve("release")
                .resolve(executableName("keyguard-gpg-agent"))
            require(Files.isExecutable(binary)) {
                "keyguard-gpg-agent binary not found / not executable at $binary"
            }
            return binary
        }

        private fun keyguardGeneratedKey(config: GpgKeyConfig): TestGpgKey {
            val generated = NativeGpgKeyGenerator.generate(config)
            val resolution = NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = generated.privateKeyArmored,
                publicKeyArmored = generated.publicKeyArmored,
                fingerprint = generated.fingerprint,
            )
                ?: error("Could not resolve agent metadata for the generated test key")
            return TestGpgKey(
                name = config.userId,
                privateKeyArmored = generated.privateKeyArmored,
                publicKeyArmored = generated.publicKeyArmored,
                primaryFingerprint = generated.fingerprint,
                // The Kotlin-computed keygrips — gpg's keygrip lookup must match these.
                metadataKeys = resolution.authorizedAgentKeys,
            )
        }

        private fun prepareClientHome(home: Path) {
            Files.createDirectories(home)
            restrict(home)
            // No allow-loopback needed on the client: the agent that handles PKSIGN/
            // PKDECRYPT is OUR Rust binary, not a gpg-agent. --no-autostart on each call
            // makes gpg reuse the already-bound agent socket.
            Files.writeString(home.resolve("gpg.conf"), "no-autostart\ntrust-model always\n")
        }

        private fun importPublicKeys(gpg: GpgCli, keys: List<TestGpgKey>) {
            for (key in keys) {
                val result = gpg.run(
                    "--no-autostart", "--batch", "--yes", "--import",
                    stdin = key.publicKeyArmored.encodeToByteArray(),
                )
                require(result.isSuccess) {
                    "Failed to import public key ${key.name}:\n${result.stderr}"
                }
                // Set ultimate ownertrust so encryption doesn't prompt.
                val ownertrust = "${key.primaryFingerprint}:6:\n"
                gpg.run(
                    "--no-autostart", "--batch", "--yes", "--import-ownertrust",
                    stdin = ownertrust.encodeToByteArray(),
                )
            }
        }

        private fun restrict(dir: Path) {
            runCatching {
                Files.setPosixFilePermissions(
                    dir,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
            }
        }

        private fun randomHex(bytes: Int): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }

        private fun gpgAgentSocketForClientHome(home: Path, gpg: GpgCli = GpgCli(home)): String {
            val result = gpg.gpgconf("--list-dirs", "agent-socket")
            require(result.isSuccess) {
                "Failed to resolve GPG agent socket:\n${result.stderr}"
            }
            val socket = result.stdout
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.let { line ->
                    if (line.startsWith("agent-socket:")) {
                        line.substringAfter("agent-socket:")
                    } else {
                        line
                    }
                }
                ?: error("gpgconf did not report an agent-socket:\n${result.stdout}")
            if (isWindows()) {
                require(
                    !isWindowsNamedPipePath(socket) && Path.of(socket).isAbsolute,
                ) {
                    "Expected a Windows libassuan marker-file socket, got: $socket"
                }
            } else {
                require(Path.of(socket).isAbsolute) {
                    "Expected gpgconf agent-socket to be absolute, got: $socket"
                }
            }
            return socket
        }

        private fun prepareGpgAgentSocketDirectory(home: Path, socket: String) {
            if (isWindows()) {
                Files.createDirectories(requireNotNull(Path.of(socket).parent))
                return
            }

            val homePath = home.toAbsolutePath().normalize()
            val socketPath = Path.of(socket).toAbsolutePath().normalize()
            if (socketPath.startsWith(homePath)) return

            val result = GpgCli(home).gpgconf("--create-socketdir")
            require(result.isSuccess) {
                "gpgconf resolved agent-socket outside GNUPGHOME ($socket), " +
                    "but failed to create its socket directory:\n${result.stderr}\n${result.stdout}"
            }
        }

        private fun executableName(name: String): String =
            if (isWindows()) "$name.exe" else name

        private data class GeneratedKeyCase(
            val key: TestGpgKey,
            val label: String,
        )
    }

    @Test
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun `native defaults sign from persistent homes across socket directory recreation`() {
        assumeTrue(!isWindows())
        val macos = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        persistentDataHomes(macos).forEachIndexed { index, xdgDataHome ->
            verifyPersistentHomeCase(index, xdgDataHome, macos)
        }
    }

    private fun persistentDataHomes(macos: Boolean): List<String?> {
        if (macos) return listOf(null)
        val dataLink = Files.createSymbolicLink(
            workRoot.resolve("data-link"),
            Files.createDirectory(workRoot.resolve("data-target")),
        )
        return listOf(
            null,
            "",
            "relative",
            workRoot.resolve("data space").toString(),
            // Keep the raw environment spelling: Path.of would already collapse the separators.
            "$workRoot//data repeated///",
            // GnuPG hashes this lexical spelling, including the symlink and dot component.
            "$dataLink/./",
        )
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun verifyPersistentHomeCase(index: Int, xdgDataHome: String?, macos: Boolean) {
        val caseRoot = Files.createDirectory(workRoot.resolve("d$index")).also(::restrict)
        val userHome = Files.createDirectory(caseRoot.resolve("home space"))
        val sharedPermissions = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(userHome, sharedPermissions)
        val dataRoot = xdgDataHome?.takeIf { it.startsWith('/') }?.let(Path::of)
            ?: userHome.resolve(".local/share")
        val home = if (macos) userHome.resolve(".keyguard/gnupg") else dataRoot.resolve("keyguard/gnupg")
        prepareClientHome(home)
        restrict(home.parent)
        val commonConfig = Files.writeString(home.resolve("common.conf"), "# persistent\nno-autostart\n")
        Files.setPosixFilePermissions(commonConfig, PosixFilePermissions.fromString("rw-------"))
        val defaultHome = Files.createDirectory(userHome.resolve(".gnupg"))
        val defaultConfig = Files.writeString(defaultHome.resolve("common.conf"), "# untouched\n")
        val legacyHome = if (macos) {
            userHome.resolve("Library/Group Containers/com.artemchep.keyguard/gnupg")
        } else {
            caseRoot.resolve("run/keyguard-gpg-agent")
        }
        val runtime = caseRoot.resolve("run")
        val environment = mapOf(
            "HOME" to userHome.toString(),
            "XDG_DATA_HOME" to xdgDataHome,
            "XDG_RUNTIME_DIR" to runtime.toString(),
            "GNUPGHOME" to defaultHome.toString(),
            "container" to null,
            "FLATPAK_ID" to null,
        )
        val fixture = PersistentHomeFixture(
            caseRoot, userHome, home, runtime, legacyHome,
            commonConfig, defaultConfig, sharedPermissions, environment,
            GpgCli(home, environmentOverrides = environment),
        )
        importPublicKeys(fixture.gpg, listOf(generatedModern))
        try {
            repeat(2) { verifyPersistentHomeRestart(fixture) }
        } finally {
            fixture.gpg.gpgconf("--remove-socketdir")
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun verifyPersistentHomeRestart(fixture: PersistentHomeFixture) {
        with(fixture) {
            Files.createDirectory(runtime).also(::restrict)
            Files.createDirectories(legacyHome)
            val legacyConfig = Files.writeString(legacyHome.resolve("common.conf"), "# legacy\n")
            val socketDirectory = requireNotNull(Path.of(gpgAgentSocketForClientHome(home, gpg)).parent)
            var externalSocketDirectory = false
            val defaultLauncher = KeyguardAgentLauncher(
                binaryPath = binaryPath,
                processor = TestGpgAgentRequestProcessor(listOf(generatedModern)),
                authToken = ByteArray(32).also { SecureRandom().nextBytes(it) },
            )
            try {
                defaultLauncher.start(
                    ipcEndpoint = AgentIpcEndpoint.UnixSocket(caseRoot.resolve("ipc.sock"), caseRoot),
                    environmentOverrides = environment,
                )
                externalSocketDirectory = !socketDirectory.toRealPath().startsWith(home.toRealPath())
                val signed = gpg.run(
                    "--no-autostart", "--batch", "--yes", "--armor",
                    "--local-user", generatedModern.primaryFingerprint, "--clearsign",
                    stdin = "Default path signing test\n".encodeToByteArray(),
                )
                assertTrue(signed.isSuccess, signed.toString())
                val verified = gpg.run("--no-autostart", "--verify", stdin = signed.stdout.encodeToByteArray())
                assertTrue(verified.isSuccess, verified.toString())
                assertEquals("# persistent\nno-autostart\n", Files.readString(commonConfig))
                assertEquals("# untouched\n", Files.readString(defaultConfig))
                assertEquals("# legacy\n", Files.readString(legacyConfig))
                assertEquals(sharedPermissions, Files.getPosixFilePermissions(userHome))
            } finally {
                defaultLauncher.stop()
            }
            val removed = gpg.gpgconf("--remove-socketdir")
            assertTrue(removed.isSuccess, removed.toString())
            if (externalSocketDirectory) {
                assertTrue(Files.notExists(socketDirectory), "The next startup must recreate $socketDirectory")
            }
            runtime.deleteRecursively()
        }
    }

    private data class PersistentHomeFixture(
        val caseRoot: Path,
        val userHome: Path,
        val home: Path,
        val runtime: Path,
        val legacyHome: Path,
        val commonConfig: Path,
        val defaultConfig: Path,
        val sharedPermissions: Set<PosixFilePermission>,
        val environment: Map<String, String?>,
        val gpg: GpgCli,
    )

    // ---- SIGN tests ------------------------------------------------------------------

    @Test
    fun `rsa sign and verify`() = signAndVerify(keys.rsa, "rsa")

    @Test
    fun `ed25519 sign and verify`() = signAndVerify(keys.ed25519, "ed25519")

    @Test
    fun `nistp256 sign and verify`() = signAndVerify(keys.nistp256, "nistp256")

    // ---- DECRYPT tests ---------------------------------------------------------------

    @Test
    fun `rsa encrypt and decrypt`() = encryptAndDecrypt(keys.rsa, "rsa")

    @Test
    fun `ed25519 cv25519 encrypt and decrypt`() = encryptAndDecrypt(keys.ed25519, "cv25519")

    @Test
    fun `nistp256 ecdh encrypt and decrypt`() = encryptAndDecrypt(keys.nistp256, "nistp256")

    // ---- Keyguard-GENERATED key tests ------------------------------------------------

    @Test
    fun `generated modern sign and verify`() = signAndVerify(generatedModern, "gen-modern")

    @Test
    fun `generated rsa 3072 sign and verify`() =
        signAndVerify(generatedRsaKey(GpgKeyConfig.RsaLength.B3072), "gen-rsa-3072")

    @Test
    fun `generated rsa 4096 sign and verify`() =
        signAndVerify(generatedRsaKey(GpgKeyConfig.RsaLength.B4096), "gen-rsa-4096")

    @Test
    fun `generated modern encrypt and decrypt`() = encryptAndDecrypt(generatedModern, "gen-modern")

    @Test
    fun `generated rsa 3072 encrypt and decrypt`() =
        encryptAndDecrypt(generatedRsaKey(GpgKeyConfig.RsaLength.B3072), "gen-rsa-3072")

    @Test
    fun `generated rsa 4096 encrypt and decrypt`() =
        encryptAndDecrypt(generatedRsaKey(GpgKeyConfig.RsaLength.B4096), "gen-rsa-4096")

    @Test
    fun `all keyguard generated variants are covered`() {
        val expectedLabels = buildSet {
            add("gen-modern")
            GpgKeyConfig.RsaLength.entries.forEach { length ->
                add("gen-rsa-${length.size}")
            }
        }
        assertEquals(expectedLabels, generatedKeys.map { it.label }.toSet())
    }

    // ---- Raw Assuan failure-path tests ----------------------------------------------

    @Test
    fun `assuan keyinfo lists served keys`() {
        val transcript = assuanTranscript(
            "KEYINFO --list\n",
            "BYE\n",
        )

        assertTrue(
            transcript.any { it.startsWith("S KEYINFO ") },
            transcript.joinToString("\n"),
        )
        assertAtLeastOkCount(transcript, 1)
    }

    @Test
    fun `assuan command framing failures match gpg agent`() {
        val transcript = assuanTranscript(
            "FOOBAR\n",
            " FOO\n",
            "# ignored comment\n",
            "\n",
            "BYE\n",
        )

        assertErrorCodes(
            transcript,
            GpgErrorCode.AssUnknownCommand,
            GpgErrorCode.AssSyntax,
        )
        assertTrue(transcript.any { it == "OK closing connection" }, transcript.joinToString("\n"))
    }

    @Test
    fun `assuan overlong top-level command closes without error line`() {
        val transcript = assuanTranscript("A".repeat(1100) + "\n")

        assertEquals(1, transcript.size, transcript.joinToString("\n"))
        assertTrue(transcript.single().startsWith("OK "), transcript.joinToString("\n"))
    }

    @Test
    fun `assuan keygrip command failures match gpg agent`() {
        val transcript = assuanTranscript(
            "HAVEKEY\n",
            "HAVEKEY abcd\n",
            "HAVEKEY $MISSING_KEYGRIP\n",
            "HAVEKEY --list=0\n",
            "HAVEKEY --list=1\n",
            "KEYINFO\n",
            "KEYINFO abcd\n",
            "KEYINFO $MISSING_KEYGRIP\n",
            "SIGKEY\n",
            "SIGKEY abcd\n",
            "SIGKEY $MISSING_KEYGRIP\n",
            "SETKEY\n",
            "SETKEY abcd\n",
            "SETKEY $MISSING_KEYGRIP\n",
            "BYE\n",
        )

        assertErrorCodes(
            transcript,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
            GpgErrorCode.NoSecretKey,
            GpgErrorCode.AssParameter,
            GpgErrorCode.Truncated,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
            GpgErrorCode.NotFound,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
        )
        assertAtLeastOkCount(transcript, 3)
    }

    @Test
    fun `assuan signing setup failures match gpg agent`() {
        val transcript = assuanTranscript(
            "PKSIGN\n",
            "SETHASH\n",
            "SETHASH 9999 $VALID_SHA256_HEX\n",
            "SETHASH --hash=bogus $VALID_SHA256_HEX\n",
            "SETHASH 8 AABB\n",
            "SETHASH 8 $VALID_SHA256_HEX\n",
            "SIGKEY $MISSING_KEYGRIP\n",
            "PKSIGN\n",
            "RESET\n",
            "SIGKEY ${firstSigningKeygrip()}\n",
            "PKSIGN\n",
            "RESET\n",
            "SIGKEY ${rsaSigningKeygrip()}\n",
            "SETHASH --pss 8 $VALID_SHA256_HEX\n",
            "PKSIGN\n",
            "BYE\n",
        )

        assertErrorCodes(
            transcript,
            GpgErrorCode.NoSecretKey,
            GpgErrorCode.UnsupportedAlgorithm,
            GpgErrorCode.UnsupportedAlgorithm,
            GpgErrorCode.AssParameter,
            GpgErrorCode.AssParameter,
            GpgErrorCode.NoSecretKey,
            GpgErrorCode.InvalidValue,
            GpgErrorCode.NotSupported,
        )
        assertAtLeastOkCount(transcript, 7)
    }

    @Test
    fun `assuan pkdecrypt inquiry failures match gpg agent`() {
        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT\n", "CAN\n", "BYE\n"),
            expected = GpgErrorCode.AssCanceled,
        )
        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT\n", "FOO\n", "BYE\n"),
            expected = GpgErrorCode.AssUnexpectedCommand,
        )
        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT\n", "END\n", "BYE\n"),
            expected = GpgErrorCode.NoSecretKey,
        )
        assertPkdecryptFailure(
            commands = listOf("SETKEY $MISSING_KEYGRIP\n", "PKDECRYPT\n", "END\n", "BYE\n"),
            expected = GpgErrorCode.NoSecretKey,
        )
        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT --kem=PGP\n", "CAN\n", "BYE\n"),
            expected = GpgErrorCode.AssCanceled,
        )
        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT --kem=BAD\n", "BYE\n"),
            expected = GpgErrorCode.AssParameter,
            expectInquiry = false,
        )
    }

    @Test
    fun `assuan pkdecrypt ciphertext size failures match gpg agent`() {
        val tooMuchData = buildList {
            add("PKDECRYPT\n")
            repeat(5) {
                add("D ${"A".repeat(900)}\n")
            }
            add("END\n")
            add("BYE\n")
        }
        assertPkdecryptFailure(
            commands = tooMuchData,
            expected = GpgErrorCode.AssTooMuchData,
        )

        assertPkdecryptFailure(
            commands = listOf("PKDECRYPT\n", "D ${"A".repeat(1100)}\n", "BYE\n"),
            expected = GpgErrorCode.AssLineTooLong,
        )
    }

    private fun generatedRsaKey(length: GpgKeyConfig.RsaLength): TestGpgKey =
        checkNotNull(generatedRsaKeys[length]) {
            "Missing generated RSA ${length.size} test key"
        }

    private fun firstSigningKeygrip(): String =
        (keys.all + generatedKeys.map { it.key })
            .flatMap { it.metadataKeys }
            .first { it.canSign }
            .keygrip
            .trim()
            .uppercase()

    private fun rsaSigningKeygrip(): String =
        keys.rsa.metadataKeys
            .first { it.canSign }
            .keygrip
            .trim()
            .uppercase()

    private fun signAndVerify(key: TestGpgKey, algo: String) {
        val message = "keyguard gpg e2e $algo"
        // Clearsign: this drives gpg -> Rust Assuan PKSIGN -> IPC -> Kotlin crypto.
        val signResult = clientGpg.run(
            "--no-autostart", "--batch", "--yes", "--status-fd", "2",
            "-u", key.primaryFingerprint, "--clearsign",
            stdin = message.encodeToByteArray(),
        )
        assertEquals(
            0, signResult.exitCode,
            "clearsign for $algo failed (exit ${signResult.exitCode}):\n${signResult.stderr}",
        )
        val signed = signResult.stdout
        assertTrue(
            signed.contains("BEGIN PGP SIGNED MESSAGE"),
            "clearsign for $algo produced no signed message:\n$signed\n${signResult.stderr}",
        )

        // Verify: pure public-key, but proves the signature the agent produced is valid.
        val verifyResult = clientGpg.run(
            "--no-autostart", "--status-fd", "2", "--verify",
            stdin = signed.encodeToByteArray(),
        )
        assertEquals(
            0, verifyResult.exitCode,
            "verify for $algo failed (exit ${verifyResult.exitCode}):\n${verifyResult.stderr}",
        )
        assertTrue(
            verifyResult.stderr.contains("GOODSIG") || verifyResult.stderr.contains("VALIDSIG"),
            "verify for $algo did not report a good signature:\n${verifyResult.stderr}",
        )
    }

    private fun encryptAndDecrypt(key: TestGpgKey, algo: String) {
        val secret = "keyguard-secret-$algo-${randomHex(4)}"
        // Encrypt: pure public-key, no agent needed.
        val encryptResult = clientGpg.run(
            "--no-autostart", "--batch", "--yes", "--trust-model", "always",
            "-r", key.primaryFingerprint, "--encrypt", "--armor",
            stdin = secret.encodeToByteArray(),
        )
        assertEquals(
            0, encryptResult.exitCode,
            "encrypt for $algo failed (exit ${encryptResult.exitCode}):\n${encryptResult.stderr}",
        )
        val ciphertext = encryptResult.stdout
        assertTrue(
            ciphertext.contains("BEGIN PGP MESSAGE"),
            "encrypt for $algo produced no message:\n${encryptResult.stderr}",
        )

        // Decrypt: drives gpg -> Rust Assuan SETKEY/PKDECRYPT -> IPC -> Kotlin crypto.
        // A passing round-trip PROVES the agent's value encoding is gpg-compatible.
        val decryptResult = clientGpg.run(
            "--no-autostart", "--batch", "--yes", "--status-fd", "2", "--decrypt",
            stdin = ciphertext.encodeToByteArray(),
        )
        assertEquals(
            0, decryptResult.exitCode,
            "decrypt for $algo failed (exit ${decryptResult.exitCode}):\n${decryptResult.stderr}",
        )
        assertEquals(
            secret, decryptResult.stdout,
            "decrypt for $algo recovered the wrong plaintext:\n${decryptResult.stderr}",
        )
    }

    private fun assertPkdecryptFailure(
        commands: List<String>,
        expected: GpgErrorCode,
        expectInquiry: Boolean = true,
    ) {
        val transcript = assuanTranscript(*commands.toTypedArray())
        if (expectInquiry) {
            assertTrue(
                transcript.contains("S INQUIRE_MAXLEN 4096"),
                transcript.joinToString("\n"),
            )
            assertTrue(
                transcript.contains("INQUIRE CIPHERTEXT"),
                transcript.joinToString("\n"),
            )
        }
        assertTrue(
            expected.code in errorCodes(transcript),
            "Expected ${expected.code} in transcript:\n${transcript.joinToString("\n")}",
        )
    }

    private fun assuanTranscript(
        vararg commands: String,
    ): List<String> {
        return launcher.assuanTranscript(
            gpgSocket = gpgAgentSocket,
            commands = commands.toList(),
        )
    }

    private fun assertErrorCodes(
        transcript: List<String>,
        vararg expected: GpgErrorCode,
    ) {
        assertEquals(
            expected.map { it.code },
            errorCodes(transcript),
            transcript.joinToString("\n"),
        )
    }

    private fun assertAtLeastOkCount(
        transcript: List<String>,
        minCount: Int,
    ) {
        val count = transcript.count { it == "OK" || it.startsWith("OK ") }
        assertTrue(count >= minCount, transcript.joinToString("\n"))
    }

    private fun errorCodes(
        transcript: List<String>,
    ): List<Int> = transcript
        .filter { it.startsWith("ERR ") }
        .map { line ->
            val encoded = line
                .removePrefix("ERR ")
                .substringBefore(' ')
                .toLong()
            // Real gpg-agent includes the error source in the upper bits
            // (for example 67109144); this minimal agent emits source-less
            // values. GnuPG clients compare the low libgpg-error code.
            (encoded and 0xffff).toInt()
        }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
