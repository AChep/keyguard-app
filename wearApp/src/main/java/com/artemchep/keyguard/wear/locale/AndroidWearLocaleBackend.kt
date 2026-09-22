package com.artemchep.keyguard.wear.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.app.LocaleManagerCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidWearLocaleBackend(
    private val context: Context,
) : WearLocaleBackend {
    private val preferences = context.getSharedPreferences("wear_locale", Context.MODE_PRIVATE)
    private var legacyLanguage = preferences.getString("language", null)

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    override val usesFrameworkLocales: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(33)
    private fun localeManager(): LocaleManager =
        requireNotNull(context.getSystemService(LocaleManager::class.java))

    override fun getApplicationLocales(): List<String> =
        if (usesFrameworkLocales) {
            val locales = localeManager().applicationLocales
            List(locales.size()) { locales[it].toLanguageTag() }
        } else {
            emptyList()
        }

    override fun setApplicationLocales(locales: List<String>) {
        if (usesFrameworkLocales) {
            localeManager().applicationLocales = locales.toAndroidLocaleList()
        } else {
            error("Framework app locales require Android 13")
        }
    }

    override fun getSystemLocales(): List<String> {
        val locales = LocaleManagerCompat.getSystemLocales(context)
        return (0 until locales.size()).mapNotNull { locales[it]?.toLanguageTag() }
    }

    override fun getLegacyLanguage(): String? = legacyLanguage

    override suspend fun setLegacyLanguage(language: String?) {
        withContext(Dispatchers.IO) {
            if (!preferences.edit().putString("language", language).commit()) {
                throw IOException("Could not save the app language")
            }
        }
        // SharedPreferences changes its in-memory value even if commit fails. Only expose
        // a new selection after it has been saved successfully.
        legacyLanguage = language
    }

    override fun isMigrationComplete(): Boolean = preferences.getBoolean("framework_migrated", false)

    override fun completeMigration() {
        preferences.edit().putBoolean("framework_migrated", true).apply()
    }

    override fun applyLegacyLocales(locales: List<String>) {
        if (locales.isNotEmpty()) {
            LocaleList.setDefault(locales.toAndroidLocaleList())
        }
    }
}

internal fun List<String>.toAndroidLocaleList(): LocaleList =
    LocaleList.forLanguageTags(joinToString(","))

internal fun Context.withWearLocales(locales: List<String>): Context {
    // Supply only locale overrides so density, font scale and other configuration changes
    // continue to come from the base context.
    val override = Configuration().apply {
        setLocales(locales.toAndroidLocaleList())
    }
    return createConfigurationContext(override)
}
