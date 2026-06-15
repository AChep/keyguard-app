package com.artemchep.keyguard.buildplugins.resources

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class ResourcesCommonPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val extension = extensions.create<ResourcesCommonExtension>(ResourcesCommonExtension.EXTENSION_NAME).apply {
            composeResourcesDir.convention(ResourcesCommonExtension.defaultComposeResourcesDir(project))
            composeFilesDir.convention(ResourcesCommonExtension.defaultComposeFilesDir(project))
            hashKotlinOutputDir.convention(ResourcesCommonExtension.defaultHashKotlinOutputDir(project))
            localeKotlinOutputDir.convention(ResourcesCommonExtension.defaultLocaleKotlinOutputDir(project))
            localeResOutputDir.convention(ResourcesCommonExtension.defaultLocaleResOutputDir(project))
        }

        val generateResHashesKt = tasks.register<GenerateResHashesTask>(
            ResourcesCommonExtension.GENERATE_HASHES_TASK_NAME,
        ) {
            sourceRoot.set(extension.composeFilesDir)
            hashEntries.set(extension.hashEntries)
            inputFiles.from(
                providers.provider {
                    extension.hashEntries.get()
                        .values
                        .map { relativePath -> extension.composeFilesDir.file(relativePath).get().asFile }
                },
            )
            outputDir.set(extension.hashKotlinOutputDir)
            packageName.set(extension.generatedPackageName)
        }

        val generateLocaleConfig = tasks.register<GenerateLocaleConfigTask>(
            ResourcesCommonExtension.GENERATE_LOCALE_TASK_NAME,
        ) {
            localeDirectoryNames.set(
                providers.provider {
                    extension.composeResourcesDir.get().asFile.listFiles()
                        ?.filter { file -> file.isDirectory && file.name.startsWith("values") }
                        ?.map { it.name }
                        ?.sorted()
                        ?: emptyList()
                },
            )
            defaultLocale.set(extension.defaultLocale)
            packageName.set(extension.generatedPackageName)
            kotlinOutputDir.set(extension.localeKotlinOutputDir)
            resOutputDir.set(extension.localeResOutputDir)
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
            kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateResHashesKt.flatMap { it.outputDir })
            kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateLocaleConfig.flatMap { it.kotlinOutputDir })
        }

        pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val androidComponents = extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()
            androidComponents.onVariants { variant ->
                variant.sources.res?.addGeneratedSourceDirectory(
                    generateLocaleConfig,
                ) { task -> task.resOutputDir }
            }
        }
    }
}
