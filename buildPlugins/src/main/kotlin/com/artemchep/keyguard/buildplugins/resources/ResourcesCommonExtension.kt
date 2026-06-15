package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

abstract class ResourcesCommonExtension {
    abstract val composeResourcesDir: DirectoryProperty
    abstract val composeFilesDir: DirectoryProperty
    abstract val generatedPackageName: Property<String>
    abstract val defaultLocale: Property<String>
    abstract val hashEntries: MapProperty<String, String>
    abstract val hashKotlinOutputDir: DirectoryProperty
    abstract val localeKotlinOutputDir: DirectoryProperty
    abstract val localeResOutputDir: DirectoryProperty

    companion object {
        const val EXTENSION_NAME = "keyguardResources"
        const val GENERATE_HASHES_TASK_NAME = "generateKeyguardResHashesKt"
        const val GENERATE_LOCALE_TASK_NAME = "generateResLocaleConfig"
        const val DEFAULT_PACKAGE_NAME = "com.artemchep.keyguard.build"
        const val DEFAULT_LOCALE = "en-US"
        const val DEFAULT_COMPOSE_RESOURCES_PATH = "src/commonMain/composeResources"
        const val DEFAULT_COMPOSE_FILES_DIRECTORY_NAME = "files"
        const val DEFAULT_HASH_KOTLIN_OUTPUT_PATH = "generated/keyguardResHashesKt/kotlin"
        const val DEFAULT_LOCALE_KOTLIN_OUTPUT_PATH = "generated/keyguardResLocaleConfig/kotlin"
        const val DEFAULT_LOCALE_RES_OUTPUT_PATH = "generated/keyguardResLocaleConfig/res"

        fun defaultHashEntries(): Map<String, String> = mapOf(
            "justdeleteme" to "justdeleteme.json",
            "justgetmydata" to "justgetmydata.json",
            "passkeys" to "passkeys.json",
            "public_suffix_list" to "public_suffix_list.txt",
            "tfa" to "tfa.json",
        )

        fun defaultComposeResourcesDir(project: Project): Directory =
            project.layout.projectDirectory.dir(DEFAULT_COMPOSE_RESOURCES_PATH)

        fun defaultComposeFilesDir(project: Project): Directory =
            defaultComposeResourcesDir(project).dir(DEFAULT_COMPOSE_FILES_DIRECTORY_NAME)

        fun defaultHashKotlinOutputDir(project: Project): Provider<Directory> =
            project.layout.buildDirectory.dir(DEFAULT_HASH_KOTLIN_OUTPUT_PATH)

        fun defaultLocaleKotlinOutputDir(project: Project): Provider<Directory> =
            project.layout.buildDirectory.dir(DEFAULT_LOCALE_KOTLIN_OUTPUT_PATH)

        fun defaultLocaleResOutputDir(project: Project): Provider<Directory> =
            project.layout.buildDirectory.dir(DEFAULT_LOCALE_RES_OUTPUT_PATH)
    }

    init {
        generatedPackageName.convention(DEFAULT_PACKAGE_NAME)
        defaultLocale.convention(DEFAULT_LOCALE)
        hashEntries.convention(defaultHashEntries())
    }
}
