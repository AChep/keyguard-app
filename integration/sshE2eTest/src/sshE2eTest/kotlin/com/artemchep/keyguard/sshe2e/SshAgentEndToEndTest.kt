package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.service.sshagent.sshPublicKeysMatch
import com.artemchep.keyguard.crypto.NativeKeyPairGenerator
import com.artemchep.keyguard.integration.process.freePort
import com.artemchep.keyguard.util.agentipc.AgentIpcLocation
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Assume.assumeTrue

class SshAgentEndToEndTest {
    companion object {
        private lateinit var workRoot: Path
        private lateinit var sshSocket: String
        private lateinit var launcher: KeyguardSshAgentLauncher
        private lateinit var keys: List<TestSshKey>
        private lateinit var ssh: SshCli
        private lateinit var binaryPath: Path

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

            binaryPath = buildAndLocateBinary(repoRoot)
            keys = generateKeys()

            val authToken = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val processor = TestSshAgentRequestProcessor(keys = keys)
            launcher = KeyguardSshAgentLauncher(
                binaryPath = binaryPath,
                processor = processor,
                authToken = authToken,
            )
            val ipcLocation = if (isWindows()) {
                AgentIpcLocation.WindowsPipe(prefix = "", exactName = "kg-sshe2e-$token-ipc")
            } else {
                AgentIpcLocation.UnixDirectory(parent = workRoot.toString(), exactName = "ipc")
            }
            sshSocket = if (isWindows()) {
                "\\\\.\\pipe\\kg-sshe2e-$token-agent"
            } else {
                workRoot.resolve("a.sock")
                    .toAbsolutePath()
                    .toString()
            }
            launcher.start(ipcLocation = ipcLocation, sshSocket = sshSocket)
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
            val targetDir = repoRoot.resolve("target")
            val build = ProcessBuilder(
                "cargo", "build", "--release",
                "--target-dir", targetDir.toString(),
            )
                .directory(agentDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            require(build.waitFor() == 0) {
                "cargo build --release failed for $agentDir"
            }
            val binary = targetDir
                .resolve("release")
                .resolve(executableName("keyguard-ssh-agent"))
            require(Files.isExecutable(binary)) {
                "keyguard-ssh-agent binary not found / not executable at $binary"
            }
            return binary
        }

        private fun generateKeys(): List<TestSshKey> {
            val generator = NativeKeyPairGenerator(Base64ServiceImpl())
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
    fun `macos default socket serves keys and preserves the shared GPG home across restarts`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
        val userHome = Files.createDirectory(workRoot.resolve("home space"))
        val sharedPermissions = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(userHome, sharedPermissions)
        val keyguard = userHome.resolve(".keyguard")
        val socket = keyguard.resolve("ssh-agent.sock")
        val gpgConfig = keyguard.resolve("gnupg/common.conf")
        val legacy = Files.createDirectories(userHome.resolve("Library/Group Containers/com.artemchep.keyguard"))
        val legacyConfig = Files.writeString(legacy.resolve("sentinel"), "unchanged")
        repeat(2) { attempt ->
            val defaultLauncher = KeyguardSshAgentLauncher(
                binaryPath = binaryPath,
                processor = TestSshAgentRequestProcessor(keys),
                authToken = ByteArray(32).also { SecureRandom().nextBytes(it) },
            )
            try {
                defaultLauncher.start(
                    ipcLocation = AgentIpcLocation.UnixDirectory(
                        parent = workRoot.toString(),
                        exactName = "default-ipc",
                    ),
                    environmentOverrides = mapOf("HOME" to userHome.toString()),
                )
                val result = SshCli(socket.toString()).run("ssh-add", "-L")
                assertTrue(result.isSuccess, result.describe())
                keys.forEach { key ->
                    val containsKey = result.stdout
                        .lineSequence()
                        .any { sshPublicKeysMatch(it, key.publicKey) }
                    assertTrue(containsKey, result.describe())
                }
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(keyguard))
                assertEquals(sharedPermissions, Files.getPosixFilePermissions(userHome))
                assertEquals("unchanged", Files.readString(legacyConfig))
                if (attempt == 0) {
                    Files.createDirectory(gpgConfig.parent)
                    Files.writeString(gpgConfig, "no-autostart\n")
                } else {
                    assertEquals("no-autostart\n", Files.readString(gpgConfig))
                }
            } finally {
                defaultLauncher.stop()
            }
            assertTrue(Files.notExists(socket), "The default socket must be cleaned up on shutdown")
        }
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
    fun `a certificate is listed first and signs with real ssh-keygen`() {
        val dir = Files.createTempDirectory(workRoot, "certificate-")
        val principal = "certificate@keyguard.test"
        val issued = issueCertificate(dir, principal)
        val allowedSignersFile = dir.resolve("allowed_signers")
        val signatureFile = dir.resolve("message.sig")
        val namespace = "keyguard-e2e-certificate"
        val message = "Hello from Keyguard SSH agent E2E: certificate\n".encodeToByteArray()

        withCertifiedAgent(dir, issued) { cli ->
            val listResult = cli.run("ssh-add", "-L")
            assertTrue(listResult.isSuccess, listResult.describe())
            val listed = listResult.stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            assertEquals(keys.size + 1, listed.size, listResult.describe())
            assertTrue(sshPublicKeysMatch(listed.first(), issued.certificate), listResult.describe())

            // ssh-keygen asks the agent to sign as the certificate, and
            // the signature carries it to the verifier.
            val signResult = cli.run(
                "ssh-keygen", "-Y", "sign", "-f", issued.certificateFile.toString(), "-n", namespace,
                stdin = message,
            )
            assertTrue(signResult.isSuccess, signResult.describe())
            Files.writeString(signatureFile, signResult.stdout)

            Files.writeString(allowedSignersFile, "$principal cert-authority ${issued.caPublicKey}\n")
            val verifyResult = cli.run(
                "ssh-keygen", "-Y", "verify", "-f", allowedSignersFile.toString(),
                "-I", principal, "-n", namespace, "-s", signatureFile.toString(),
                stdin = message,
            )
            assertTrue(verifyResult.isSuccess, verifyResult.describe())
        }
    }

    @Test
    fun `a certificate signs in to a real sshd that trusts only its authority`() {
        // A throwaway sshd runs as the current user, so it can only sign in that user.
        val sshd = Path.of("/usr/sbin/sshd")
        assumeTrue(!isWindows() && Files.isExecutable(sshd))
        val user = System.getProperty("user.name")
        val dir = Files.createTempDirectory(workRoot, "sshd-")
        val issued = issueCertificate(dir, principal = user)
        val hostKeyFile = dir.resolve("host")
        val hostKeyResult = ssh.run(
            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", hostKeyFile.toString(),
        )
        assertTrue(hostKeyResult.isSuccess, hostKeyResult.describe())
        val port = freePort()
        val config = dir.resolve("sshd_config")
        Files.writeString(
            config,
            """
            ListenAddress 127.0.0.1
            Port $port
            HostKey $hostKeyFile
            PidFile ${dir.resolve("sshd.pid")}
            AuthorizedKeysFile none
            TrustedUserCAKeys ${dir.resolve("ca.pub")}
            PubkeyAuthentication yes
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            UsePAM no
            StrictModes no
            """.trimIndent() + "\n",
        )
        val server = ProcessBuilder(sshd.toString(), "-D", "-e", "-f", config.toString())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("sshd.log").toFile())
            .start()
        try {
            awaitPort(port)
            withCertifiedAgent(dir, issued) { cli ->
                val result = cli.run(
                    "ssh", "-F", "none", "-p", port.toString(),
                    "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null",
                    "-o", "IdentityAgent=${cli.socket}",
                    "$user@127.0.0.1", "echo", "keyguard-certificate-ok",
                )
                val log = Files.readString(dir.resolve("sshd.log"))
                assertTrue(result.isSuccess, result.describe() + "\nsshd:\n$log")
                assertTrue(result.stdout.contains("keyguard-certificate-ok"), result.describe())
                assertTrue(log.contains("ED25519-CERT"), "sshd did not accept a certificate:\n$log")
            }
        } finally {
            server.destroy()
            server.waitFor()
        }
    }

    private class IssuedCertificate(
        val key: TestSshKey,
        val certificate: String,
        val certificateFile: Path,
        val caPublicKey: String,
    )

    /** Issues a certificate for the Ed25519 key with OpenSSH itself, valid around now. */
    private fun issueCertificate(
        dir: Path,
        principal: String,
    ): IssuedCertificate {
        val key = keys.single { it.keyType == "ssh-ed25519" }
        val caFile = dir.resolve("ca")
        val publicKeyFile = dir.resolve("key.pub")
        Files.writeString(publicKeyFile, key.publicKey + "\n")
        val caResult = ssh.run(
            "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "Keyguard E2E CA",
            "-f", caFile.toString(),
        )
        assertTrue(caResult.isSuccess, caResult.describe())
        val issueResult = ssh.run(
            "ssh-keygen", "-q", "-s", caFile.toString(), "-I", "keyguard-e2e",
            "-n", principal, "-V", "-5m:+1h", publicKeyFile.toString(),
        )
        assertTrue(issueResult.isSuccess, issueResult.describe())
        val certificateFile = dir.resolve("key-cert.pub")
        return IssuedCertificate(
            key = key,
            certificate = Files.readString(certificateFile).trim(),
            certificateFile = certificateFile,
            caPublicKey = Files.readString(dir.resolve("ca.pub")).trim(),
        )
    }

    /** Runs an agent that holds the [issued] certificate next to the generated keys. */
    private fun withCertifiedAgent(
        dir: Path,
        issued: IssuedCertificate,
        block: (SshCli) -> Unit,
    ) {
        val token = randomHex(4)
        val launcher = KeyguardSshAgentLauncher(
            binaryPath = binaryPath,
            processor = TestSshAgentRequestProcessor(
                keys = keys.map { if (it == issued.key) it.copy(certificate = issued.certificate) else it },
            ),
            authToken = ByteArray(32).also { SecureRandom().nextBytes(it) },
        )
        val socket = if (isWindows()) {
            "\\\\.\\pipe\\kg-sshe2e-$token-certificate"
        } else {
            dir.resolve("c.sock").toAbsolutePath().toString()
        }
        try {
            launcher.start(
                ipcLocation = if (isWindows()) {
                    AgentIpcLocation.WindowsPipe(prefix = "", exactName = "kg-sshe2e-$token-certificate-ipc")
                } else {
                    AgentIpcLocation.UnixDirectory(parent = dir.toString(), exactName = "ipc")
                },
                sshSocket = socket,
            )
            block(SshCli(socket))
        } finally {
            launcher.stop()
        }
    }

    private fun awaitPort(port: Int) {
        repeat(100) {
            val listening = runCatching {
                Socket("127.0.0.1", port).close()
            }.isSuccess
            if (listening) return
            Thread.sleep(50)
        }
        error("sshd did not listen on port $port")
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
