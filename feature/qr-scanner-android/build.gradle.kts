plugins {
    id("keyguard.quality")
    alias(libs.plugins.android.library)
    id("keyguard.android-library")
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.artemchep.keyguard.feature.qr.scanner"
}

dependencies {
    implementation(project(":common"))

    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.jetbrains.compose.components.resources)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.accompanist.permissions)
    implementation(libs.google.play.services.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.core)
}
