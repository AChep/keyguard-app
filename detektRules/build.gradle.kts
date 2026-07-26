plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test.utils)
    // On the test analysis classpath so the fixtures can name the real JsonElement types
    // instead of stubbing them.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.collections.immutable)
    // The 2.0.0-alpha.5 Gradle metadata of these two references a `detekt-api`
    // test-fixtures variant that was never published, so resolve them without it.
    testImplementation(libs.detekt.test) {
        isTransitive = false
    }
    testImplementation(libs.detekt.test.junit) {
        isTransitive = false
    }
    testRuntimeOnly(libs.junit5.jupiter.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.test {
    useJUnitPlatform()
}
