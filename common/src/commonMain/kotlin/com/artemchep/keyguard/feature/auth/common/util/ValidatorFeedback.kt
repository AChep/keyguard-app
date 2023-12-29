package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ValidationFeedback {
    OK,
    ERROR_EMPTY,
}

fun validateFeedback(feedback: String?): ValidationFeedback {
    return if (feedback.isNullOrBlank()) {
        ValidationFeedback.ERROR_EMPTY
    } else {
        ValidationFeedback.OK
    }
}

/**
 * @return human-readable variant of [validateFeedback] result, or `null` if
 * there's no validation error.
 */
fun ValidationFeedback.format(scope: TranslatorScope): String? =
    when (this) {
        ValidationFeedback.ERROR_EMPTY -> scope.translate(Res.strings.error_must_not_be_blank)
        else -> null
    }

fun Flow<String>.validatedFeedback(scope: TranslatorScope) = this
    .map { rawFeedback ->
        val feedback = rawFeedback
            .trim(' ')
        val feedbackError = validateFeedback(feedback)
            .format(scope)
        if (feedbackError != null) {
            Validated.Failure(
                model = feedback,
                error = feedbackError,
            )
        } else {
            Validated.Success(
                model = feedback,
            )
        }
    }
