package com.artemchep.keyguard.feature.datepicker

import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatePickerStateProducerTest {
    @Test
    fun `createDatePickerResult maps month number to kotlinx month`() {
        val actual = createDatePickerResult(
            month = 7,
            year = 2032,
        )

        assertEquals(
            expected = DatePickerResult.Confirm(
                month = Month.JULY,
                year = 2032,
            ),
            actual = actual,
        )
    }

    @Test
    fun `createDatePickerResult rejects invalid month numbers`() {
        assertFailsWith<IllegalArgumentException> {
            createDatePickerResult(
                month = 13,
                year = 2032,
            )
        }
    }
}
