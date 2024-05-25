package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationInteger {
    OK,
    ERROR_EMPTY,
    ERROR_INVALID,
    ;

    object Const {
        /** The minimum length of the number */
        const val MIN_LENGTH = 1
    }
}

fun validateInteger(
    text: String?,
    minLength: Int = ValidationInteger.Const.MIN_LENGTH,
): ValidationInteger {
    val length = text?.length ?: 0
    return if (length < minLength) {
        ValidationInteger.ERROR_EMPTY
    } else if (text?.toIntOrNull() == null) {
        ValidationInteger.ERROR_INVALID
    } else {
        ValidationInteger.OK
    }
}

suspend fun ValidationInteger.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationInteger.ERROR_EMPTY -> scope.translate(Res.string.error_must_not_be_blank)
        ValidationInteger.ERROR_INVALID -> scope.translate(Res.string.error_must_be_number)
        else -> null
    }

fun Flow<String>.validatedInteger(
    scope: TranslatorScope,
    minLength: Int = ValidationInteger.Const.MIN_LENGTH,
) = this
    .map { rawText ->
        val text = rawText
            .trim()
        val textError = validateInteger(
            text = text,
            minLength = minLength,
        )
            .format(scope)
        if (textError != null) {
            Validated.Failure(
                model = text,
                error = textError,
            )
        } else {
            Validated.Success(
                model = text,
            )
        }
    }
