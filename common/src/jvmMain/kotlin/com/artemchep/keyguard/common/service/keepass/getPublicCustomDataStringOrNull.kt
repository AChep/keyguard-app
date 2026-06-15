package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.VariantItem

/**
 * Gets the public custom data string from a header, if the database
 * header supports this feature.
 */
fun DatabaseHeader.getPublicCustomDataStringOrNull(key: String) =
    when (this) {
        is DatabaseHeader.Ver4x -> {
            val data = publicCustomData[key] as? VariantItem.StringUtf8
            data?.value
        }

        is DatabaseHeader.Ver3x -> null
    }

fun DatabaseHeader.getVersionString() = kotlin.run {
    val major = version.major
    val minor = version.minor
    "$major.$minor"
}
