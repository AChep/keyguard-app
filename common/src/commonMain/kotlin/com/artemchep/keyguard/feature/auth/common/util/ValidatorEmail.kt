package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationEmail {
    OK,
    ERROR_EMPTY,
    ERROR_INVALID,
}

val REGEX_EMAIL = (
        "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
                "\\@" +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
                "(" +
                "\\." +
                "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
                ")+"
        ).toRegex()

fun validateEmail(email: String?): ValidationEmail {
    return if (email.isNullOrBlank()) {
        ValidationEmail.ERROR_EMPTY
    } else if (!REGEX_EMAIL.matches(email)) {
        ValidationEmail.ERROR_INVALID
    } else {
        ValidationEmail.OK
    }
}

/**
 * @return human-readable variant of [validateEmail] result, or `null` if
 * there's no validation error.
 */
suspend fun ValidationEmail.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationEmail.ERROR_EMPTY -> scope.translate(Res.string.error_must_not_be_blank)
        ValidationEmail.ERROR_INVALID -> scope.translate(Res.string.error_invalid_email)
        else -> null
    }

fun Flow<String>.validatedEmail(scope: TranslatorScope) = this
    .map { rawEmail ->
        val email = rawEmail
            .trim()
        val emailError = validateEmail(email)
            .format(scope)
        if (emailError != null) {
            Validated.Failure(
                model = email,
                error = emailError,
            )
        } else {
            Validated.Success(
                model = email,
            )
        }
    }
