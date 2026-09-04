package com.artemchep.keyguard.buildplugins.nativezxcvbn

import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import java.io.File

class NativeZxcvbnConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val hostPlatform = detectHostPlatform()
        val libraryFile = File(
            rootProject.projectDir,
            "util/zxcvbn/build/cargo-target/${hostPlatform.desktopLibRustTarget}/release/" +
                hostPlatform.dynamicLibraryName("keyguard_zxcvbn_jni"),
        )

        tasks.withType<Test>().configureEach {
            dependsOn(":util:zxcvbn:compileNativeZxcvbnDesktop")
            inputs.file(libraryFile)
                .withPropertyName("nativeZxcvbnDesktopLibrary")
            systemProperty(
                NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY,
                libraryFile.absolutePath,
            )
        }
    }

    private companion object {
        const val NATIVE_ZXCVBN_LIBRARY_PATH_PROPERTY = "keyguard.nativeZxcvbn.libraryPath"
    }
}
