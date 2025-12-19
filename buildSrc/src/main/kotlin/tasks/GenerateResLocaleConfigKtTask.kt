package tasks

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateResLocaleConfigKtTask : GenerateResLocaleConfigBaseTask() {
    @get:Input
    val packageName: String = GenerateTaskDefaults.PACKAGE_NAME

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = run {
            val dir = composeResourcesDir.get().asFile
            collectLocalesFromComposeResources(dir)
        }

        val payload = locales
            .joinToString { "\"$it\"" }
        val content = """
package $packageName

data object LocaleConfig {
    val locales = listOf($payload)
}
        """.trimIndent()

        outputDir.get().asFile.resolve("LocaleConfig.kt").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
