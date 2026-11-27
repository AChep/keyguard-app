package com.artemchep.keyguard.feature.secretscan

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.secretscan.PlaintextSecretScanner
import com.artemchep.keyguard.common.service.secretscan.SecretScanEvent
import com.artemchep.keyguard.common.service.secretscan.SecretScanItem
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.common.service.secretscan.secretScanLiteralDigest
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.feature.environments.EnvironmentDotenv
import com.artemchep.keyguard.feature.environments.coerceEnvironmentName
import com.artemchep.keyguard.feature.environments.isValidEnvironmentName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SecretScanUtilsTest {
    @Test
    fun `an ssh key is named after its file`() {
        assertEquals("id_ed25519", secretScanFileName("/home/me/.ssh/id_ed25519"))
        assertEquals("id_rsa", secretScanFileName("C:\\Users\\me\\.ssh\\id_rsa"))
    }

    @Test
    fun `an env file is named after its folder`() {
        assertEquals("my-api", secretScanDotEnvName("/home/me/my-api/.env"))
        assertEquals("my-api (.env.production)", secretScanDotEnvName("/home/me/my-api/.env.production"))
        assertEquals("app", secretScanDotEnvName("C:\\work\\app\\.env"))
        assertEquals(".env", secretScanDotEnvName("/.env"))
    }

    @Test
    fun `an env file name obeys the environment name rule`() {
        val long = "a".repeat(1000)
        val name = secretScanDotEnvName("/p/$long/.env")
        assertEquals(coerceEnvironmentName(long), name)
        assertTrue(isValidEnvironmentName(name))
        assertEquals(".env", secretScanDotEnvName("/p/ /.env"))
    }

    @Test
    fun `the literal digest ignores order, unset values and comments`() {
        val crypto = CryptoGeneratorJvm()
        val a = EnvironmentDotenv.parse("A=1\nB=2\n").secretScanLiteralDigest(crypto)
        val b = EnvironmentDotenv.parse("# x\nB=2\nEMPTY=\nA=1\n").secretScanLiteralDigest(crypto)
        val c = EnvironmentDotenv.parse("A=1\nB=3\n").secretScanLiteralDigest(crypto)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNull(EnvironmentDotenv.parse("EMPTY=\n").secretScanLiteralDigest(crypto))
    }

    @Test
    fun `the plan holds the unchanged env files without a folder`() = runTest {
        val good = dotEnv(path = "/p/my-api/.env.local")
        val changed = dotEnv(path = "/p/other/.env")
        val scanner = FakeScanner(
            files = mapOf(good.path to "TOKEN=abc\n"),
        )
        val result = buildSecretScanImportPlan(
            items = listOf(good, changed),
            scanner = scanner,
            sshKeyImportService = NoSshKeyImportService,
            askPassphrase = { _, _ -> fail("No key to ask for") },
        )
        assertEquals(listOf<SecretScanItem>(good), result.items)
        assertEquals(1, result.changed)
        assertEquals(0, result.skipped)
        val item = result.plan.items.single()
        assertEquals("my-api (.env.local)", item.request.title)
        assertEquals(DSecret.Type.Environment, item.request.type)
        assertEquals(null, item.folderKey)
        assertTrue(result.plan.folders.isEmpty())
    }

    @Test
    fun `a wrong passphrase asks again`() = runTest {
        val key = SecretScanItem.SshKey(
            path = "/h/.ssh/id_rsa",
            displayPath = "~/.ssh/id_rsa",
            sha256 = "a",
            encrypted = true,
            fingerprint = null,
        )
        val wrongs = mutableListOf<Boolean>()
        val result = buildSecretScanImportPlan(
            items = listOf(key),
            scanner = FakeScanner(mapOf(key.path to "-----BEGIN OPENSSH PRIVATE KEY-----")),
            sshKeyImportService = NoSshKeyImportService,
            askPassphrase = { _, wrong ->
                wrongs += wrong
                "guess".takeIf { wrongs.size < 3 }
            },
        )
        assertEquals(listOf(false, true, true), wrongs)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a skipped passphrase skips the key`() = runTest {
        val key = SecretScanItem.SshKey(
            path = "/h/.ssh/id_rsa",
            displayPath = "~/.ssh/id_rsa",
            sha256 = "a",
            encrypted = true,
            fingerprint = null,
        )
        var asked = 0
        val result = buildSecretScanImportPlan(
            items = listOf(key),
            scanner = FakeScanner(mapOf(key.path to "-----BEGIN OPENSSH PRIVATE KEY-----")),
            sshKeyImportService = NoSshKeyImportService,
            askPassphrase = { _, _ ->
                asked += 1
                null
            },
        )
        assertEquals(1, asked)
        assertEquals(1, result.skipped)
        assertTrue(result.plan.items.isEmpty())
    }
}

private class FakeScanner(
    private val files: Map<String, String>,
) : PlaintextSecretScanner {
    override val defaultRootsAvailable: Boolean = true
    override val trashSupported: Boolean = true

    override fun scan(
        kind: SecretScanKind,
        extraRoots: List<String>,
    ): Flow<SecretScanEvent> = emptyFlow()

    override suspend fun read(item: SecretScanItem): ByteArray? = files[item.path]?.encodeToByteArray()

    override suspend fun remove(items: List<SecretScanItem>): Map<String, Boolean> = emptyMap()
}

private fun dotEnv(path: String) = SecretScanItem.DotEnv(
    path = path,
    displayPath = path,
    sha256 = "a",
    variableCount = 1,
    literalDigest = "d",
)

private object NoSshKeyImportService : SshKeyImportService {
    override fun import(request: SshKeyImportRequest): SshKeyImportResult =
        SshKeyImportResult.NeedsPassphrase(formatLabel = "OpenSSH")
}
