import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
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

    doFirst {
        // Preflight: a real gpg toolchain must be on PATH.
        fun requireOnPath(tool: String, versionArg: String) {
            val process = try {
                ProcessBuilder(tool, versionArg).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw GradleException(
                    "GPG E2E test requires '$tool' on PATH, but it could not be started.",
                    e,
                )
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GradleException("Timed out while running '$tool $versionArg'.")
            }
            val output = process.inputStream.readBytes().decodeToString().trim()
            if (process.exitValue() != 0) {
                throw GradleException(
                    "GPG E2E test requires '$tool' on PATH, but '$tool $versionArg' exited " +
                        "with ${process.exitValue()}.\n$output",
                )
            }
        }
        requireOnPath("gpg", "--version")
        requireOnPath("gpgconf", "--version")
        requireOnPath("cargo", "--version")
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
