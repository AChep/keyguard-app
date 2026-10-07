package com.artemchep.keyguard.buildplugins.android

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        val android = extensions.getByType<ApplicationExtension>()
        configureAndroidDefaults(android)
        android.defaultConfig.targetSdk = libs.versionInt("androidTargetSdk")
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        configureAndroidDefaults(extensions.getByType<LibraryExtension>())
    }
}

class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        val android = extensions.getByType<TestExtension>()
        configureAndroidDefaults(android)
        android.defaultConfig.targetSdk = libs.versionInt("androidTargetSdk")
    }
}

private fun Project.configureAndroidDefaults(android: CommonExtension) {
    val jdk = libs.versionInt("jdk")
    android.compileSdk {
        version = release(libs.versionInt("androidCompileSdk")) {
            minorApiLevel = libs.versionInt("androidCompileSdkMinor")
        }
    }
    android.defaultConfig.minSdk = libs.versionInt("androidMinSdk")
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    android.compileOptions.sourceCompatibility = JavaVersion.toVersion(jdk)
    android.compileOptions.targetCompatibility = JavaVersion.toVersion(jdk)

    // AGP provides this extension through built-in Kotlin support. Applying
    // org.jetbrains.kotlin.android here would conflict with that support.
    extensions.getByType<KotlinAndroidProjectExtension>().apply {
        jvmToolchain(jdk)
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jdk.toString()))
    }
}
