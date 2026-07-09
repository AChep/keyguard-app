import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val sshE2eTest by sourceSets.creating {
    kotlin.srcDir("src/sshE2eTest/kotlin")
    resources.srcDir("src/sshE2eTest/resources")
}

dependencies {
    "sshE2eTestImplementation"(project(":common"))
    "sshE2eTestImplementation"(kotlin("test-junit"))
    "sshE2eTestImplementation"(libs.kotlinx.coroutines.core)
    "sshE2eTestImplementation"(libs.kotlinx.coroutines.test)
    "sshE2eTestImplementation"(libs.bouncycastle.bcprov)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.register<Test>("sshE2eTest") {
    group = "verification"
    description = "Drives real OpenSSH clients against the real Keyguard SSH agent (Rust binary + " +
        "Kotlin IPC server), validating key listing and signing end to end."

    dependsOn(sshE2eTest.classesTaskName)

    testClassesDirs = sshE2eTest.output.classesDirs
    classpath = sshE2eTest.runtimeClasspath
    useJUnit()

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    // The test builds (via cargo) and locates the keyguard-ssh-agent binary itself,
    // and writes throwaway socket dirs under /tmp/kg-sshe2e-<token> so Unix socket
    // paths stay short on macOS and Linux.
    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    // Allow forwarding the verbose flag from the Gradle invocation to the test fork
    // (e.g. -Dkeyguard.sshE2e.verbose=true) so the agent binary logs details.
    System.getProperty("keyguard.sshE2e.verbose")?.let {
        systemProperty("keyguard.sshE2e.verbose", it)
    }

    doFirst {
        fun requireExecutableOnPath(tool: String) {
            val path = System.getenv("PATH").orEmpty()
            val executableNames = if (File.separatorChar == '\\' && !tool.contains('.')) {
                val extensions = System.getenv("PATHEXT")
                    ?.split(File.pathSeparatorChar, ';')
                    ?.filter { it.isNotBlank() }
                    ?: listOf(".COM", ".EXE", ".BAT", ".CMD")
                listOf(tool) + extensions.map { tool + it.lowercase() }
            } else {
                listOf(tool)
            }
            val candidates = path
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .flatMap { dir ->
                    executableNames
                        .asSequence()
                        .map { Path.of(dir).resolve(it) }
                }
            if (candidates.none { Files.isExecutable(it) }) {
                throw GradleException("SSH E2E test requires '$tool' on PATH.")
            }
        }

        fun requireVersionCommand(tool: String, versionArg: String) {
            val process = try {
                ProcessBuilder(tool, versionArg).redirectErrorStream(true).start()
            } catch (e: Exception) {
                throw GradleException(
                    "SSH E2E test requires '$tool' on PATH, but it could not be started.",
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
                    "SSH E2E test requires '$tool' on PATH, but '$tool $versionArg' exited " +
                        "with ${process.exitValue()}.\n$output",
                )
            }
        }

        requireExecutableOnPath("ssh-add")
        requireExecutableOnPath("ssh-keygen")
        requireVersionCommand("cargo", "--version")
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
