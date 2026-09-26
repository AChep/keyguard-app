import com.artemchep.keyguard.buildplugins.android.accountManagementFlavors

plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.test)
    id("keyguard.android-test")
    alias(libs.plugins.baseline.profile)
}

android {
    namespace = "com.artemchep.macrobenchmark"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":androidApp"
    // Enable the benchmark to run separately from the app process
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildFeatures {
        buildConfig = true
    }

    accountManagementFlavors()
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
