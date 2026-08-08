import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

val jdkVersion = libs.versions.jdk.get()
val javaVersion = JavaVersion.toVersion(jdkVersion)
val kotlinJvmTarget = JvmTarget.fromTarget(jdkVersion)

android {
    namespace = "com.artemchep.keyguard.ipctestclient"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.artemchep.keyguard.ipctestclient"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The output-pipe expiry test sleeps out the provider's 60 second pipe
        // lifetime, which is longer than the rest of the suite put together.
        // Opt back in with
        // `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=`.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.artemchep.keyguard.ipctestclient.support.SlowIpcTest"
    }

    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}

kotlin {
    jvmToolchain(jdkVersion.toInt())

    compilerOptions {
        jvmTarget = kotlinJvmTarget
    }
}

dependencies {
    implementation(libs.openkeychain.openpgp.api)
    implementation(libs.openkeychain.sshauthentication.api)

    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.jetbrains.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
