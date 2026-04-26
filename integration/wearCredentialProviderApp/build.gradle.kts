plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.artemchep.keyguard.integration.wearcredentialproviderapp"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.artemchep.keyguard.integration.wearcredentialproviderapp"
        minSdk = 30
        targetSdk = libs.versions.androidTargetSdk.get().toInt()

        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
    coreLibraryDesugaring(libs.android.desugarjdklibs)

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

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}
