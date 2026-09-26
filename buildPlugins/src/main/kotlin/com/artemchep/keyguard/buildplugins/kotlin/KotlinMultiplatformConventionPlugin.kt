package com.artemchep.keyguard.buildplugins.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Shared Kotlin defaults, without imposing a platform target set. */
class KotlinMultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
