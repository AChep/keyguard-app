package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts

/** The vault services used to evaluate filters, independent of the DI container. */
class CipherFilterContext(
    val cipherSshKeyWeakCheck: CipherSshKeyWeakCheck,
    val checkPasswordSetLeak: CheckPasswordSetLeak,
    val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    val cipherBreachCheck: CipherBreachCheck,
    val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    val getBreaches: GetBreaches,
    val cipherIncompleteCheck: CipherIncompleteCheck,
    val cipherExpiringCheck: CipherExpiringCheck,
    val cipherUnsecureUrlCheck: CipherUnsecureUrlCheck,
    val tldService: TldService,
    val getTwoFa: GetTwoFa,
    val getPasskeys: GetPasskeys,
    val cipherUrlDuplicateCheck: CipherUrlDuplicateCheck,
    val cipherUrlBroadCheck: CipherUrlBroadCheck,
    val getWatchtowerAlerts: GetWatchtowerAlerts,
)
