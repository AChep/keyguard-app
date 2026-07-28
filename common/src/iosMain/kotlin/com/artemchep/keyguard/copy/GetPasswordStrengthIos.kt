package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.usecase.GetPasswordStrength

object GetPasswordStrengthIos : GetPasswordStrength {
    override fun invoke(password: String): IO<PasswordStrength> = io(
        PasswordStrength(
            crackTimeSeconds = when {
                password.length < 8 -> 1_000L
                password.length < 12 -> 100_000_000L
                else -> 1_000_000_000_000L
            },
            version = 0L,
        ),
    )
}
