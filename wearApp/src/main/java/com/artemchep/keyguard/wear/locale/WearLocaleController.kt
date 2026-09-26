package com.artemchep.keyguard.wear.locale

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface WearLocaleBackend {
    val usesFrameworkLocales: Boolean
    fun getApplicationLocales(): List<String>
    fun setApplicationLocales(locales: List<String>)
    fun getSystemLocales(): List<String>
    fun getLegacyLanguage(): String?
    suspend fun setLegacyLanguage(language: String?)
    fun isMigrationComplete(): Boolean
    fun completeMigration()
    fun applyLegacyLocales(locales: List<String>)
}

internal data class WearLocaleState(
    val language: String?,
    val locales: List<String>,
)

/** Accessed on the main thread; storage writes may suspend on an IO dispatcher. */
internal class WearLocaleController(
    private val backend: WearLocaleBackend,
) {
    val usesFrameworkLocales: Boolean get() = backend.usesFrameworkLocales

    private val writeMutex = Mutex()
    private val mutableState = MutableStateFlow(readState())
    val state = mutableState.asStateFlow()

    fun initialize() {
        if (usesFrameworkLocales && !backend.isMigrationComplete()) {
            // An existing framework selection takes precedence over the old preference.
            val legacyLanguage = legacyLanguage()
            if (backend.getApplicationLocales().isEmpty() && legacyLanguage != null) {
                backend.setApplicationLocales(listOf(legacyLanguage))
            }
            // Even an empty selection must be marked as migrated, so selecting System later
            // never resurrects a legacy preference on the next process start.
            backend.completeMigration()
        }
        refresh()
    }

    fun refresh() {
        val newState = readState()
        if (!usesFrameworkLocales) {
            // Compose Multiplatform's non-composable resource access uses the process locale.
            // Reapply even when the preference is unchanged: Android may reset it on config changes.
            backend.applyLegacyLocales(newState.locales)
        }
        mutableState.value = newState
    }

    suspend fun setLanguage(language: String?) = writeMutex.withLock {
        val normalized = normalizeWearLanguageTag(language)
        require(language == null || normalized != null) { "Invalid language tag: $language" }
        if (usesFrameworkLocales) {
            val locales = listOfNotNull(normalized)
            if (backend.getApplicationLocales() != locales) {
                backend.setApplicationLocales(locales)
            }
            // A user choice also completes any pending migration.
            backend.completeMigration()
        } else if (legacyLanguage() != normalized) {
            backend.setLegacyLanguage(normalized)
        }
        refresh()
    }

    private fun legacyLanguage(): String? = normalizeWearLanguageTag(backend.getLegacyLanguage())

    private fun readState(): WearLocaleState {
        val requestedLocales = if (usesFrameworkLocales) {
            backend.getApplicationLocales()
        } else {
            listOfNotNull(legacyLanguage())
        }
        return WearLocaleState(
            language = requestedLocales.firstOrNull(),
            locales = (requestedLocales + backend.getSystemLocales()).distinct(),
        )
    }
}

internal fun normalizeWearLanguageTag(tag: String?): String? = tag
    ?.takeIf { it.isNotBlank() }
    ?.let {
        runCatching { Locale.Builder().setLanguageTag(it).build() }
            .getOrNull()
            ?.takeIf { locale -> locale.language.isNotEmpty() }
            ?.toLanguageTag()
    }
