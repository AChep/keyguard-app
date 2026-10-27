plugins {
    id("keyguard.quality")
    id("keyguard.kotlin-multiplatform-library")
    id("keyguard.compose-free")
    alias(libs.plugins.kotlin.plugin.serialization)
}

// Shared authoring/delivery limits. Generated sources are committed so Cargo builds independently.
val environmentLimits = linkedMapOf(
    "MAX_VARIABLE_NAME_LENGTH" to 255, // ASCII characters (also bytes); syntax is validated separately.
    "MAX_VARIABLES" to 1_024,
)
val generatedHeader = "// Generated from feature/environments/build.gradle.kts. Do not edit.\n" +
    "// Regenerate with ./gradlew :feature:environments:generateEnvironmentLimits.\n"
val environmentLimitSources = mapOf(
    layout.projectDirectory.file(
        "src/commonMain/kotlin/com/artemchep/keyguard/feature/environments/EnvironmentLimits.kt",
    ) to buildString {
        append(generatedHeader)
        append("package com.artemchep.keyguard.feature.environments\n\n")
        append("object EnvironmentLimits {\n")
        environmentLimits.forEach { (name, value) -> append("    const val $name: Int = $value\n") }
        append("}\n")
    },
    layout.settingsDirectory.file("commonCli/src/limits.rs") to buildString {
        append(generatedHeader)
        environmentLimits.forEach { (name, value) -> append("pub(crate) const $name: usize = $value;\n") }
    },
)

tasks.register("generateEnvironmentLimits") {
    group = "build setup"
    description = "Generates the committed Kotlin and Rust environment limits."
    val sources = environmentLimitSources
    inputs.property("content", sources.values.toList())
    outputs.files(sources.keys)
    doLast {
        sources.forEach { (file, content) -> file.asFile.writeText(content) }
    }
}

val checkEnvironmentLimits = tasks.register("checkEnvironmentLimits") {
    group = "verification"
    description = "Checks that the committed Kotlin and Rust environment limits match Gradle."
    mustRunAfter("generateEnvironmentLimits")
    val sources = environmentLimitSources
    inputs.files(sources.keys).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("content", sources.values.toList())
    doLast {
        sources.forEach { (file, content) ->
            // Git may check out CRLF on Windows; the generated declarations must still match.
            check(file.asFile.isFile && file.asFile.readText().replace("\r\n", "\n") == content) {
                "${file.asFile.name} is missing or stale. Run " +
                    "./gradlew :feature:environments:generateEnvironmentLimits and commit both files."
            }
        }
    }
}
tasks.matching { it.name == "check" || it.name == "detekt" }.configureEach {
    dependsOn(checkEnvironmentLimits)
}
// EnvironmentDotenvCompatibilityTest writes this fixture for scripts/check_environment_dotenv.py.
// Declare it so an up-to-date or cached test run still provides it.
tasks.matching { it.name == "desktopTest" }.configureEach {
    outputs.file(layout.buildDirectory.file("dotenv-compatibility.json"))
}

kotlin {
    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("commonMain") {
            dependencies {
                implementation(project(":standard:presentation"))
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
            }
        }
    }
}
