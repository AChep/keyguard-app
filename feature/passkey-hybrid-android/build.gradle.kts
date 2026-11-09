plugins {
    id("keyguard.quality")
    id("keyguard.android-library")
    id("keyguard.native-crypto-consumer")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.artemchep.keyguard.feature.passkey.hybrid"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":util:fido-hybrid"))
    implementation(project(":feature:passkey-hybrid-presentation"))
    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.components.resources)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.compose)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
