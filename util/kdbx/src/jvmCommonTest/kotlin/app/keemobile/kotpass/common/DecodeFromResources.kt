package app.keemobile.kotpass.common

import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser

internal fun decodeFromResources(
    path: String,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser
) = ClassLoader
    .getSystemResourceAsStream(path)!!
    .use { inputStream ->
        KeePassDatabase.decode(
            inputStream = inputStream,
            credentials = credentials,
            validateHashes = validateHashes,
            contentParser = contentParser
        )
    }
