import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val webdavE2eTest by sourceSets.creating {
    kotlin.srcDir("src/webdavE2eTest/kotlin")
    resources.srcDir("src/webdavE2eTest/resources")
}

dependencies {
    "webdavE2eTestImplementation"(project(":util:webdav"))
    "webdavE2eTestImplementation"(kotlin("test-junit"))
    "webdavE2eTestImplementation"(libs.kotlinx.coroutines.test)
    "webdavE2eTestImplementation"(libs.ktor.ktor.client.cio)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.register<Test>("webdavE2eTest") {
    group = "verification"
    description = "Runs WebDAV E2E tests against a real hacdias/webdav server from PATH."

    dependsOn(webdavE2eTest.classesTaskName)

    testClassesDirs = webdavE2eTest.output.classesDirs
    classpath = webdavE2eTest.runtimeClasspath
    useJUnit()

    maxParallelForks = 1
    forkEvery = 0L
    outputs.upToDateWhen { false }

    doFirst {
        val expectedBinaryMessage = "Expected hacdias/webdav to be installed as 'webdav' on PATH"
        val process = try {
            ProcessBuilder("webdav", "version").start()
        } catch (e: Exception) {
            throw GradleException(
                "$expectedBinaryMessage, but 'webdav version' could not be started.",
                e,
            )
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("Timed out while running 'webdav version'.")
        }
        val output = listOf(process.inputStream, process.errorStream)
            .joinToString(separator = "\n") { it.readBytes().decodeToString().trim() }
            .trim()
        if (process.exitValue() != 0) {
            throw GradleException(
                "$expectedBinaryMessage, but 'webdav version' exited with ${process.exitValue()}.\n$output",
            )
        }
        if (!Regex("""\bversion:\s*5\.""").containsMatchIn(output)) {
            throw GradleException(
                "Expected hacdias/webdav v5.x on PATH, but got:\n$output",
            )
        }
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
