package com.artemchep.keyguard.buildplugins.resources

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GenerateAppleStringsTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `incomplete locales receive English strings and whole plurals while translations win`() {
        val root = temporaryFolder.newFolder()
        val resources = root.resolve("resources")
        fun resource(locale: String, file: String, body: String) {
            resources.resolve("$locale/$file").apply {
                parentFile.mkdirs()
                writeText("<resources>$body</resources>")
            }
        }
        resource("values", "strings.xml", """
            <string name="new_label">Create your vault</string>
            <string name="greeting">Hello %1${'$'}s, %2${'$'}d items</string>
            <string name="translated">English</string>
        """.trimIndent())
        resource("values", "plurals.xml", """
            <plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d items</item></plurals>
            <plurals name="new_items"><item quantity="one">%d new item</item><item quantity="other">%d new items</item></plurals>
        """.trimIndent())
        resource("values-en-rUS", "strings.xml", "<string name=\"translated\">American</string>")
        resource("values-fr", "strings.xml", "<string name=\"translated\">Français</string>")
        resource("values-fr", "plurals.xml", """
            <plurals name="items"><item quantity="other">%d éléments</item></plurals>
        """.trimIndent())
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("generate", GenerateAppleStringsTask::class.java).get().apply {
            composeResourcesDir.set(resources)
            catalogFile.set(root.resolve("Localizable.xcstrings"))
            swiftFile.set(root.resolve("L10n.swift"))
        }
        task.generate()
        val catalog = JsonSlurper().parse(task.catalogFile.get().asFile)
        fun Any?.at(vararg path: String): Any? = path.fold(this) { node, key -> (node as Map<*, *>)[key] }
        fun localization(key: String, locale: String) = catalog.at("strings", key, "localizations", locale)
        fun value(key: String, locale: String) = localization(key, locale).at("stringUnit", "value")
        for (locale in listOf("en-US", "fr")) {
            assertEquals("Create your vault", value("new_label", locale))
            assertEquals("Hello %1${'$'}@, %2${'$'}lld items", value("greeting", locale))
            assertEquals(localization("new_items", "en"), localization("new_items", locale))
        }
        assertEquals("American", value("translated", "en-US"))
        assertEquals("Français", value("translated", "fr"))
        assertEquals(localization("items", "en"), localization("items", "en-US"))
        val plural = localization("items", "fr").at("substitutions", "arg1", "variations", "plural") as Map<*, *>
        assertFalse(plural.containsKey("one"))
        assertEquals("%arg éléments", plural.at("other", "stringUnit", "value"))
    }
}
