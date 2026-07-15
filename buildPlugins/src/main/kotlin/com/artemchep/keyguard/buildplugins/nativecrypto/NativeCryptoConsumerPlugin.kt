package com.artemchep.keyguard.buildplugins.nativecrypto

import com.artemchep.keyguard.buildplugins.cargo.detectHostPlatform
import com.artemchep.keyguard.buildplugins.cargo.dynamicLibraryName
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import java.io.File

class NativeCryptoConsumerPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val hostPlatform = detectHostPlatform()
        val libraryFile = File(
            rootProject.projectDir,
            "util/crypto/build/cargo-target/${hostPlatform.desktopLibRustTarget}/release/" +
                hostPlatform.dynamicLibraryName("keyguard_crypto_jni"),
        )

        tasks.withType<Test>().configureEach {
            dependsOn(":util:crypto:compileNativeCryptoDesktop")
            inputs.file(libraryFile)
                .withPropertyName("nativeCryptoDesktopLibrary")
            systemProperty(
                NATIVE_CRYPTO_LIBRARY_PATH_PROPERTY,
                libraryFile.absolutePath,
            )
        }
    }

    private companion object {
        const val NATIVE_CRYPTO_LIBRARY_PATH_PROPERTY = "keyguard.nativeCrypto.libraryPath"
    }
}
