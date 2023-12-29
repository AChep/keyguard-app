package com.artemchep.keyguard.feature.decorator

import com.artemchep.keyguard.common.usecase.DateFormatter
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.monthsUntil

interface ItemDecorator<out Generic, in Value> {
    fun getOrNull(item: Value): Generic?
}

object ItemDecoratorNone : ItemDecorator<Nothing, Any> {
    override fun getOrNull(item: Any) = null
}

class ItemDecoratorDate<Generic, Value>(
    private val dateFormatter: DateFormatter,
    private val selector: (Value) -> Instant?,
    private val factory: (String, String) -> Generic,
) : ItemDecorator<Generic, Value> {
    private val past = Instant.fromEpochMilliseconds(0L)

    /**
     * Last shown month, used to not repeat the sections
     * if it stays the same.
     */
    private var lastMonths: Int? = null

    override fun getOrNull(item: Value): Generic? {
        val instant = selector(item) ?: return null
        val months = instant
            .monthsUntil(past, TimeZone.UTC)
        if (months == lastMonths) {
            return null
        }

        lastMonths = months

        val text = dateFormatter.formatDateShort(instant)
        return factory(
            "decorator.date.$months",
            text,
        )
    }
}

class ItemDecoratorTitle<Generic, Value>(
    private val selector: (Value) -> String?,
    private val factory: (String, String) -> Generic,
) : ItemDecorator<Generic, Value> {
    /**
     * Last shown character, used to not repeat the sections
     * if it stays the same.
     */
    private var lastChar: Char? = null

    override fun getOrNull(item: Value): Generic? {
        val char = selector(item)
            ?.firstOrNull()
            ?.uppercaseChar()
            // Replace all non letter symbols with a common
            // character.
            ?.takeIf { it.isLetter() }
            ?: '#'
        if (char == lastChar) {
            return null
        }

        lastChar = char
        return factory(
            "decorator.title.$char",
            char.toString(),
        )
    }
}
