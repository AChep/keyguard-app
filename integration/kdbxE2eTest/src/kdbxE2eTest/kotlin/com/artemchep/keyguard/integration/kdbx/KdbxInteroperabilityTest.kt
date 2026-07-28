package com.artemchep.keyguard.integration.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class KdbxInteroperabilityTest {
    @Test
    fun kdbx3AesAesKdfRoundTripsWithPykeepass() = runCase(
        TestCase("kdbx3-aes-aeskdf", "ver3_aes.kdbx"),
    )

    @Test
    fun kdbx4AesAesKdfRoundTripsWithPykeepass() = runCase(
        TestCase("kdbx4-aes-aeskdf", "ver4_aes.kdbx"),
    )

    @Test
    fun kdbx4AesArgon2dRoundTripsWithPykeepass() = runCase(
        TestCase("kdbx4-aes-argon2d", "ver4_argon2.kdbx"),
    )

    @Test
    fun kdbx4TwofishArgon2dRoundTripsWithPykeepass() = runCase(
        TestCase("kdbx4-twofish-argon2d", "ver4_twofish.kdbx"),
    )

    @Test
    fun passwordAndKeyfileRoundTripWithPykeepass() = runCase(
        TestCase("kdbx4-aes-argon2d-composite", "ver4_argon2.kdbx", useKeyfile = true),
    )

    private fun runCase(testCase: TestCase) {
        val caseDirectory = artifactsDirectory.resolve(testCase.id)
        Files.createDirectories(caseDirectory)
        val sourceDatabase = caseDirectory.resolve("python-source.kdbx")
        val manifest = caseDirectory.resolve("python-manifest.json")
        val keyfile = if (testCase.useKeyfile) caseDirectory.resolve("composite-key.bin") else null
        val kotlinDatabase = caseDirectory.resolve("kotlin-roundtrip.kdbx")
        val oracle = PythonKdbxOracle(python, driver, repoRoot)

        try {
            oracle.generate(
                seed = seedDirectory.resolve(testCase.seedFile),
                database = sourceDatabase,
                manifest = manifest,
                password = Password,
                keyfile = keyfile,
            )
            val expected = Json.parseToJsonElement(Files.readString(manifest))
            val credentials = credentials(keyfile)
            val decoded = KeePassDatabase.decode(
                data = Files.readAllBytes(sourceDatabase),
                credentials = credentials,
                cipherProviders = CipherProviders,
            )
            assertManifestEquals(
                label = "Python source decoded by Kotlin",
                expected = expected,
                actual = decoded.toCanonicalManifest(),
                output = caseDirectory.resolve("kotlin-decoded-manifest.json"),
            )

            val sink = Buffer()
            decoded.encodeTo(sink, cipherProviders = CipherProviders)
            Files.write(kotlinDatabase, sink.readByteArray())

            val roundTrip = KeePassDatabase.decode(
                data = Files.readAllBytes(kotlinDatabase),
                credentials = credentials,
                cipherProviders = CipherProviders,
            )
            assertManifestEquals(
                label = "Kotlin encoded database decoded by Kotlin",
                expected = expected,
                actual = roundTrip.toCanonicalManifest(),
                output = caseDirectory.resolve("kotlin-roundtrip-manifest.json"),
            )

            oracle.verify(
                database = kotlinDatabase,
                manifest = manifest,
                password = Password,
                keyfile = keyfile,
            )
        } catch (error: Throwable) {
            throw AssertionError(
                "KDBX interoperability case '${testCase.id}' failed. " +
                    "Artifacts were retained at $caseDirectory",
                error,
            )
        }
    }

    private fun credentials(keyfile: Path?): Credentials {
        val password = EncryptedValue.fromString(Password)
        return if (keyfile == null) {
            Credentials.from(password)
        } else {
            Credentials.from(password, Files.readAllBytes(keyfile))
        }
    }

    private fun assertManifestEquals(
        label: String,
        expected: JsonElement,
        actual: JsonElement,
        output: Path,
    ) {
        Files.writeString(output, PrettyJson.encodeToString(JsonElement.serializer(), actual) + "\n")
        assertEquals(
            expected = expected,
            actual = actual,
            message = "$label manifest mismatch. Actual manifest: $output",
        )
    }

    private data class TestCase(
        val id: String,
        val seedFile: String,
        val useKeyfile: Boolean = false,
    )

    private companion object {
        const val Password = "test-password"

        val CipherProviders = BaseCiphers.entries + TwofishCipher
        val PrettyJson = Json { prettyPrint = true }
        val repoRoot: Path = requiredPathProperty("keyguard.repoRoot")
        val driver: Path = requiredPathProperty("keyguard.kdbxE2e.driver")
        val seedDirectory: Path = requiredPathProperty("keyguard.kdbxE2e.seedDir")
        val artifactsDirectory: Path = requiredPathProperty("keyguard.kdbxE2e.artifactsDir")
        val python: String = System.getProperty("keyguard.kdbxE2e.python")
            ?.takeIf(String::isNotBlank)
            ?: error("Missing system property 'keyguard.kdbxE2e.python'")

        fun requiredPathProperty(name: String): Path = System.getProperty(name)
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: error("Missing system property '$name'")
    }
}
