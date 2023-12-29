package com.artemchep.keyguard.feature.home.vault.add.attachment

import com.artemchep.keyguard.platform.LeUri
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize

sealed interface SkeletonAttachment {
    sealed interface Identity : LeParcelable

    val identity: Identity
    val name: String
    val size: String

    data class Remote(
        override val identity: Identity,
        override val name: String,
        override val size: String,
    ) : SkeletonAttachment {
        @LeParcelize
        data class Identity(
            val id: String,
        ) : SkeletonAttachment.Identity, LeParcelable
    }

    data class Local(
        override val identity: Identity,
        override val name: String,
        override val size: String,
    ) : SkeletonAttachment {
        @LeParcelize
        data class Identity(
            val id: String,
            val uri: LeUri,
            val size: Long? = null,
        ) : SkeletonAttachment.Identity, LeParcelable
    }
}
