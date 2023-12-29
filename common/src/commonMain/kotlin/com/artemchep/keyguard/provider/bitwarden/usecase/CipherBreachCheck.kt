package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherBreachCheckImpl(
    private val cipherUrlCheck: CipherUrlCheck,
) : CipherBreachCheck {
    // https://haveibeenpwned.com/api/v3/dataclasses
    private val sensitiveDataClass = setOf(
        "Passwords",
        // Other. These are not necessarily require password change, but
        // are serious enough that there might have been an unknown breach
        // that actually leaked the passwords.
        "Auth tokens",
        "Bank account numbers",
        "Biometric data",
        "Bios",
        "Chat logs",
        "Credit card CVV",
        "Credit cards",
        "Encrypted keys",
        "Historical passwords",
        "MAC addresses",
        "Payment histories",
        "Payment methods",
        "Personal health data",
        "PINs",
        "Private messages",
        "Purchases",
        "Security questions and answers",
    )

    constructor(directDI: DirectDI) : this(
        cipherUrlCheck = directDI.instance(),
    )

    override fun invoke(
        cipher: DSecret,
        breaches: HibpBreachGroup,
    ): IO<Boolean> = ioEffect {
        val login = cipher.login
            ?: return@ioEffect false
        val revision = (login.passwordRevisionDate ?: cipher.revisionDate)
            .toLocalDateTime(TimeZone.UTC)
            .date

        val validBreaches = breaches.breaches
            .filter { breach ->
                val bd = breach.breachDate
                    ?: breach.addedDate
                    ?: return@filter false
                bd >= revision
            }
            // We show only accounts that have leaked the sensitive data
            // in some form. For these web-sites you should change the
            // password.
            .filter { breach ->
                breach.dataClasses
                    .any { dataClass ->
                        dataClass in sensitiveDataClass
                    }
            }
            .filter { breach ->
                val domain = breach.domain
                    ?.takeIf { it.isNotBlank() }
                    ?: return@filter false
                cipher.uris.any { uri ->
                    cipherUrlCheck(uri, domain)
                        .bind()
                }
            }
        validBreaches
            .isNotEmpty()
    }

}
