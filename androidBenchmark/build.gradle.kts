import com.android.build.api.dsl.ManagedVirtualDevice
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baseline.profile)
}

android {
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    namespace = "com.artemchep.macrobenchmark"

    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    targetProjectPath = ":androidApp"
    // Enable the benchmark to run separately from the app process
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildFeatures {
        buildConfig = true
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

baselineProfile {
    // This enables using connected devices to generate profiles. The default is true.
    // When using connected devices, they must be rooted or API 33 and higher.
    useConnectedDevices = true
}

dependencies {
    implementation(project(":androidTest"))
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.profileinstaller)
}
