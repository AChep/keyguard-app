package com.artemchep.keyguard.android

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PasswordProviderGetActivityArgs(
    val accountId: String,
    val cipherId: String,
    val id: String,
    val requiresUserVerification: Boolean,
    val userVerified: Boolean,
) : Parcelable
