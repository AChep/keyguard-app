package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.util.to6DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOptionalStringNullable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.provider.bitwarden.entity.SendAuthTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SendRequest(
    @SerialName("key")
    val key: String,
    @SerialName("type")
    val type: SendTypeEntity,
    @SerialName("authType")
    val authType: SendAuthTypeEntity? = null,
    @SerialName("name")
    val name: String,
    @SerialName("notes")
    val notes: String?,
    @SerialName("password")
    val password: String? = null,
    @SerialName("disabled")
    val disabled: Boolean,
    /**
     * `true` to hide the email of the author of the
     * send, `false` otherwise.
     */
    @SerialName("hideEmail")
    val hideEmail: Boolean,
    @SerialName("deletionDate")
    val deletionDate: Instant,
    @SerialName("expirationDate")
    val expirationDate: Instant?,
    @SerialName("maxAccessCount")
    val maxAccessCount: Int?,
    @SerialName("text")
    val text: SendTextRequest?,
    @SerialName("file")
    val file: SendFileRequest?,
    @SerialName("emails")
    val emails: String? = null,
) {
    companion object
}

@Serializable
data class SendTextRequest(
    @SerialName("text")
    val text: String,
    @SerialName("hidden")
    val hidden: Boolean,
) {
    companion object
}

@Serializable
data class SendFileRequest(
    @SerialName("fileName")
    val fileName: String? = null,
) {
    companion object
}

context(CryptoGenerator, Base64Service)
fun SendRequest.Companion.of(
    model: BitwardenSend,
    key: ByteArray,
) = kotlin.run {
    val type = SendTypeEntity.of(model.type)
    val text = model.text
        ?.let(SendTextRequest::of)
    val file = model.file
        ?.let(SendFileRequest::of)
    val deletionDate = model.deletedDate
        ?: Clock.System.now()

    val authType = model.authTypeOrInferred

    // Enforce the auth type by juggling the fields depending
    // on the type.
    val newEmails = if (authType == BitwardenSend.AuthType.Email) {
        getNewEmails(
            model = model,
        )
    } else null
    val newPassword = if (authType == BitwardenSend.AuthType.Password) {
        getNewPass(
            model = model,
            key = key,
        ) // note that it returns `null` if the password has not been changed
    } else null

    val keyBase64 = requireNotNull(model.keyBase64) { "Send request must have a cipher key!" }
    SendRequest(
        type = type,
        key = keyBase64,
        authType = authType.let(SendAuthTypeEntity.Companion::of),
        name = requireNotNull(model.name) { "Send request must have a non-null name!" },
        notes = model.notes,
        password = newPassword,
        disabled = model.disabled,
        hideEmail = model.hideEmail == true,
        deletionDate = deletionDate
            .to6DigitsNanosOfSecond(),
        expirationDate = model.expirationDate
            ?.to6DigitsNanosOfSecond(),
        maxAccessCount = model.maxAccessCount,
        text = text,
        file = file,
        emails = newEmails,
    )
}

private fun getNewEmails(
    model: BitwardenSend,
) = model.emails
    .takeUnless { it.isEmpty() }
    ?.joinToString(",")

context(CryptoGenerator, Base64Service)
private fun getNewPass(
    model: BitwardenSend,
    key: ByteArray,
) = when (val pwd = model.changes?.passwordBase64) {
    is BitwardenOptionalStringNullable.Some -> run {
        pwd.value
        // Bitwarden doesn't allow us to remove the password
        // within the PUT request.
            ?: return@run null
        val password = decode(pwd.value)
        // Send the hash code of that password, instead of
        // sending the actual password.
        val passwordHash = pbkdf2(
            seed = password,
            salt = key,
            iterations = 100_000,
        )
        encodeToString(passwordHash)
    }

    is BitwardenOptionalStringNullable.None,
    null,
        -> null
}

fun SendTextRequest.Companion.of(
    model: BitwardenSend.Text,
) = kotlin.run {
    SendTextRequest(
        text = model.text.orEmpty(),
        hidden = model.hidden == true,
    )
}

fun SendFileRequest.Companion.of(
    model: BitwardenSend.File,
) = kotlin.run {
    SendFileRequest(
        fileName = model.fileName,
    )
}
