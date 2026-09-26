package com.artemchep.keyguard.copy

import kotlin.concurrent.Volatile
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

/**
 * Holds a value built for the current locale, such as a Foundation formatter
 * that is expensive to create. The value is rebuilt when the locale changes.
 */
internal class LocaleCache<T : Any>(
    private val localeProvider: () -> NSLocale = { NSLocale.currentLocale },
    private val factory: (NSLocale) -> T,
) {
    private class Entry<T>(
        val localeIdentifier: String,
        val value: T,
    )

    @Volatile
    private var entry: Entry<T>? = null

    fun get(): T {
        val locale = localeProvider()
        val localeIdentifier = locale.localeIdentifier
        entry
            ?.takeIf { it.localeIdentifier == localeIdentifier }
            ?.let { return it.value }
        return factory(locale)
            .also { entry = Entry(localeIdentifier, it) }
    }
}
