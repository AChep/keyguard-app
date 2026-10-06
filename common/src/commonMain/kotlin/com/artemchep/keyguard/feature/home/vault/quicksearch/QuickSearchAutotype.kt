package com.artemchep.keyguard.feature.home.vault.quicksearch

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.autotype.AutotypeLogin
import com.artemchep.keyguard.common.usecase.GetTotpCode
import kotlinx.coroutines.flow.firstOrNull

/** Resolved by the desktop operation after it claims the original destination. */
typealias QuickSearchAutotypePayload = suspend () -> AutotypeLogin?

internal enum class QuickSearchAutotypeField {
    Username,
    Password,
    OneTimeCode,
    ;

    /** The number key that chooses the field while the menu is open. */
    val number: Int
        get() = ordinal + 1
}

internal fun quickSearchAutotypeFields(secret: DSecret): List<QuickSearchAutotypeField> {
    if (secret.reprompt || secret.type != DSecret.Type.Login) return emptyList()
    return buildList {
        if (!secret.login?.username.isNullOrEmpty()) add(QuickSearchAutotypeField.Username)
        if (!secret.login?.password.isNullOrEmpty()) add(QuickSearchAutotypeField.Password)
        if (secret.login?.totp != null) add(QuickSearchAutotypeField.OneTimeCode)
    }
}

internal fun quickSearchAutotypePayload(
    secret: DSecret,
    field: QuickSearchAutotypeField,
    getTotpCode: GetTotpCode,
): QuickSearchAutotypePayload? {
    if (field !in quickSearchAutotypeFields(secret)) return null
    return {
        when (field) {
            QuickSearchAutotypeField.Username -> AutotypeLogin(secret.login!!.username!!, "")
            QuickSearchAutotypeField.Password -> AutotypeLogin("", secret.login!!.password!!)
            QuickSearchAutotypeField.OneTimeCode -> getTotpCode(secret.login!!.totp!!.token)
                .firstOrNull()
                ?.getOrNull()
                ?.code
                ?.let { AutotypeLogin("", it) }
        }
    }
}

internal fun quickSearchAutotypeLogin(secret: DSecret): AutotypeLogin? {
    val fields = quickSearchAutotypeFields(secret)
    val hasCredentials = QuickSearchAutotypeField.Username in fields ||
        QuickSearchAutotypeField.Password in fields
    if (!hasCredentials) return null
    return AutotypeLogin(
        username = secret.login?.username.orEmpty(),
        password = secret.login?.password.orEmpty(),
    )
}
