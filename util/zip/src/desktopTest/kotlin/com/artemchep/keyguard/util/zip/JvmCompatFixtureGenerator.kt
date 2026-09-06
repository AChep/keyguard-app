package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test

/**
 * Writes the golden archives the Rust `jvm_compat` test reads. The archives
 * are checked in, so this only runs on demand:
 *
 * ```text
 * ./gradlew :util:zip:desktopTest --tests '*JvmCompatFixtureGenerator*' \
 *     -Pkeyguard.zip.writeFixtures=true
 * ```
 *
 * Regenerating changes the SHA-256 constants pinned in `jvm_compat.rs`;
 * update them in the same change.
 */
class JvmCompatFixtureGenerator {
    @Test
    fun writesTheRustCompatFixtures() = runTest {
        if (System.getProperty(WRITE_FIXTURES_PROPERTY) != "true") {
            println(
                "Skipped: pass -P$WRITE_FIXTURES_PROPERTY=true to rewrite the " +
                    "archives in $FIXTURE_DIR.",
            )
            return@runTest
        }

        val directory = fixtureDirectory()
        directory.createDirectories()
        directory.resolve("plain.zip").writeBytes(archive(password = null))
        directory.resolve("aes256.zip")
            .writeBytes(archive(password = CompatFixtures.PASSWORD))
    }

    private suspend fun archive(password: String?): ByteArray = archive(
        password = password,
        entries = CompatFixtures.NAMES.mapIndexed { index, name ->
            bytesEntry(name, CompatFixtures.CONTENTS[index])
        },
    )

    /** Gradle runs the test task with the module directory as its cwd. */
    private fun fixtureDirectory(): Path {
        val moduleDir = Paths.get("").toAbsolutePath().normalize()
        check(moduleDir.resolve("rust").isDirectory()) {
            "Expected the module directory as the working directory, got $moduleDir"
        }
        return moduleDir.resolve(FIXTURE_DIR).normalize()
    }
}

private const val WRITE_FIXTURES_PROPERTY = "keyguard.zip.writeFixtures"

private const val FIXTURE_DIR = "rust/crates/keyguard-zip-core/tests/fixtures/jvm"
