package com.artemchep.keyguard.util.xml

/** An element name resolved against its in-scope namespace declarations. */
data class XmlName(
    val namespace: String?,
    val local: String,
)
