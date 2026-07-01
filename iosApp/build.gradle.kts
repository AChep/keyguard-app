plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(libs.versions.jdk.get().toInt())
}
