plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.library)
    id("keyguard.android-library")
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    id("keyguard.native-crypto-consumer")
}

android {
    namespace = "com.artemchep.keyguard.feature.android.ipc"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":feature:android-ipc-presentation"))

    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kodein.kodein.di)
    implementation(libs.kodein.kodein.di.framework.android.x.viewmodel.savedstate)
    implementation(libs.openkeychain.openpgp.api)
    implementation(libs.openkeychain.sshauthentication.api)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
