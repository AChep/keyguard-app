package com.artemchep.keyguard.buildplugins.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.application") {
            val android = extensions.getByType<ApplicationExtension>()
            configureAndroidDefaults(android)
            android.defaultConfig.targetSdk = androidCatalog().versionInt("androidTargetSdk")
        }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.library") {
            configureAndroidDefaults(extensions.getByType<LibraryExtension>())
        }
    }
}

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.test") {
            val android = extensions.getByType<TestExtension>()
            configureAndroidDefaults(android)
            android.defaultConfig.targetSdk = androidCatalog().versionInt("androidTargetSdk")
        }
    }
}

private fun Project.configureAndroidDefaults(android: CommonExtension) {
    val libs = androidCatalog()
    val jdk = libs.versionInt("jdk")
    android.compileSdk = libs.versionInt("androidCompileSdk")
    android.defaultConfig.minSdk = libs.versionInt("androidMinSdk")
    android.compileOptions.sourceCompatibility = JavaVersion.toVersion(jdk)
    android.compileOptions.targetCompatibility = JavaVersion.toVersion(jdk)

    // AGP provides this extension through built-in Kotlin support. Applying
    // org.jetbrains.kotlin.android here would conflict with that support.
    extensions.getByType<KotlinAndroidProjectExtension>().apply {
        jvmToolchain(jdk)
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jdk.toString()))
    }
}

internal fun Project.androidCatalog(): VersionCatalog =
    extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun VersionCatalog.versionInt(name: String): Int =
    findVersion(name).get().requiredVersion.toInt()
