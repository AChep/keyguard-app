import com.artemchep.keyguard.buildplugins.android.configureKeyguardApplication
import com.artemchep.keyguard.buildplugins.version.createVersionInfo

plugins {
    id("keyguard.license-policy")
    id("keyguard.crypto-dependency-check")
    id("keyguard.quality")
    alias(libs.plugins.android.application)
    id("keyguard.android-application")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.parcelize)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.baseline.profile)
    id("keyguard.resources-common") apply false
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
        versionCode = versionInfo.logicalVersion
        versionName = versionInfo.marketingVersion
    }

    buildTypes {
        create("benchmarkRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

androidComponents {
    listOf(
        "benchmarkRelease",
        "nonMinifiedRelease",
    ).forEach { buildType ->
        onVariants(selector().withBuildType(buildType)) { variant ->
            variant.sources.kotlin?.addStaticSourceDirectory(
                "src/benchmarkShared/kotlin",
            )
            variant.sources.manifests.addStaticManifestFile(
                "src/benchmarkShared/AndroidManifest.xml",
            )
        }
    }
    onVariants(selector().withBuildType("benchmarkRelease")) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            "src/benchmarkRelease/AndroidManifest.xml",
        )
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":feature:qr-scanner-android"))
    baselineProfile(project(":androidBenchmark"))

    // Credential exchange (CXF/CXP) export registration relies on the
    // Play Services backend and only wired into the playStore flavor.
    "playStoreImplementation"(libs.androidx.credentials.providerevents.play.services)

    // Unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)

    // Android tests
    androidTestImplementation(project(":androidTest"))
    androidTestImplementation(project(":util:crypto"))
    androidTestImplementation(project(":util:io"))
    androidTestImplementation(libs.androidx.arch.core.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.web)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material.ExperimentalMaterialApi")
        val args = listOf(
            "-Xexpect-actual-classes",
        )
        freeCompilerArgs.addAll(args)
    }
}
