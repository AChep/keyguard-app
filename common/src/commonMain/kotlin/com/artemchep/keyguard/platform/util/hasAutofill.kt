package com.artemchep.keyguard.platform.util

import com.artemchep.keyguard.platform.Platform

fun Platform.hasAutofill(): Boolean =
    this is Platform.Mobile.Android &&
            !this.isChromebook &&
            !this.isWatch

fun Platform.hasWatch(): Boolean =
    this is Platform.Mobile.Android &&
            this.isWatch

fun Platform.hasBrowser(): Boolean =
    !hasWatch()

fun Platform.hasSubscription(): Boolean =
    this is Platform.Mobile.Android

fun Platform.hasDynamicShortcuts(): Boolean =
    this is Platform.Mobile.Android
