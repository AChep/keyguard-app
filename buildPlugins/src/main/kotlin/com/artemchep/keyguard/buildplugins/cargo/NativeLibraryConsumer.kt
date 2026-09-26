package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

internal fun Project.configureNativeLibraryTests(moduleName: String) {
    val nativeName = "native${moduleName.toTaskSuffix()}"
    val nativeLibrary = configurations.create("${nativeName}DesktopLibrary") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(
        nativeLibrary.name,
        dependencies.project(
            mapOf(
                "path" to ":util:$moduleName",
                "configuration" to CargoCommonPlugin.NATIVE_DESKTOP_LIBRARY_ELEMENTS_CONFIGURATION_NAME,
            ),
        ),
    )

    tasks.withType<Test>().configureEach {
        inputs.files(nativeLibrary).withPropertyName("${nativeName}DesktopLibrary")
        jvmArgumentProviders.add(
            NativeLibraryPathArgumentProvider("keyguard.$nativeName.libraryPath", nativeLibrary),
        )
    }
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
