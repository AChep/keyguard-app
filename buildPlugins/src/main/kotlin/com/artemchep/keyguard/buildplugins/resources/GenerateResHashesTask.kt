package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

@CacheableTask
abstract class GenerateResHashesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @get:Input
    abstract val hashEntries: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @TaskAction
    fun generate() {
        val payload = hashEntries.get()
            .toSortedMap()
            .entries
            .joinToString(separator = "\n") { (constantName, relativePath) ->
                val file = sourceRoot.file(relativePath).get().asFile
                val hash = file.md5()
                "    const val $constantName = \"$hash\""
            }

        val content = """
package ${packageName.get()}

data object FileHashes {
$payload
}
        """.trimIndent()

        outputDir.get().asFile.resolve("FileHashes.kt").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(readBytes()).joinToString("") { "%02x".format(it) }
    }
}
