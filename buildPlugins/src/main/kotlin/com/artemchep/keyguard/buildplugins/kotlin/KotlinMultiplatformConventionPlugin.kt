package com.artemchep.keyguard.buildplugins.kotlin

import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared Kotlin defaults, without imposing a platform target set. */
class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.versionInt("jdk"))
            // Stdlib APIs every module may use without declaring it again.
            compilerOptions.optIn.addAll(
                "kotlin.ExperimentalStdlibApi",
                "kotlin.time.ExperimentalTime",
                "kotlin.uuid.ExperimentalUuidApi",
            )
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
