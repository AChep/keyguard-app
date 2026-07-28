import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("keyguard.native-crypto-consumer")
}

val kdbxE2eTest by sourceSets.creating {
    kotlin.srcDir("src/kdbxE2eTest/kotlin")
    resources.srcDir("src/kdbxE2eTest/resources")
}

dependencies {
    "kdbxE2eTestImplementation"(project(":util:kdbx"))
    "kdbxE2eTestImplementation"(kotlin("test-junit"))
    "kdbxE2eTestImplementation"(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.named<Test>("test") {
    enabled = false
}

val pythonExecutable = providers.gradleProperty("kdbxE2ePython").orElse("python3")
val pythonDriver = layout.projectDirectory.file("python/kdbx_e2e.py")
val requirements = layout.projectDirectory.file("requirements.txt")
val seedDirectory = rootProject.layout.projectDirectory.dir("util/kdbx/src/jvmCommonTest/resources")
val artifactsDirectory = layout.buildDirectory.dir("kdbxE2eTest/artifacts")

tasks.register<Test>("kdbxE2eTest") {
    group = "verification"
    description = "Runs KDBX interoperability tests against pykeepass."

    dependsOn(kdbxE2eTest.classesTaskName)

    testClassesDirs = kdbxE2eTest.output.classesDirs
    classpath = kdbxE2eTest.runtimeClasspath
    useJUnit()

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    systemProperty("keyguard.repoRoot", rootDir.absolutePath)
    systemProperty("keyguard.kdbxE2e.python", pythonExecutable.get())
    systemProperty("keyguard.kdbxE2e.driver", pythonDriver.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.seedDir", seedDirectory.asFile.absolutePath)
    systemProperty("keyguard.kdbxE2e.artifactsDir", artifactsDirectory.get().asFile.absolutePath)

    doFirst {
        val command = listOf(
            pythonExecutable.get(),
            pythonDriver.asFile.absolutePath,
            "doctor",
        )
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw GradleException(pythonSetupMessage(pythonExecutable.get(), requirements.asFile), e)
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("Timed out while validating the pykeepass runtime.\n" +
                pythonSetupMessage(pythonExecutable.get(), requirements.asFile))
        }
        val output = process.inputStream.readBytes().decodeToString().trim()
        if (process.exitValue() != 0) {
            throw GradleException(
                "pykeepass runtime validation failed with exit code ${process.exitValue()}.\n" +
                    output + "\n" + pythonSetupMessage(pythonExecutable.get(), requirements.asFile),
            )
        }
    }

    testLogging {
        events = setOf(
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

fun pythonSetupMessage(python: String, requirementsFile: java.io.File): String =
    "KDBX E2E tests require the pinned Python dependencies. Create a virtualenv and run:\n" +
        "  $python -m pip install -r ${requirementsFile.absolutePath}\n" +
        "Then select it with -PkdbxE2ePython=/path/to/venv/bin/python."
