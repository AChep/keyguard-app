package com.artemchep.keyguard.buildplugins.androidssh

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ResolveAndroidSshAgentPackageVersionTask : DefaultTask() {
    @get:Input
    abstract val marketingVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = "build"
        description = "Resolves the Debian-compatible package version."
    }

    @TaskAction
    fun action() {
        val version = AndroidSshAgentTermuxPackaging.resolvePackageVersion(
            marketingVersion = marketingVersion.get(),
        )

        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("$version\n")
    }
}
