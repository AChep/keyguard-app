package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType

/**
 * Builds the Rust static libraries that back the Apple targets of a Kotlin Multiplatform
 * utility module.
 *
 * Unlike [RustMultiplatformLibraryPlugin] this convention does not build a JNI library: the
 * JVM targets of the module are expected to be implemented in Kotlin. A project named `zip`,
 * for example, is expected to provide the Cargo package `keyguard-zip-c`, producing the
 * `libkeyguard_zip_c.a` static libraries.
 */
class RustAppleLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val naming = RustModuleNaming(this)
        val cargoOffline = cargoOfflineProvider(naming.moduleTaskName)
        tasks.withType<CargoBuildTask>().configureEach {
            offline.set(cargoOffline)
        }

        configureRustAppleLibraries(
            naming = naming,
            targets = appleNativeTargets(),
        )
        Unit
    }
}
