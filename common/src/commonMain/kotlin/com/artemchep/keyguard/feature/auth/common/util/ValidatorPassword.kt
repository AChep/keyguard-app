package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationPassword {
    OK,
    ERROR_MIN_LENGTH,
    ;

    object Const {
        /** The minimum length of the password */
        const val MIN_LENGTH = 6
    }
}

fun validatePassword(password: String?): ValidationPassword {
    val passwordLength = password?.trim()?.length ?: 0
    return if (passwordLength < ValidationPassword.Const.MIN_LENGTH) {
        ValidationPassword.ERROR_MIN_LENGTH
    } else {
        ValidationPassword.OK
    }
}

fun ValidationPassword.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationPassword.ERROR_MIN_LENGTH -> scope.translate(
            res = Res.strings.error_must_have_at_least_n_symbols,
            ValidationPassword.Const.MIN_LENGTH,
        )

        else -> null
    }

fun Flow<String>.validatedPassword(scope: TranslatorScope) = this
    .map { password ->
        val passwordError = validatePassword(password)
            .format(scope)
        if (passwordError != null) {
            Validated.Failure(
                model = password,
                error = passwordError,
            )
        } else {
            Validated.Success(
                model = password,
            )
        }
    }
