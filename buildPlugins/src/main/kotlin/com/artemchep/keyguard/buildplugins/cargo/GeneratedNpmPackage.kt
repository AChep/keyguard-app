package com.artemchep.keyguard.buildplugins.cargo

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.targets.js.npm.RequiresNpmDependenciesTask
import java.io.File
import javax.inject.Inject

/** Refresh file dependencies even when Yarn has cached the same package version. */
@OptIn(InternalKotlinGradlePluginApi::class)
fun Project.refreshGeneratedNpmPackage(
    installTaskName: String,
    packageName: String,
    producer: TaskProvider<out Task>,
    packageDirectory: Provider<Directory>,
    nodeModulesDirectory: Provider<Directory>,
) {
    val refresh = objects.newInstance<RefreshGeneratedNpmPackageAction>().apply {
        sourceDirectory.set(packageDirectory)
        destinationDirectory.set(nodeModulesDirectory.map { it.dir(packageName) })
    }
    rootProject.tasks.named(installTaskName).configure {
        dependsOn(producer)
        inputs.dir(refresh.sourceDirectory)
            .withPropertyName("generatedPackage.$packageName")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(refresh.destinationDirectory).withPropertyName("installedPackage.$packageName")
        // Every consumer already depends on npm installation. Keeping the refresh
        // in that task also orders it before consumers in other projects.
        doLast(refresh)
    }
    rootProject.allprojects {
        tasks.withType<RequiresNpmDependenciesTask>().configureEach {
            // Kotlin tracks file dependency locations, but not their contents.
            // This location API is internal to the pinned Kotlin Gradle plugin;
            // the consumer fixture guards it when upgrading Kotlin.
            // Limit this input to tasks that actually consume this package,
            // including transitive consumers in other projects and both targets.
            inputs.files(fileBasedNpmDependencyLocations.zip(packageDirectory) { locations, directory ->
                locations.filter { File(it).absoluteFile.normalize() == directory.asFile.absoluteFile.normalize() }
            }).withPropertyName("generatedPackage.$installTaskName.$packageName")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

abstract class RefreshGeneratedNpmPackageAction @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : Action<Task> {
    abstract val sourceDirectory: DirectoryProperty
    abstract val destinationDirectory: DirectoryProperty

    override fun execute(task: Task) {
        fileSystemOperations.sync {
            from(sourceDirectory)
            into(destinationDirectory)
        }
    }
}
