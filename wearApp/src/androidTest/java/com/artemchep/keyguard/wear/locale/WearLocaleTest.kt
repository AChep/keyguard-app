package com.artemchep.keyguard.wear.locale

import android.view.View
import androidx.core.app.LocaleManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_main_header_title
import com.artemchep.keyguard.wear.WearActivity
import com.artemchep.keyguard.wear.WearApp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Run on both API 30–32 and API 33+ to exercise the two platform implementations. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class WearLocaleTest {
    @Test
    fun languageChangesReachActivityAndComposeResourcesAndPersist() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as WearApp
        val getLocale = application.koin.get<GetLocale>()
        val putLocale = application.koin.get<PutLocale>()
        val backend = AndroidWearLocaleBackend(application)
        val originalLanguage = getLocale().first()
        val originalFrameworkLocales = backend.getApplicationLocales()

        try {
            putLocale("en-US").bind()
            ActivityScenario.launch(WearActivity::class.java).use { scenario ->
                awaitLocale(scenario, "en-US")
                var originalActivity: WearActivity? = null
                var originalDensity = 0
                var originalFontScale = 0f
                scenario.onActivity {
                    originalActivity = it
                    originalDensity = it.resources.configuration.densityDpi
                    originalFontScale = it.resources.configuration.fontScale
                }

                putLocale("pt-BR").bind()
                awaitLocale(scenario, "pt-BR")
                assertEquals("pt-BR", getLocale().first())
                assertEquals("pt-BR", Locale.getDefault().toLanguageTag())
                assertEquals("Configurações", getString(Res.string.settings_main_header_title))
                scenario.onActivity {
                    assertNotSame(originalActivity, it)
                    assertEquals(originalDensity, it.resources.configuration.densityDpi)
                    assertEquals(originalFontScale, it.resources.configuration.fontScale, 0f)
                }

                // A newly constructed controller reads the platform's persisted selection.
                withContext(Dispatchers.Main) {
                    val restarted = WearLocaleController(AndroidWearLocaleBackend(application))
                    assertEquals("pt-BR", restarted.state.value.language)
                }
                scenario.recreate()
                awaitLocale(scenario, "pt-BR")

                putLocale("ar-SA").bind()
                awaitLocale(scenario, "ar-SA")
                scenario.onActivity {
                    assertEquals(View.LAYOUT_DIRECTION_RTL, it.resources.configuration.layoutDirection)
                }

                putLocale(null).bind()
                val systemLanguage = LocaleManagerCompat.getSystemLocales(application)[0]!!.toLanguageTag()
                awaitLocale(scenario, systemLanguage)
                assertNull(getLocale().first())
                assertEquals(systemLanguage, Locale.getDefault().toLanguageTag())
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (backend.usesFrameworkLocales) {
                    backend.setApplicationLocales(originalFrameworkLocales)
                    application.koin.get<WearLocaleController>().refresh()
                } else {
                    putLocale(originalLanguage).bind()
                }
            }
        }
    }

    private suspend fun awaitLocale(scenario: ActivityScenario<WearActivity>, language: String) {
        withTimeout(10_000L) {
            while (true) {
                var actual: String? = null
                scenario.onActivity { actual = it.resources.configuration.locales[0].toLanguageTag() }
                if (actual == language) return@withTimeout
                delay(50L)
            }
        }
    }
}
