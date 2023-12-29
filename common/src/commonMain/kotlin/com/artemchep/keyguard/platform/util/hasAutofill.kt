package com.artemchep.keyguard.platform.util

import com.artemchep.keyguard.platform.Platform

fun Platform.hasAutofill(): Boolean =
    this is Platform.Mobile.Android &&
            !this.isChromebook

fun Platform.hasSubscription(): Boolean =
    this is Platform.Mobile.Android
