package com.artemchep.keyguard.buildplugins.nativeio

import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import java.io.File

class NativeIoConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val hostPlatform = detectHostPlatform()
        val libraryFile = File(
            rootProject.projectDir,
            "util/io/build/cargo-target/${hostPlatform.desktopLibRustTarget}/release/" +
                hostPlatform.dynamicLibraryName("keyguard_io_jni"),
        )

        tasks.withType<Test>().configureEach {
            dependsOn(":util:io:compileNativeIoDesktop")
            inputs.file(libraryFile)
                .withPropertyName("nativeIoDesktopLibrary")
            systemProperty(
                NATIVE_IO_LIBRARY_PATH_PROPERTY,
                libraryFile.absolutePath,
            )
        }
    }

    private companion object {
        const val NATIVE_IO_LIBRARY_PATH_PROPERTY = "keyguard.nativeIo.libraryPath"
    }
}
