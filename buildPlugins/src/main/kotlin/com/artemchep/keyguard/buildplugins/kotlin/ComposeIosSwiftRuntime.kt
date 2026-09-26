package com.artemchep.keyguard.buildplugins.kotlin

import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import java.io.File
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

/**
 * Use the selected Xcode instead of the build machine path in Compose 1.13's Swift interop.
 * Workaround for https://youtrack.jetbrains.com/issue/CMP-10607; remove once the consumed
 * Compose release includes the upstream linker-path fix.
 */
fun Project.configureComposeIosSwiftRuntime() {
    if (!detectHostPlatform().isMacOs) return

    val swiftCompiler = providers.exec {
        commandLine("xcrun", "--find", "swiftc")
    }.standardOutput.asText.map { File(it.trim()) }

    tasks.withType<KotlinNativeLink>().configureEach {
        val sdk = when (target) {
            "ios_arm64" -> "iphoneos"
            "ios_simulator_arm64" -> "iphonesimulator"
            else -> return@configureEach
        }
        toolOptions.freeCompilerArgs.addAll(
            swiftCompiler.map { compiler ->
                val libraries = compiler.parentFile.parentFile.resolve("lib/swift/$sdk")
                listOf("-linker-option", "-L${libraries.absolutePath}")
            },
        )
    }
}
