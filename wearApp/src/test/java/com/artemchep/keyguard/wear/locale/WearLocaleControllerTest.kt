package com.artemchep.keyguard.wear.locale

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WearLocaleControllerTest {
    @Test
    fun `legacy startup applies saved language before system fallbacks`() {
        val backend = FakeBackend(storedLanguage = "pt-br", deviceLocales = listOf("en-US", "pt-BR"))
        val controller = WearLocaleController(backend)
        controller.initialize()

        assertEquals("pt-BR", controller.state.value.language)
        assertEquals(listOf("pt-BR", "en-US"), backend.appliedLocales)
        assertFalse(backend.migrated)
    }

    @Test
    fun `legacy changes persist and are exposed through the picker flow`() = runBlocking {
        val backend = FakeBackend()
        val controller = WearLocaleController(backend)
        controller.initialize()
        val getLocale = GetLocaleWear(controller)
        assertNull(getLocale().first())

        controller.setLanguage("zh-hant-TW")
        assertEquals("zh-Hant-TW", getLocale().first())
        assertEquals("zh-Hant-TW", backend.storedLanguage)
        assertEquals(listOf("zh-Hant-TW", "en-US"), backend.appliedLocales)

        val restarted = WearLocaleController(backend)
        restarted.initialize()
        assertEquals(controller.state.value, restarted.state.value)
    }

    @Test
    fun `picker retains its language tag when Android canonicalizes an alias`() = runBlocking {
        val backend = FakeBackend()
        val controller = WearLocaleController(backend)
        controller.setLanguage("iw-IL")
        assertEquals("he-IL", controller.state.value.language)
        assertEquals("iw-IL", GetLocaleWear(controller)().first())
    }

    @Test
    fun `returning to system uses current system languages and follows later changes`() = runBlocking {
        val backend = FakeBackend(storedLanguage = "de-DE")
        val controller = WearLocaleController(backend)
        controller.initialize()
        backend.deviceLocales = listOf("uk-UA", "en-US")
        controller.setLanguage(null)

        assertNull(controller.state.value.language)
        assertEquals(listOf("uk-UA", "en-US"), backend.appliedLocales)

        backend.deviceLocales = listOf("fr-FR")
        controller.refresh()
        assertEquals(listOf("fr-FR"), controller.state.value.locales)
        assertEquals(listOf("fr-FR"), backend.appliedLocales)
    }

    @Test
    fun `configuration changes reapply legacy process locale even if selection is unchanged`() {
        val backend = FakeBackend(storedLanguage = "de-DE")
        val controller = WearLocaleController(backend)
        controller.initialize()
        backend.appliedLocales = listOf("en-US")
        controller.refresh()
        assertEquals(listOf("de-DE", "en-US"), backend.appliedLocales)
    }

    @Test
    fun `same language does not write or change the effective configuration`() = runBlocking {
        val backend = FakeBackend(storedLanguage = "pt-BR")
        val controller = WearLocaleController(backend)
        controller.initialize()
        val initial = controller.state.value
        controller.setLanguage("pt-br")
        assertEquals(0, backend.legacyWrites)
        assertEquals(initial, controller.state.value)
    }

    @Test
    fun `failed legacy write does not publish or apply the new language`() = runBlocking {
        val backend = FakeBackend(storedLanguage = "de-DE")
        val controller = WearLocaleController(backend)
        controller.initialize()
        backend.failWrite = true
        assertFailsWith<IOException> { controller.setLanguage("fr-FR") }
        assertEquals("de-DE", controller.state.value.language)
        assertEquals(listOf("de-DE", "en-US"), backend.appliedLocales)
    }

    @Test
    fun `framework is authoritative and observes external changes`() = runBlocking {
        val backend = FakeBackend(usesFrameworkLocales = true, storedLanguage = "de-DE")
        backend.frameworkLocales = listOf("pt-BR", "fr-FR")
        val controller = WearLocaleController(backend)
        controller.initialize()
        assertEquals("pt-BR", controller.state.value.language)
        assertEquals(0, backend.frameworkWrites)
        assertTrue(backend.migrated)
        assertNull(backend.appliedLocales)

        backend.frameworkLocales = listOf("zh-Hant")
        controller.refresh()
        assertEquals("zh-Hant", GetLocaleWear(controller)().first())
        assertEquals(listOf("zh-Hant", "en-US"), controller.state.value.locales)
    }

    @Test
    fun `framework writes never update legacy storage or process defaults`() = runBlocking {
        val backend = FakeBackend(usesFrameworkLocales = true)
        val controller = WearLocaleController(backend)
        controller.initialize()
        controller.setLanguage("pt-br")
        controller.setLanguage("pt-BR")
        assertEquals(1, backend.frameworkWrites)
        assertEquals(listOf("pt-BR"), backend.frameworkLocales)
        controller.setLanguage(null)
        assertTrue(backend.frameworkLocales.isEmpty())
        assertNull(controller.state.value.language)
        assertEquals(0, backend.legacyWrites)
        assertNull(backend.appliedLocales)
    }

    @Test
    fun `upgrade migrates once and never resurrects legacy language after choosing system`() = runBlocking {
        val backend = FakeBackend(usesFrameworkLocales = true, storedLanguage = "zh-hant")
        val controller = WearLocaleController(backend)
        controller.initialize()
        assertEquals(listOf("zh-Hant"), backend.frameworkLocales)
        assertTrue(backend.migrated)

        controller.setLanguage(null)
        val restarted = WearLocaleController(backend)
        restarted.initialize()
        assertTrue(backend.frameworkLocales.isEmpty())
        assertNull(restarted.state.value.language)
        assertEquals(2, backend.frameworkWrites)
    }

    @Test
    fun `empty legacy selection completes migration without changing framework`() {
        val backend = FakeBackend(usesFrameworkLocales = true)
        WearLocaleController(backend).initialize()
        assertTrue(backend.migrated)
        assertEquals(0, backend.frameworkWrites)
    }

    @Test
    fun `failed migration remains retryable`() {
        val backend = FakeBackend(usesFrameworkLocales = true, storedLanguage = "uk-UA")
        backend.failWrite = true
        assertFailsWith<IOException> { WearLocaleController(backend).initialize() }
        assertFalse(backend.migrated)

        backend.failWrite = false
        WearLocaleController(backend).initialize()
        assertEquals(listOf("uk-UA"), backend.frameworkLocales)
        assertTrue(backend.migrated)
    }

    @Test
    fun `invalid persisted tag follows system but invalid user input is rejected`() = runBlocking {
        val backend = FakeBackend(storedLanguage = "not_a_language")
        val controller = WearLocaleController(backend)
        controller.initialize()
        assertNull(controller.state.value.language)
        assertEquals(listOf("en-US"), backend.appliedLocales)
        assertFailsWith<IllegalArgumentException> { controller.setLanguage("not_a_language") }
        assertEquals(0, backend.legacyWrites)
    }

    private class FakeBackend(
        override val usesFrameworkLocales: Boolean = false,
        var storedLanguage: String? = null,
        var deviceLocales: List<String> = listOf("en-US"),
    ) : WearLocaleBackend {
        var frameworkLocales = emptyList<String>()
        var appliedLocales: List<String>? = null
        var migrated = false
        var failWrite = false
        var legacyWrites = 0
        var frameworkWrites = 0

        override fun getApplicationLocales(): List<String> = frameworkLocales
        override fun setApplicationLocales(locales: List<String>) {
            if (failWrite) throw IOException("Test write failed")
            frameworkWrites++
            frameworkLocales = locales
        }
        override fun getSystemLocales(): List<String> = deviceLocales
        override fun getLegacyLanguage(): String? = storedLanguage
        override suspend fun setLegacyLanguage(language: String?) {
            if (failWrite) throw IOException("Test write failed")
            legacyWrites++
            storedLanguage = language
        }
        override fun isMigrationComplete(): Boolean = migrated
        override fun completeMigration() { migrated = true }
        override fun applyLegacyLocales(locales: List<String>) { appliedLocales = locales }
    }
}
