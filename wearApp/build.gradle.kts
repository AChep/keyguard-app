import com.artemchep.keyguard.buildplugins.android.configureKeyguardApplication
import com.artemchep.keyguard.buildplugins.version.createVersionInfo

plugins {
    id("keyguard.license-policy")
    id("keyguard.crypto-dependency-check")
    id("keyguard.wear-dependency-check")
    id("keyguard.quality")
    id("keyguard.koin")
    alias(libs.plugins.android.application)
    id("keyguard.android-application")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    id("keyguard.resources-common") apply false
    id("keyguard.detekt-custom-rules")
}

// Application roots always revalidate the assembled dependency graph.
koinCompiler {
    strictSafety.set(true)
}

// The flavors share src/main/java, so one production variant covers every call site.
detektCustomRules {
    androidVariant("noneDebug")
}

val versionInfo = createVersionInfo(
    marketingVersion = libs.versions.appVersionName.get(),
    logicalVersion = libs.versions.appVersionCode.get().toInt(),
)

android {
    configureKeyguardApplication(project)
    namespace = "com.artemchep.keyguard"

    defaultConfig {
        applicationId = "com.artemchep.keyguard"
        minSdk = 30

        versionCode = versionInfo.logicalVersion
        versionName = versionInfo.marketingVersion
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
