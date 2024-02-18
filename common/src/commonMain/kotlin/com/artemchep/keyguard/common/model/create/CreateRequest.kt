package com.artemchep.keyguard.common.model.create

import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.platform.LeUri
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.Instant

@optics
data class CreateRequest(
    val ownership: Ownership? = null,
    val ownership2: Ownership2? = null,
    val merge: Merge? = null,
    val title: String? = null,
    val note: String? = null,
    val favorite: Boolean? = null,
    val reprompt: Boolean? = null,
    val uris: PersistentList<DSecret.Uri> = persistentListOf(),
    val fido2Credentials: PersistentList<DSecret.Login.Fido2Credentials> = persistentListOf(),
    val fields: PersistentList<DSecret.Field> = persistentListOf(),
    val attachments: PersistentList<Attachment> = persistentListOf(),
    // types
    val type: DSecret.Type? = null,
    val login: Login = Login(),
    val card: Card = Card(),
    val identity: Identity = Identity(),
    // other
    val now: Instant,
) {
    companion object;

    @optics
    data class Ownership(
        val accountId: String?,
        val folderId: String?,
        val organizationId: String?,
        val collectionIds: Set<String>,
    ) {
        companion object;
    }

    @optics
    data class Ownership2(
        val accountId: String?,
        val folder: FolderInfo,
        val organizationId: String?,
        val collectionIds: Set<String>,
    ) {
        companion object;
    }

    @optics
    data class Merge(
        val ciphers: List<DSecret>,
        val removeOrigin: Boolean,
    ) {
        companion object;
    }

    @optics
    sealed interface Attachment {
        companion object;

        val id: String

        @optics
        data class Remote(
            override val id: String,
            val name: String,
        ) : Attachment {
            companion object
        }

        @optics
        data class Local(
            override val id: String,
            val uri: LeUri,
            val name: String,
            val size: Long? = null,
        ) : Attachment {
            companion object
        }
    }

    @optics
    data class Login(
        val username: String? = null,
        val password: String? = null,
        val totp: String? = null,
    ) {
        companion object;
    }

    @optics
    data class Card(
        val cardholderName: String? = null,
        val brand: String? = null,
        val number: String? = null,
        val fromMonth: String? = null,
        val fromYear: String? = null,
        val expMonth: String? = null,
        val expYear: String? = null,
        val code: String? = null,
    ) {
        companion object;
    }

    @optics
    data class Identity(
        val title: String? = null,
        val firstName: String? = null,
        val middleName: String? = null,
        val lastName: String? = null,
        val address1: String? = null,
        val address2: String? = null,
        val address3: String? = null,
        val city: String? = null,
        val state: String? = null,
        val postalCode: String? = null,
        val country: String? = null,
        val company: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val ssn: String? = null,
        val username: String? = null,
        val passportNumber: String? = null,
        val licenseNumber: String? = null,
    ) {
        companion object;
    }
}
