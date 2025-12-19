import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "com.artemchep.test"
    testOptions.targetSdk = libs.versions.androidTargetSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("11")
    }
}

dependencies {
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
