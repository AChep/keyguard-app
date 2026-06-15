package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.feature.datepicker.DatePickerResult
import kotlinx.datetime.number

internal fun DatePickerResult.Confirm.toMonthAndYearStrings(): Pair<String, String> = Pair(
    first = month.number.toString().padStart(2, '0'),
    second = year.toString(),
)
