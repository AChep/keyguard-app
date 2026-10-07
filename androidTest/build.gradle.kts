import com.artemchep.keyguard.buildplugins.android.accountManagementFlavors

plugins {
    id("keyguard.quality")
    id("keyguard.android-library")
}

android {
    namespace = "com.artemchep.test"
    testOptions.targetSdk = libs.versions.androidTargetSdk.get().toInt()

    accountManagementFlavors()
}

dependencies {
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}
