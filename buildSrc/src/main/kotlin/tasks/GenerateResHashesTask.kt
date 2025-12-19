package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.io.File
import java.security.MessageDigest

@CacheableTask
abstract class GenerateResHashesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    val packageName: String = GenerateTaskDefaults.PACKAGE_NAME

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile
        val files = inputFiles.files.sortedBy { it.name } // Sort for deterministic output

        // Calculate hashes
        val payload = files.map { file ->
            val name = file.name.substringBefore('.')
            val hash = file.md5()
            name to hash
        }.joinToString(separator = "\n") { (name, hash) ->
            "    const val $name = \"$hash\""
        }

        val content = """
package $packageName

data object FileHashes {
$payload
}
        """.trimIndent()

        outDir.resolve("FileHashes.kt").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        // Use standard formatting to avoid internal dependencies
        return md.digest(readBytes()).joinToString("") { "%02x".format(it) }
    }
}
