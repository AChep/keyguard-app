package com.artemchep.keyguard.android.util

import android.content.Intent

fun Intent.putExtraUnlessBlank(
    key: String,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    putExtra(key, value)
}
