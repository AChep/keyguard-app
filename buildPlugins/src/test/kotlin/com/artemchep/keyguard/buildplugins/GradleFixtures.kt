package com.artemchep.keyguard.buildplugins

import org.gradle.testkit.runner.GradleRunner
import java.io.File

/** Reuse the outer build's cache and network setting, including on a fresh CI worker. */
internal fun fixtureGradleRunner(projectDir: File, vararg arguments: String): GradleRunner {
    val sharedArguments = listOf("--stacktrace", "--max-workers=2") +
        System.getProperty("keyguard.test.gradleUserHome")?.let { listOf("-g", it) }.orEmpty() +
        if (System.getProperty("keyguard.test.offline").toBoolean()) listOf("--offline") else emptyList()
    return GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(arguments.toList() + sharedArguments)
}
