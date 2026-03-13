package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.feature.datepicker.DatePickerResult
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertEquals

class AddStateProducerDatePickerTest {
    @Test
    fun `date picker result is converted back to month and year input strings`() {
        val actual = DatePickerResult.Confirm(
            month = Month.JANUARY,
            year = 2035,
        ).toMonthAndYearStrings()

        assertEquals(
            expected = "01" to "2035",
            actual = actual,
        )
    }
}
