package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import org.kodein.di.DirectDI

/**
 * @author Artem Chepurnyi
 */
class CipherIncompleteCheckImpl() : CipherIncompleteCheck {
    private val placeholderNames = setOf(
        "login",
        "email",
        "password",
        "username",
        "key",
        "item",
        "todo",
        "entry",
        "identity",
    )

    private val tokenNames = setOf(
        "ssh",
        "key",
        "token",
        "api",
        "security",
        "password",
        "gpg",
        "signature",
        "private",
        "public",
        "id",
    )

    constructor(directDI: DirectDI) : this()

    override fun invoke(secret: DSecret): Boolean {
        if (secret.name.isBlank()) {
            return true
        }
        val lowercaseName = secret.name.lowercase()
        if (lowercaseName in placeholderNames) {
            return true
        }

        // API has limited subset of item types, so you might create an
        // item just to add attachments/custom fields to it.
        if (
            secret.fields.isNotEmpty() ||
            secret.attachments.isNotEmpty()
        ) {
            return false
        }

        return when (secret.type) {
            DSecret.Type.Login -> incompleteLogin(secret)
            DSecret.Type.Card -> incompleteCard(secret)
            DSecret.Type.Identity -> incompleteIdentity(secret)
            DSecret.Type.SecureNote -> incompleteNote(secret)
            DSecret.Type.SshKey -> incompleteSshKey(secret)
            DSecret.Type.None -> false
        }
    }

    private fun incompleteLogin(secret: DSecret): Boolean {
        val login = secret.login ?: return true
        // Passkey contains both password and
        // a username (and a website too).
        if (login.fido2Credentials.isNotEmpty()) {
            return false
        }
        if (login.username.isNullOrBlank()) {
            // Every login should have a username, otherwise how do you know
            // which user to login with?
            val lowercaseNameByWords = secret.name.lowercase().split(" ")
            if (lowercaseNameByWords.any { it in tokenNames }) {
                // Token items tend to have no username.
                return false
            }
            return true
        }
        return false
    }

    private fun incompleteIdentity(secret: DSecret): Boolean {
        val identity = secret.identity ?: return true
        kotlin.run {
            if (
                identity.firstName.isNullOrBlank() &&
                identity.lastName.isNullOrBlank()
            ) {
                // Every identity should have a person name, otherwise how do you know
                // which person it is?
                // #1: If the entry name contains a space, then there's a chance
                //  that the entry name itself is a person's name.
                if (" " in secret.name) return@run
                return true
            }
        }
        return false
    }

    private fun incompleteNote(secret: DSecret): Boolean {
        return secret.notes.isBlank()
    }

    private fun incompleteCard(secret: DSecret): Boolean {
        val card = secret.card ?: return true
        return card.number.isNullOrBlank()
    }

    private fun incompleteSshKey(secret: DSecret): Boolean {
        val sshKey = secret.sshKey ?: return true
        return sshKey.privateKey.isNullOrBlank() ||
                sshKey.publicKey.isNullOrBlank() ||
                sshKey.fingerprint.isNullOrBlank()
    }
}
