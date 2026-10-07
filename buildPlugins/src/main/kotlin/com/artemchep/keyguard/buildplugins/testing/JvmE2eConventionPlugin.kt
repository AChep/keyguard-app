package com.artemchep.keyguard.buildplugins.testing

import com.artemchep.keyguard.buildplugins.libs
import com.artemchep.keyguard.buildplugins.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Configures the suite registered by [JvmE2eConventionPlugin]. */
abstract class JvmE2eExtension {
    /** The host tools that `verifyE2eEnvironment` checks before the suite runs. */
    abstract val toolchain: Property<E2eToolchain>
}

/** A separately invoked JVM suite, named after its integration project. */
class JvmE2eConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(libs.versionInt("jdk"))
        }

        // Kotlin and Java use src/<suite>/kotlin, java and resources for a named source set.
        val suite = extensions.getByType<SourceSetContainer>().create(name)
        dependencies.add(suite.implementationConfigurationName, "org.jetbrains.kotlin:kotlin-test-junit")
        tasks.named<Test>("test") {
            enabled = false
        }
        val e2e = extensions.create<JvmE2eExtension>("keyguardE2e")
        val verifyEnvironment = tasks.register<VerifyE2eEnvironmentTask>("verifyE2eEnvironment") {
            toolchain.set(e2e.toolchain)
            onlyIf("the suite declares an E2E toolchain") { toolchain.isPresent }
        }
        tasks.register<Test>(name) {
            group = "verification"
            dependsOn(suite.classesTaskName)
            dependsOn(verifyEnvironment)
            // Suites build Keyguard binaries with Cargo or read fixtures from the repository.
            systemProperty("keyguard.repoRoot", rootDir.absolutePath)
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
