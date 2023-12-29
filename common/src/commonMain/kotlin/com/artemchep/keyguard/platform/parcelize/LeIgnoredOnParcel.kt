package com.artemchep.keyguard.platform.parcelize

/**
 * The property annotated with [LeIgnoredOnParcel]
 * will not be stored into parcel.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
expect annotation class LeIgnoredOnParcel()
