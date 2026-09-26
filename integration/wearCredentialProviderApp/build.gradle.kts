import com.artemchep.keyguard.buildplugins.android.enableCoreLibraryDesugaring

plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.application)
    id("keyguard.android-application")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.artemchep.keyguard.integration.wearcredentialproviderapp"
    enableCoreLibraryDesugaring(project)

    defaultConfig {
        applicationId = "com.artemchep.keyguard.integration.wearcredentialproviderapp"
        minSdk = 30

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.jetbrains.compose.ui.tooling.preview)

    debugImplementation(libs.jetbrains.compose.ui.tooling)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
