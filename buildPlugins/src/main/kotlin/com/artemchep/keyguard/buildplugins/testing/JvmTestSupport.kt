package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

fun Test.verboseTestLogging(includeSkipped: Boolean = true) {
    testLogging {
        events = setOf(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.STANDARD_ERROR,
            TestLogEvent.STANDARD_OUT,
        ) + if (includeSkipped) setOf(TestLogEvent.SKIPPED) else emptySet()
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showStackTraces = true
        showStandardStreams = true
    }
}

/** Benchmarks stay opt-in and reuse compiled tests without running the ordinary test task. */
fun Project.registerJvmBenchmark(
    name: String,
    description: String,
    testPattern: String,
    includeSkipped: Boolean = true,
    useEnglishLocale: Boolean = true,
    configure: Test.() -> Unit = {},
): TaskProvider<Test> {
    val sourceTest = tasks.named<Test>("desktopTest")
    val sourceClasses = tasks.named("desktopTestClasses")
    return tasks.register<Test>(name) {
        group = "verification"
        this.description = description
        dependsOn(sourceClasses)
        // Copy the compiled-test collections without making desktopTest a task dependency.
        testClassesDirs = sourceTest.get().testClassesDirs
        classpath = sourceTest.get().classpath
        maxParallelForks = 1
        forkEvery = 0L
        outputs.upToDateWhen { false }
        if (useEnglishLocale) {
            systemProperty("user.language", "en")
            systemProperty("user.country", "US")
        }
        filter {
            includeTestsMatching(testPattern)
            isFailOnNoMatchingTests = true
        }
        verboseTestLogging(includeSkipped)
        configure()
    }
}

fun Test.forwardSystemProperties(names: Iterable<String>) {
    names.forEach { name ->
        project.providers.systemProperty(name).orNull?.let { value -> systemProperty(name, value) }
    }
}

fun Test.benchmarkReport(
    propertyName: String,
    output: Provider<RegularFile>,
    clearExisting: Boolean = false,
) {
    val outputFile = output.get().asFile
    systemProperty(propertyName, outputFile.absolutePath)
    if (clearExisting) {
        doFirst {
            outputFile.parentFile.mkdirs()
            outputFile.delete()
        }
    }
}

fun Test.flightRecorder(output: Provider<RegularFile>, clearExisting: Boolean = false) {
    val outputFile = output.get().asFile
    doFirst {
        outputFile.parentFile.mkdirs()
        if (clearExisting) outputFile.delete()
    }
    jvmArgs(
        "-XX:StartFlightRecording=filename=${outputFile.absolutePath},settings=profile,dumponexit=true",
        "-XX:FlightRecorderOptions=stackdepth=256",
    )
}
