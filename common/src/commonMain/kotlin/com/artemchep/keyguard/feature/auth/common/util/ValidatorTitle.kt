package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationTitle {
    OK,
    ERROR_EMPTY,
}

fun validateTitle(title: String?): ValidationTitle {
    return if (title.isNullOrBlank()) {
        ValidationTitle.ERROR_EMPTY
    } else {
        ValidationTitle.OK
    }
}

fun ValidationTitle.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationTitle.ERROR_EMPTY -> scope.translate(Res.strings.error_must_not_be_blank)
        else -> null
    }

fun Flow<String>.validatedTitle(scope: TranslatorScope) = this
    .map { rawTitle ->
        val title = rawTitle
            .trim()
        val titleError = validateTitle(title)
            .format(scope)
        if (titleError != null) {
            Validated.Failure(
                model = title,
                error = titleError,
            )
        } else {
            Validated.Success(
                model = title,
            )
        }
    }
