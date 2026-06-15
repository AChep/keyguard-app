import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

val jdkVersion = libs.versions.jdk.get()
val javaVersion = JavaVersion.toVersion(jdkVersion)
val kotlinJvmTarget = JvmTarget.fromTarget(jdkVersion)

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "com.artemchep.test"
    testOptions.targetSdk = libs.versions.androidTargetSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    val accountManagementDimension = "accountManagement"
    flavorDimensions += accountManagementDimension
    productFlavors {
        maybeCreate("playStore").apply {
            dimension = accountManagementDimension
        }
        maybeCreate("none").apply {
            dimension = accountManagementDimension
        }
    }
}

kotlin {
    jvmToolchain(jdkVersion.toInt())

    compilerOptions {
        jvmTarget = kotlinJvmTarget
    }
}

dependencies {
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
