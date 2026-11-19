package com.artemchep.keyguard.platform.util

import com.artemchep.keyguard.platform.Platform

fun Platform.hasAutofill(): Boolean = when (this) {
    is Platform.Mobile.Android -> !isChromebook && !isWatch
    is Platform.Mobile.Ios -> true
    Platform.Desktop.MacOS.Native -> true
    // Browser extension, plus the passkey provider on Windows.
    is Platform.Desktop.Linux,
    Platform.Desktop.MacOS.Jvm,
    Platform.Desktop.Windows,
    -> true
    else -> false
}

fun Platform.hasWatch(): Boolean =
    this is Platform.Mobile.Android &&
            this.isWatch

fun Platform.hasBrowser(): Boolean =
    !hasWatch()

fun Platform.hasSubscription(): Boolean =
    this is Platform.Mobile.Android ||
            this is Platform.Mobile.Ios

/** Premium is sold on the website instead of a store. */
fun Platform.hasWebPurchase(): Boolean =
    this is Platform.Desktop &&
            this !is Platform.Apple

fun Platform.hasDynamicShortcuts(): Boolean =
    this is Platform.Mobile.Android
