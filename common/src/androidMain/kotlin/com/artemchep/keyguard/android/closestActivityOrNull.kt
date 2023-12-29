package com.artemchep.keyguard.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

val Context.closestActivityOrNull: Activity?
    get() = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.closestActivityOrNull
        else -> null
    }
