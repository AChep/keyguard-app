package com.artemchep.keyguard.buildplugins.kotlin

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** The Android, desktop JVM and Apple targets shared by the utility libraries. */
class KotlinMultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("keyguard.kotlin-multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()
                minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
                withHostTest {}
            }
            jvm("desktop")
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
        }
    }
}
