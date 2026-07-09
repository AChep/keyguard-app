package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.sshagent.sshPublicKeysMatch
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.crypto.KeyPairGeneratorJvm
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass

class SshAgentEndToEndTest {
    companion object {
        private lateinit var workRoot: Path
        private lateinit var sshSocket: String
        private lateinit var launcher: KeyguardSshAgentLauncher
        private lateinit var keys: List<TestSshKey>
        private lateinit var ssh: SshCli

        @JvmStatic
        @BeforeClass
        fun setup() {
            val repoRoot = Path.of(requireNotNull(System.getProperty("keyguard.repoRoot")) {
                "Missing system property keyguard.repoRoot"
            })

            val token = randomHex(4)
            workRoot = if (isWindows()) {
                Files.createTempDirectory("kg-sshe2e-$token-")
            } else {
                Path.of("/tmp", "kg-sshe2e-$token")
            }
            Files.createDirectories(workRoot)
            restrict(workRoot)

            val binaryPath = buildAndLocateBinary(repoRoot)
            keys = generateKeys()

            val authToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val processor = TestSshAgentRequestProcessor(keys = keys)
            launcher = KeyguardSshAgentLauncher(
                binaryPath = binaryPath,
                processor = processor,
                authToken = authToken,
            )
            val ipcEndpoint = if (isWindows()) {
                AgentIpcEndpoint.WindowsPipe("\\\\.\\pipe\\kg-sshe2e-$token-ipc")
            } else {
                val ipcSocketPath = workRoot.resolve("ipc.sock")
                AgentIpcEndpoint.UnixSocket(
                    socketPath = ipcSocketPath,
                    directory = workRoot,
                )
            }
            sshSocket = if (isWindows()) {
                "\\\\.\\pipe\\kg-sshe2e-$token-agent"
            } else {
                workRoot.resolve("a.sock")
                    .toAbsolutePath()
                    .toString()
            }
            launcher.start(ipcEndpoint = ipcEndpoint, sshSocket = sshSocket)
            ssh = SshCli(sshSocket)
        }

        @JvmStatic
        @AfterClass
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        fun teardown() {
            runCatching { if (::launcher.isInitialized) launcher.stop() }
            runCatching { if (::workRoot.isInitialized) workRoot.deleteRecursively() }
        }

        private fun buildAndLocateBinary(repoRoot: Path): Path {
            val agentDir = repoRoot.resolve("desktopSshAgent").resolve("src")
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
                .resolve(executableName("keyguard-ssh-agent"))
            require(Files.isExecutable(binary)) {
                "keyguard-ssh-agent binary not found / not executable at $binary"
            }
            return binary
        }

        private fun generateKeys(): List<TestSshKey> {
            val generator = KeyPairGeneratorJvm(CryptoGeneratorJvm())
            return listOf(
                generator.populate(generator.ed25519()).toTestKey(
                    name = "Keyguard E2E Ed25519",
                    principal = "ed25519@keyguard.test",
                ),
                generator.populate(generator.rsa(KeyPairGenerator.RsaLength.B2048)).toTestKey(
                    name = "Keyguard E2E RSA",
                    principal = "rsa@keyguard.test",
                ),
            )
        }

        private fun com.artemchep.keyguard.common.model.KeyPair.toTestKey(
            name: String,
            principal: String,
        ): TestSshKey = TestSshKey(
            name = name,
            principal = principal,
            privateKeyPem = privateKey.ssh,
            publicKey = publicKey.ssh,
            keyType = publicKey.ssh.substringBefore(' '),
            fingerprint = publicKey.fingerprint,
        )

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

        private fun executableName(name: String): String =
            if (isWindows()) "$name.exe" else name
    }

    @Test
    fun `ssh-add lists generated identities`() {
        val listResult = ssh.run("ssh-add", "-l")
        assertTrue(listResult.isSuccess, listResult.describe())
        keys.forEach { key ->
            assertTrue(
                listResult.stdout.contains(key.name),
                "Expected ssh-add -l output to contain ${key.name}:\n${listResult.describe()}",
            )
        }

        val publicKeysResult = ssh.run("ssh-add", "-L")
        assertTrue(publicKeysResult.isSuccess, publicKeysResult.describe())
        val publicKeys = publicKeysResult.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertEquals(keys.size, publicKeys.size, publicKeysResult.describe())
        keys.forEach { key ->
            assertTrue(
                publicKeys.any { sshPublicKeysMatch(it, key.publicKey) },
                "Expected ssh-add -L output to contain ${key.name}:\n${publicKeysResult.describe()}",
            )
        }
    }

    @Test
    fun `ed25519 signs and verifies with real ssh-keygen`() =
        signAndVerify(keyType = "ssh-ed25519", namespace = "keyguard-e2e-ed25519")

    @Test
    fun `rsa signs and verifies with real ssh-keygen`() =
        signAndVerify(keyType = "ssh-rsa", namespace = "keyguard-e2e-rsa")

    private fun signAndVerify(
        keyType: String,
        namespace: String,
    ) {
        val key = keys.single { it.keyType == keyType }
        val dir = Files.createTempDirectory(workRoot, "sign-")
        val publicKeyFile = dir.resolve("key.pub")
        val allowedSignersFile = dir.resolve("allowed_signers")
        val signatureFile = dir.resolve("message.sig")
        val message = "Hello from Keyguard SSH agent E2E: ${key.name}\n".encodeToByteArray()

        Files.writeString(publicKeyFile, key.publicKey + "\n")
        Files.writeString(allowedSignersFile, "${key.principal} ${key.publicKey}\n")

        val signResult = ssh.run(
            "ssh-keygen",
            "-Y",
            "sign",
            "-f",
            publicKeyFile.toString(),
            "-n",
            namespace,
            stdin = message,
        )
        assertTrue(signResult.isSuccess, signResult.describe())
        assertTrue(signResult.stdout.isNotBlank(), signResult.describe())
        Files.writeString(signatureFile, signResult.stdout)

        val verifyResult = ssh.run(
            "ssh-keygen",
            "-Y",
            "verify",
            "-f",
            allowedSignersFile.toString(),
            "-I",
            key.principal,
            "-n",
            namespace,
            "-s",
            signatureFile.toString(),
            stdin = message,
        )
        assertTrue(verifyResult.isSuccess, verifyResult.describe())
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
