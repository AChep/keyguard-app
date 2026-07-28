package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.SecureRandom
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.BaseKdfProvider
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import java.io.InputStream
import java.io.OutputStream

fun KeePassDatabase.Companion.decode(
    inputStream: InputStream,
    credentials: Credentials,
    validateHashes: Boolean = true,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
): KeePassDatabase = decode(
    data = inputStream.readBytes(),
    credentials = credentials,
    validateHashes = validateHashes,
    contentParser = contentParser,
    cipherProviders = cipherProviders,
    kdfProvider = kdfProvider,
)

fun KeePassDatabase.Companion.decodeFromXml(
    inputStream: InputStream,
    credentials: Credentials,
    contentParser: XmlContentParser = DefaultXmlContentParser,
): KeePassDatabase = decodeFromXml(
    xmlData = inputStream.readBytes(),
    credentials = credentials,
    contentParser = contentParser,
)

fun KeePassDatabase.encode(
    outputStream: OutputStream,
    contentParser: XmlContentParser = DefaultXmlContentParser,
    cipherProviders: List<CipherProvider> = BaseCiphers.entries,
    kdfProvider: KdfProvider = BaseKdfProvider,
    random: SecureRandom = SecureRandom(),
) {
    outputStream.write(
        encode(
            contentParser = contentParser,
            cipherProviders = cipherProviders,
            kdfProvider = kdfProvider,
            random = random,
        ),
    )
}
