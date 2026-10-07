import com.artemchep.keyguard.buildplugins.android.configureKeyguardApplication

plugins {
    id("keyguard.application-root")
    id("keyguard.crypto-dependency-check")
    id("keyguard.wear-dependency-check")
    id("keyguard.android-application")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    id("keyguard.detekt-custom-rules")
}

// The flavors share src/main/java, so one production variant covers every call site.
detektCustomRules {
    androidVariant("noneDebug")
}

android {
    configureKeyguardApplication(project)

    defaultConfig {
        minSdk = 30
    }

    lint {
        disable += "Instantiatable"
    }
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(project(":common"))

    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.horologist.compose.layout)
    debugImplementation(libs.jetbrains.compose.ui.tooling)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    androidTestImplementation(project(":util:crypto"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
        )
    }
}
