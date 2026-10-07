package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes the desktop library of the Rust module at [producerPath] to JVM tests. [testTaskName]
 * limits this to one task, so tests that never load the library do not build it.
 */
fun Project.configureNativeLibraryTests(
    producerPath: String,
    testTaskName: String? = null,
) {
    val nativeName = "native${producerPath.substringAfterLast(':').toTaskSuffix()}"
    val nativeLibrary = resolvableFrom(
        configurationName = "${nativeName}DesktopLibrary",
        producerConfiguration = CargoCommonPlugin.NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME,
        producerPath,
    )

    tasks.withType<Test>().named { name -> testTaskName == null || name == testTaskName }.configureEach {
        inputs.files(nativeLibrary).withPropertyName("${nativeName}DesktopLibrary")
        jvmArgumentProviders.add(
            NativeLibraryPathArgumentProvider("keyguard.$nativeName.libraryPath", nativeLibrary),
        )
    }
}

/**
 * Resolves the binaries that [producerPaths] bundle with the desktop app, in a configuration named
 * `bundledAppResources`.
 */
fun Project.bundledAppResourcesFrom(vararg producerPaths: String): Configuration = resolvableFrom(
    configurationName = "bundledAppResources",
    producerConfiguration = CargoCommonPlugin.BUNDLED_APP_RESOURCES_ELEMENTS_CONFIGURATION_NAME,
    *producerPaths,
)

/** Creates [configurationName], which resolves [producerConfiguration] of every [producerPaths]. */
private fun Project.resolvableFrom(
    configurationName: String,
    producerConfiguration: String,
    vararg producerPaths: String,
): Configuration {
    val configuration = configurations.create(configurationName) {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    producerPaths.forEach { producerPath ->
        dependencies.add(
            configuration.name,
            dependencies.project(
                mapOf(
                    "path" to producerPath,
                    "configuration" to producerConfiguration,
                ),
            ),
        )
    }
    return configuration
}

class NativeLibraryPathArgumentProvider(
    @get:Input
    val systemPropertyName: String,
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val library: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-D$systemPropertyName=${library.singleFile.absolutePath}")
}
