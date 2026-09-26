package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** A separately invoked JVM suite, named after its integration project. */
class JvmE2eConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
        }

        // Kotlin and Java use src/<suite>/kotlin, java and resources for a named source set.
        val suite = extensions.getByType<SourceSetContainer>().create(name)
        dependencies.add(suite.implementationConfigurationName, "org.jetbrains.kotlin:kotlin-test-junit")
        tasks.named<Test>("test") {
            enabled = false
        }
        tasks.register<Test>(name) {
            group = "verification"
            dependsOn(suite.classesTaskName)
            testClassesDirs = suite.output.classesDirs
            classpath = suite.runtimeClasspath
            useJUnit()
            maxParallelForks = 1
            forkEvery = 0L
            outputs.upToDateWhen { false }
            verboseTestLogging()
        }
        Unit
    }
}
