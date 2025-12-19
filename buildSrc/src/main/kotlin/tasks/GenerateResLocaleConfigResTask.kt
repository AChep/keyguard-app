package tasks

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateResLocaleConfigResTask : GenerateResLocaleConfigBaseTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val locales = run {
            val dir = composeResourcesDir.get().asFile
            collectLocalesFromComposeResources(dir)
        }

        val payload = locales
            .joinToString(separator = System.lineSeparator()) { "<locale android:name=\"$it\" />" }

        val content = """
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
$payload
</locale-config>
        """.trimIndent()

        outputDir.get().asFile.resolve("xml/locale_config.xml").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }
}
