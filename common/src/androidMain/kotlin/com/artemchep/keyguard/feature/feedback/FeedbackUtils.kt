package com.artemchep.keyguard.feature.feedback

import android.os.Build
import com.artemchep.keyguard.build.BuildKonfig

actual fun getFeedbackSubject(): String = kotlin.run {
    val date = BuildKonfig.buildDate
    val sdk = Build.VERSION.SDK_INT
    "Feedback Android/$date/$sdk"
}
