package com.artemchep.keyguard.android

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PasskeyProviderGetActivityArgs(
    val accountId: String,
    val cipherId: String,
    val credId: String,
    val cipherName: String,
    val credRpId: String,
    val credUserDisplayName: String,
    val requiresUserVerification: Boolean,
    val userVerified: Boolean,
) : Parcelable
