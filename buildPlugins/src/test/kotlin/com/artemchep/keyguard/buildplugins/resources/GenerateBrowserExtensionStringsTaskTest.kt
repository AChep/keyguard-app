package com.artemchep.keyguard.buildplugins.resources

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GenerateBrowserExtensionStringsTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File

    private fun resource(locale: String, file: String, body: String) {
        root.resolve("resources/$locale/$file").apply {
            parentFile.mkdirs()
            writeText("<resources xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">$body</resources>")
        }
    }

    private fun task(allowlist: String): GenerateBrowserExtensionStringsTask {
        root.resolve("strings.allowlist").writeText(allowlist)
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks.register("generate", GenerateBrowserExtensionStringsTask::class.java).get().apply {
            composeResourcesDir.set(root.resolve("resources"))
            allowlistFile.set(root.resolve("strings.allowlist"))
            localesDir.set(root.resolve("_locales"))
            keysFile.set(root.resolve("i18n-keys.ts"))
        }
    }

    private fun messages(locale: String): Map<*, *> =
        JsonSlurper().parse(root.resolve("_locales/$locale/messages.json")) as Map<*, *>

    private fun Map<*, *>.message(key: String) = (this[key] as Map<*, *>)["message"]

    @Test
    fun `values convert to Chrome messages with English fallback per locale`() {
        root = temporaryFolder.newFolder()
        resource("values", "strings.xml", """
            <string name="greeting">Hello <xliff:g id="name" example="Ann">%1${'$'}s</xliff:g>, %2${'$'}d items</string>
            <string name="price">Costs ${'$'}5, 100%% off, %s and %s</string>
            <string name="quoted">It\'s \"fine\"\nNext</string>
            <string name="untranslated">English only</string>
            <string name="hidden">Not exported</string>
        """.trimIndent())
        resource("values", "plurals.xml", """
            <plurals name="items"><item quantity="one">%1${'$'}d item</item><item quantity="other">%1${'$'}d items</item></plurals>
            <plurals name="logins"><item quantity="one">%d login</item><item quantity="other">%d logins</item></plurals>
        """.trimIndent())
        resource("values-fr-rFR", "strings.xml", """
            <string name="greeting">Bonjour %1${'$'}s, %2${'$'}d éléments</string>
            <string name="quoted">C\'est \"bien\"</string>
        """.trimIndent())
        resource("values-fr-rFR", "plurals.xml", """
            <plurals name="items"><item quantity="other">%1${'$'}d éléments</item></plurals>
        """.trimIndent())
        val task = task(
            """
            # Comments and blank lines are skipped.

            greeting
            price
            quoted
            untranslated
            items
            logins
            """.trimIndent(),
        )
        task.generate()

        val en = messages("en")
        assertEquals("Hello \$arg1\$, \$arg2\$ items", en.message("greeting"))
        assertEquals(
            mapOf("arg1" to mapOf("content" to "\$1"), "arg2" to mapOf("content" to "\$2")),
            (en["greeting"] as Map<*, *>)["placeholders"],
        )
        assertEquals("Costs \$\$5, 100% off, \$arg1\$ and \$arg2\$", en.message("price"))
        assertEquals("It's \"fine\"\nNext", en.message("quoted"))
        assertFalse((en["quoted"] as Map<*, *>).containsKey("placeholders"))
        assertNull(en["hidden"])
        assertEquals("\$arg1\$ item", en.message("items__one"))
        assertEquals("\$arg1\$ items", en.message("items__few"))
        assertEquals("\$arg1\$ login", en.message("logins__one"))
        assertEquals("en", en.message("items__locale"))

        val fr = messages("fr")
        assertEquals("Bonjour \$arg1\$, \$arg2\$ éléments", fr.message("greeting"))
        assertEquals("C'est \"bien\"", fr.message("quoted"))
        assertEquals("English only", fr.message("untranslated"))
        // A translated plural fills its missing categories from its own `other`,
        // never from English; an untranslated one is English as a whole.
        for (category in GenerateBrowserExtensionStringsTask.PLURAL_CATEGORIES) {
            assertEquals("\$arg1\$ éléments", fr.message("items__$category"))
            assertEquals(en.message("logins__$category"), fr.message("logins__$category"))
        }
        // The extension picks the category with the rules of the plural's language.
        assertEquals("fr", fr.message("items__locale"))
        assertEquals("en", fr.message("logins__locale"))

        assertEquals(
            """
            // Generated by ./gradlew :common:generateBrowserExtensionStrings from
            // strings.allowlist. Do not edit.

            /** A message of the extension's `_locales`. */
            export type I18nKey =
              | "greeting"
              | "price"
              | "quoted"
              | "untranslated";

            /**
             * A plural of the extension's `_locales`: one `<key>__<category>` message per
             * category, and `<key>__locale`, the locale of the plural rules that pick it.
             */
            export type I18nPluralKey =
              | "items"
              | "logins";

            """.trimIndent(),
            task.keysFile.get().asFile.readText(),
        )
    }

    @Test
    fun `resource directories map to Chrome locales and unsupported ones are dropped`() {
        root = temporaryFolder.newFolder()
        listOf(
            "values", "values-af-rZA", "values-en-rGB", "values-en-rUS", "values-es-rES", "values-fr-rFR",
            "values-iw-rIL", "values-no-rNO", "values-pt-rBR", "values-pt-rPT", "values-sr-rSP",
            "values-zh-rCN", "values-zh-rTW", "values-b+es+419",
        ).forEach { locale ->
            resource(locale, "strings.xml", "<string name=\"label\">$locale</string>")
        }
        // A stale locale from an earlier run is removed.
        root.resolve("_locales/af/messages.json").apply {
            parentFile.mkdirs()
            writeText("{}")
        }
        task("label").generate()

        val locales = root.resolve("_locales").list()!!.toSet()
        assertEquals(
            setOf(
                "en", "en_GB", "en_US", "es", "fr", "he", "no", "pt_BR", "pt_PT", "sr", "zh_CN", "zh_TW", "es_419",
            ),
            locales,
        )
        assertEquals("values-iw-rIL", messages("he").message("label"))
        assertTrue(root.resolve("_locales/en/messages.json").isFile)
    }

    @Test
    fun `the plural locale is the Chrome locale as a BCP 47 tag`() {
        root = temporaryFolder.newFolder()
        resource("values", "plurals.xml", """
            <plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d items</item></plurals>
        """.trimIndent())
        resource("values-pt-rBR", "plurals.xml", """
            <plurals name="items"><item quantity="one">%d item</item><item quantity="other">%d itens</item></plurals>
        """.trimIndent())
        // A plural without `other` is not a translation.
        resource("values-ru-rRU", "plurals.xml", """
            <plurals name="items"><item quantity="one">%d элемент</item></plurals>
        """.trimIndent())
        task("items").generate()

        assertEquals("pt-BR", messages("pt_BR").message("items__locale"))
        assertEquals("en", messages("ru").message("items__locale"))
        assertEquals("\$arg1\$ items", messages("ru").message("items__few"))
    }

    @Test
    fun `an unknown allowlisted key fails the build`() {
        root = temporaryFolder.newFolder()
        resource("values", "strings.xml", "<string name=\"label\">Label</string>")
        val task = task("label\nmissing_key")
        val error = assertThrows(GradleException::class.java) { task.generate() }
        assertTrue(error.message!!, error.message!!.contains("missing_key"))
    }

    @Test
    fun `a key that only translations have fails the build`() {
        root = temporaryFolder.newFolder()
        resource("values", "strings.xml", "<string name=\"label\">Label</string>")
        resource("values-fr-rFR", "strings.xml", "<string name=\"stale\">Périmé</string>")
        resource("values-fr-rFR", "plurals.xml", """
            <plurals name="stale_items"><item quantity="other">%d éléments</item></plurals>
        """.trimIndent())
        val task = task("label\nstale\nstale_items")
        val error = assertThrows(GradleException::class.java) { task.generate() }
        assertEquals(
            "strings.allowlist names keys that are not in the base strings.xml or plurals.xml: stale, stale_items",
            error.message,
        )
    }

    @Test
    fun `a key that only a regional English directory has fails the build`() {
        root = temporaryFolder.newFolder()
        resource("values", "strings.xml", "<string name=\"label\">Label</string>")
        // Chrome has no `en_CA`, so the directory maps to `en`, as the base one does.
        resource("values-en-rCA", "strings.xml", "<string name=\"regional\">Regional</string>")
        resource("values-en-rCA", "plurals.xml", """
            <plurals name="regional_items"><item quantity="other">%d items</item></plurals>
        """.trimIndent())
        val task = task("label\nregional\nregional_items")
        val error = assertThrows(GradleException::class.java) { task.generate() }
        assertEquals(
            "strings.allowlist names keys that are not in the base strings.xml or plurals.xml: " +
                "regional, regional_items",
            error.message,
        )
    }

    @Test
    fun `resource directories that map to one browser locale fail the build`() {
        root = temporaryFolder.newFolder()
        listOf(
            "values", "values-en-rCA", "values-es-rES", "values-es-rMX", "values-sr-rSP", "values-b+sr+Latn",
            "values-fr-rFR", "values-af-rZA", "values-af-rNA",
        ).forEach { locale ->
            resource(locale, "strings.xml", "<string name=\"label\">$locale</string>")
        }
        val error = assertThrows(GradleException::class.java) { task("label").generate() }
        assertEquals(
            "Resource directories map to the same browser locale, en: values, values-en-rCA; " +
                "sr: values-b+sr+Latn, values-sr-rSP; es: values-es-rES, values-es-rMX. " +
                "Map or skip one in GenerateBrowserExtensionStringsTask.chromeLocale().",
            error.message,
        )
    }

    @Test
    fun `a plural message name taken by a string fails the build`() {
        root = temporaryFolder.newFolder()
        resource("values", "strings.xml", "<string name=\"items__locale\">Taken</string>")
        resource("values", "plurals.xml", """
            <plurals name="items"><item quantity="other">%d items</item></plurals>
        """.trimIndent())
        val error = assertThrows(GradleException::class.java) { task("items\nitems__locale").generate() }
        assertEquals("Keys clash with plural messages: items__locale", error.message)
    }
}
