package com.artemchep.keyguard.feature.feedback

import com.artemchep.keyguard.build.BuildKonfig

actual fun getFeedbackSubject(): String = run {
    val date = BuildKonfig.buildDate
    "Feedback Desktop/$date"
}
