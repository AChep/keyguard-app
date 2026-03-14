package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@optics
data class BitwardenSend(
    /**
     * Id of the Bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val sendId: String,
    val accessId: String,
    val revisionDate: Instant,
    val createdDate: Instant? = null,
    val deletedDate: Instant? = null,
    val expirationDate: Instant? = null,
    // service fields
    override val service: BitwardenService,
    // common
    val authType: AuthType? = null, // null means unknown
    val keyBase64: String? = null,
    val name: String?,
    val notes: String?,
    val accessCount: Int,
    val maxAccessCount: Int? = null,
    val password: String? = null,
    val disabled: Boolean = false,
    val hideEmail: Boolean? = null,
    val emails: List<String> = emptyList(),
    // types
    val type: Type = Type.None,
    val file: File? = null,
    val text: Text? = null,
    // changes
    val changes: Changes? = null,
) : BitwardenService.Has<BitwardenSend> {
    companion object;

    val authTypeOrInferred: AuthType by lazy {
        authType
            ?: when {
                // 1. We have a new change that adds a password, this always takes a precedence
                // over everything else.
                changes?.passwordBase64
                    ?.let { it is BitwardenOptionalStringNullable.Some && it.value != null } == true -> AuthType.Password

                // 2. We have emails set.
                emails.isNotEmpty() -> AuthType.Email

                // 3. We have old password set.
                password != null -> AuthType.Password

                // 4. Resort to no auth.
                else -> AuthType.None
            }
    }

    override fun withService(service: BitwardenService) = copy(service = service)

    @Serializable
    enum class Type {
        None,
        File,
        Text,
    }

    @Serializable
    enum class AuthType {
        Email,
        Password,
        None,
    }

    @optics
    @Serializable
    @SerialName("changes")
    data class Changes(
        val passwordBase64: BitwardenOptionalStringNullable = BitwardenOptionalStringNullable.None,
    ) {
        companion object;
    }

    //
    // Types
    //

    @optics
    @Serializable
    data class File(
        val id: String,
        val fileName: String,
        val keyBase64: String? = null,
        val size: Long? = null,
        val sizeName: String? = null,
    ) {
        companion object;
    }

    @optics
    @Serializable
    data class Text(
        val text: String? = null,
        val hidden: Boolean? = null,
    ) {
        companion object;
    }
}
