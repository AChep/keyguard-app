package app.keemobile.kotpass.extensions

import app.keemobile.kotpass.xml.enterDocumentRoot
import app.keemobile.kotpass.xml.xmlReader
import nl.adaptivity.xmlutil.XmlReader

/**
 * Returns a streaming reader positioned at the document's root element,
 * ready to be passed to the `unmarshal*` functions.
 */
internal fun String.parseAsXmlReader(): XmlReader = xmlReader(this)
    .apply { enterDocumentRoot() }
