import com.artemchep.keyguard.buildplugins.android.accountManagementFlavors

plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.library)
    id("keyguard.android-library")
}

android {
    namespace = "com.artemchep.test"
    testOptions.targetSdk = libs.versions.androidTargetSdk.get().toInt()

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    accountManagementFlavors()
}

dependencies {
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
