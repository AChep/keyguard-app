package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeePassCredentialsTest {
    private val keyData = "key-file".encodeToByteArray()

    @Test
    fun `null passphrase with key data omits the password component`() {
        val credentials = createKeePassCredentials(
            passphrase = null,
            keyData = keyData,
        )
        assertNull(credentials.passphrase)
        assertNotNull(credentials.key)

        // Must open a database created with the key
        // file alone by the kotpass library itself.
        val encoded = encodedDatabase(Credentials.from(keyData))
        KeePassDatabase.decode(
            Buffer().apply { write(encoded) },
            credentials,
        )

        // A zero-length password is a different key than no password.
        assertFails {
            KeePassDatabase.decode(
                Buffer().apply { write(encoded) },
                Credentials.from(EncryptedValue.fromString(""), keyData),
            )
        }
    }

    @Test
    fun `empty password maps to null passphrase`() {
        assertNull("".toPassphraseOrNull())
        assertNotNull("secret".toPassphraseOrNull())
        assertNull(
            KeePassToken.Key(
                passwordBase64 = "",
                keyBase64 = null,
            ).toPassphraseOrNull(),
        )
    }

    @Test
    fun `no password and no key data is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            createKeePassCredentials(
                passphrase = null,
                keyData = null,
            )
        }
    }

    private fun encodedDatabase(credentials: Credentials): ByteArray {
        val database = KeePassDatabase.Ver4x
            .create(
                rootName = "Root",
                meta = Meta(name = "Key file database"),
                credentials = credentials,
            )
            .let { database ->
                database.copy(
                    header = database.header.copy(
                        kdfParameters = KdfParameters.Aes(
                            rounds = 1U,
                            seed = ByteArray(32) { it.toByte() }.toByteString(),
                        ),
                    ),
                )
            }
        return Buffer()
            .also(database::encodeTo)
            .readByteArray()
    }
}
