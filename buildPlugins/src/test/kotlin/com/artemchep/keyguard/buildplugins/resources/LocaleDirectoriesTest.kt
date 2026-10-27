package com.artemchep.keyguard.buildplugins.resources

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocaleDirectoriesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `legacy directory names parse language, script and region`() {
        assertEquals(LocaleDirectory("en", region = "US"), parseLocaleDirectory("values-en-rUS"))
        assertEquals(LocaleDirectory("fr"), parseLocaleDirectory("values-fr"))
        assertEquals(LocaleDirectory("sr", script = "Latn"), parseLocaleDirectory("values-sr-latn"))
        assertEquals(LocaleDirectory("es", region = "419"), parseLocaleDirectory("values-es-r419"))
    }

    @Test
    fun `BCP 47 directory names parse their subtags`() {
        assertEquals(
            LocaleDirectory("sr", script = "Latn", region = "RS"),
            parseLocaleDirectory("values-b+sr+latn+rs"),
        )
        assertEquals(
            LocaleDirectory("ca", region = "ES", variants = listOf("valencia")),
            parseLocaleDirectory("values-b+ca+ES+valencia"),
        )
    }

    @Test
    fun `base and unrelated directories have no locale`() {
        assertNull(parseLocaleDirectory("values"))
        assertNull(parseLocaleDirectory("drawable"))
    }

    @Test(expected = GradleException::class)
    fun `unsupported qualifiers fail the build`() {
        parseLocaleDirectory("values-en-rUS-v21")
    }

    @Test
    fun `locale config keeps the default locale first and sorts the rest`() {
        val root = temporaryFolder.newFolder()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("generate", GenerateLocaleConfigTask::class.java).get().apply {
            localeDirectoryNames.set(listOf("values-zh-rTW", "values", "values-b+sr+Latn", "values-en-rUS", "values-bn-rBD"))
            defaultLocale.set("en-US")
            packageName.set("test")
            kotlinOutputDir.set(root.resolve("kotlin"))
            resOutputDir.set(root.resolve("res"))
        }
        task.generate()
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <locale-config xmlns:android="http://schemas.android.com/apk/res/android">
            <locale android:name="en-US" />
            <locale android:name="sr-Latn" />
            <locale android:name="bn-BD" />
            <locale android:name="zh-TW" />
            </locale-config>
            """.trimIndent(),
            root.resolve("res/xml/locale_config.xml").readText(),
        )
    }

    @Test
    fun `mac bundle localizations follow the resource directories`() {
        val resources = temporaryFolder.newFolder()
        listOf("values", "values-iw-rIL", "values-en-rUS", "values-bn-rBD", "values-b+sr+Latn", "drawable")
            .forEach { resources.resolve(it).mkdirs() }
        resources.resolve("values-fr-rFR").writeText("not a directory")
        assertEquals(
            listOf("sr_Latn", "bn_BD", "en_US", "iw_IL"),
            macBundleLocalizations(resources),
        )
    }
}
