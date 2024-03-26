package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.datetime.Instant
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
    val keyBase64: String? = null,
    val name: String?,
    val notes: String?,
    val accessCount: Int,
    val maxAccessCount: Int? = null,
    val password: String? = null,
    val disabled: Boolean = false,
    val hideEmail: Boolean? = null,
    // types
    val type: Type = Type.None,
    val file: File? = null,
    val text: Text? = null,
    // changes
    val changes: Changes? = null,
) : BitwardenService.Has<BitwardenSend> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)

    @Serializable
    enum class Type {
        None,
        File,
        Text,
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
