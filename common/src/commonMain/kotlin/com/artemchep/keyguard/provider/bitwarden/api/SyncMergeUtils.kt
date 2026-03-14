package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile

suspend fun merge(
    remote: BitwardenCipher,
    local: BitwardenCipher?,
    getPasswordStrength: GetPasswordStrength,
): BitwardenCipher {
    val attachments = remote.attachments.toMutableList()
    local?.attachments?.forEachIndexed { localIndex, attachment ->
        val localAttachment = attachment as? BitwardenCipher.Attachment.Local
            ?: return@forEachIndexed

        // Skip collisions.
        val remoteIndex = attachments
            .indexOfFirst { it.id == localAttachment.id }
        if (remoteIndex >= 0) {
            // This attachment already exists on remote, so
            // we just keep it as is.
            return@forEachIndexed
        }

        val parent = local.attachments
            .getOrNull(localIndex - 1)
        val parentIndex = attachments
            .indexOfFirst { it.id == parent?.id }
        if (parentIndex >= 0) {
            attachments.add(parentIndex + 1, localAttachment)
        } else {
            if (parent != null) {
                attachments.add(localAttachment)
            } else {
                attachments.add(0, localAttachment)
            }
        }
    }

    var login = remote.login
    // Calculate or copy over the password strength of
    // the password.
    if (remote.login != null) run {
        val password = remote.login.password
            ?: return@run
        val strength = local?.login?.passwordStrength
            .takeIf { local?.login?.password == remote.login.password }
        // Generate a password strength badge.
            ?: getPasswordStrength(password)
                .attempt()
                .bind()
                .getOrNull()
                ?.let { ps ->
                    BitwardenCipher.Login.PasswordStrength(
                        password = password,
                        crackTimeSeconds = ps.crackTimeSeconds,
                        version = ps.version,
                    )
                }
        login = login?.copy(
            passwordStrength = strength,
        )
    }

    val ignoredAlerts = local?.ignoredAlerts.orEmpty()
    return remote.copy(
        login = login,
        attachments = attachments,
        ignoredAlerts = ignoredAlerts,
    )
}

suspend fun merge(
    remote: BitwardenProfile,
    local: BitwardenProfile?,
): BitwardenProfile {
    val hidden = local?.hidden == true
    return remote.copy(
        hidden = hidden,
    )
}
