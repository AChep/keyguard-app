package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.xml.FormatXml

internal fun Boolean.toXmlString() = if (this) {
    FormatXml.Values.True
} else {
    FormatXml.Values.False
}
