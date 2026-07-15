import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.File
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("keyguard.native-crypto-consumer")
}

val gpgE2eTest by sourceSets.creating {
    kotlin.srcDir("src/gpgE2eTest/kotlin")
    resources.srcDir("src/gpgE2eTest/resources")
}

dependencies {
    "gpgE2eTestImplementation"(project(":common"))
    "gpgE2eTestImplementation"(kotlin("test-junit"))
    "gpgE2eTestImplementation"(libs.kotlinx.coroutines.core)
    "gpgE2eTestImplementation"(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.register<Test>("gpgE2eTest") {
    group = "verification"
    description = "Drives a real gpg client against the real Keyguard GPG agent (Rust binary + " +
        "Kotlin IPC server), validating signing and decryption end to end."

    dependsOn(gpgE2eTest.classesTaskName)

    testClassesDirs = gpgE2eTest.output.classesDirs
    classpath = gpgE2eTest.runtimeClasspath
    useJUnit()

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    // The test builds (via cargo) and locates the keyguard-gpg-agent binary itself,
    // and writes throwaway GNUPGHOME dirs under /tmp/kg-gpge2e-<token> (not the build
    // directory) because Unix socket paths have a ~104-character limit.
    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    // Allow forwarding the verbose flag from the Gradle invocation to the test fork
    // (e.g. -Dkeyguard.gpgE2e.verbose=true) so the agent binary + processor log details.
    System.getProperty("keyguard.gpgE2e.verbose")?.let {
        systemProperty("keyguard.gpgE2e.verbose", it)
    }
    System.getProperty("keyguard.gpg.binDir")?.let {
        systemProperty("keyguard.gpg.binDir", it)
    }

    doFirst {
        fun gpgTool(tool: String): String {
            val binDir = System.getProperty("keyguard.gpg.binDir")
                ?: System.getenv("KEYGUARD_GPG_BIN_DIR")
            if (binDir == null) return tool

            val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "$tool.exe"
            } else {
                tool
            }
            return File(binDir, executableName).absolutePath
        }

        // Preflight: a real gpg toolchain and cargo must be available before
        // starting the expensive test fork.
        fun requireRuns(label: String, command: String, versionArg: String) {
            val process = try {
                ProcessBuilder(command, versionArg).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw GradleException(
                    "GPG E2E test requires '$label', but it could not be started at '$command'.",
                    e,
                )
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GradleException("Timed out while running '$command $versionArg'.")
            }
            val output = process.inputStream.readBytes().decodeToString().trim()
            if (process.exitValue() != 0) {
                throw GradleException(
                    "GPG E2E test requires '$label', but '$command $versionArg' exited " +
                        "with ${process.exitValue()}.\n$output",
                )
            }
        }
        requireRuns("gpg", gpgTool("gpg"), "--version")
        requireRuns("gpgconf", gpgTool("gpgconf"), "--version")
        requireRuns("cargo", "cargo", "--version")
    }

    testLogging {
        events =
            setOf(
                TestLogEvent.FAILED,
                TestLogEvent.PASSED,
                TestLogEvent.SKIPPED,
                TestLogEvent.STANDARD_ERROR,
                TestLogEvent.STANDARD_OUT,
            )
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}
