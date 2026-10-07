package com.artemchep.keyguard.buildplugins.kotlin

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** The Android, desktop JVM and Apple targets shared by the utility libraries. */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("keyguard.kotlin-multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        // `:util:dns` gets `com.artemchep.keyguard.util.dns`; a module may still assign its own.
        val defaultNamespace = "com.artemchep.keyguard" + path.replace(':', '.').replace('-', '.')
        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = defaultNamespace
                compileSdk {
                    version = release(libs.versionInt("androidCompileSdk")) {
                        minorApiLevel = libs.versionInt("androidCompileSdkMinor")
                    }
                }
                minSdk = libs.versionInt("androidMinSdk")
                withHostTest {}
            }
            jvm("desktop")
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
        }
    }
}
