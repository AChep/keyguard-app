plugins {
    id("keyguard.quality")
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    testImplementation(kotlin("test-junit"))
}
