package app.keemobile.kotpass.extensions

import kotlin.uuid.Uuid

@PublishedApi
internal fun Uuid.isZero() = this == Uuid.NIL

@PublishedApi
internal fun Uuid?.isNullOrZero() = this?.isZero() ?: true
