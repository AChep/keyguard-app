package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.sequences.forEach
import kotlin.time.Instant

internal fun List<BitwardenCipher.Login.PasswordHistory>.withPasswordChange(
    previousPassword: String?,
    nextPassword: String?,
    at: Instant,
): List<BitwardenCipher.Login.PasswordHistory> {
    if (previousPassword == nextPassword || previousPassword == null) {
        return this
    }

    return this + BitwardenCipher.Login.PasswordHistory(
        password = previousPassword,
        lastUsedDate = at,
    )
}

/**
 * @return [BitwardenCipher] with updated password history, returns `null` if the
 * password history did not need any changes.
 */
internal fun BitwardenCipher.with3WayMergePasswordHistoryOrNull(
    at: Instant,
    vararg others: BitwardenCipher,
): BitwardenCipher? {
    val history = passwordHistory
        .toMutableList()
    var changed = false

    fun append(
        value: String?,
        topic: String? = null,
    ) {
        value
            ?.takeUnless { it.isBlank() }
            ?: return

        val entry = topic?.let { "$it: " }.orEmpty() + value
        val exists = history
            .any { it.password == entry }
        if (exists) {
            // Do not add duplicate entries
            return
        }

        history += BitwardenCipher.Login.PasswordHistory(
            password = entry,
            lastUsedDate = at,
        )
        changed = true
    }

    others.asSequence()
        .flatMap { it.passwordHistory }
        .forEach { historyEntry ->
            val exists = history
                .any { it.id == historyEntry.id }
            if (exists) {
                // Do not add duplicate entries
                return@forEach
            }

            history += historyEntry
            changed = true
        }

    val newPassword = login?.password
        ?.takeIf { it.isNotEmpty() }
    others.asSequence()
        .mapNotNull { it.login?.password }
        .distinct()
        .filter { oldPassword ->
            oldPassword != newPassword &&
                    oldPassword.isNotEmpty()
        }
        .forEach { oldPassword ->
            append(
                value = oldPassword,
            )
        }

    val newTotp = login?.totp
        ?.takeIf { it.isNotEmpty() }
    others.asSequence()
        .mapNotNull { it.login?.totp }
        .distinct()
        .filter { oldToken ->
            oldToken != newTotp &&
                    oldToken.isNotEmpty()
        }
        .forEach { oldToken ->
            append(
                value = oldToken,
                topic = "totp",
            )
        }

    fun BitwardenCipher.Field.shouldBeTracked() =
        this.type == BitwardenCipher.Field.Type.Hidden &&
                !this.value.isNullOrBlank()

    val oldFieldsNoDuplicates = kotlin.run {
        val originCiphersFields = others
            .asSequence()
            .flatMap { it.fields }
        val oldFields = fields
        sequence {
            yieldAll(originCiphersFields)
            yieldAll(oldFields)
        }
            .filter { oldField ->
                oldField.shouldBeTracked()
            }
            .distinct()
    }
    oldFieldsNoDuplicates.forEach { oldField ->
        val shouldBeTracked = fields
            .none { oldField == it }
        if (shouldBeTracked) {
            append(
                value = oldField.value.orEmpty(),
                topic = oldField.name.orEmpty(),
            )
        }
    }

    if (!changed) {
        return null
    }
    return copy(passwordHistory = history)
}
