package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import javax.inject.Inject

abstract class RustMultiplatformLibraryExtension @Inject constructor(
    private val project: Project,
    objects: ObjectFactory,
) {
    val extraSourceInputs: ConfigurableFileCollection = objects.fileCollection()

    abstract val androidCmakeToolchainFile: RegularFileProperty

    /** KGP exposes the interop package as a String, so configure its model directly. */
    fun appleInterop(packageName: String, requireTargetMapping: Boolean = false) {
        val interopPackageName = packageName
        val moduleTaskName = project.name.toTaskSuffix()
        val targets = appleNativeTargets()
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()
            kotlin.targets.withType<KotlinNativeTarget>().configureEach {
                val mapped = targets.any { target -> target.kotlinTarget == name }
                check(mapped || !requireTargetMapping) {
                    "Missing native$moduleTaskName Rust target mapping for Kotlin target '$name'"
                }
                if (mapped) {
                    compilations.getByName("main").cinterops.named("native$moduleTaskName").configure {
                        packageName(interopPackageName)
                    }
                }
            }
        }
    }
}
