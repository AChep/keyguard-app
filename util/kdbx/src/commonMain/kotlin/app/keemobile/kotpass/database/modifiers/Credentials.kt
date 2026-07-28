package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Meta
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * Modifies [Credentials] field in [KeePassDatabase] with result of [block] lambda.
 * If new [Credentials.passphrase] supplied modifies [Meta.masterKeyChanged] field.
 */
inline fun KeePassDatabase.modifyCredentials(
    crossinline block: Credentials.() -> Credentials
): KeePassDatabase {
    val newCredentials = block(credentials)
    val isModified = !credentials.passphrase?.getHash()
        .contentEquals(newCredentials.passphrase?.getHash())
    val newDatabase = when (this) {
        is KeePassDatabase.Ver3x -> copy(credentials = newCredentials)
        is KeePassDatabase.Ver4x -> copy(credentials = newCredentials)
    }

    return if (isModified) {
        newDatabase.modifyMeta {
            copy(masterKeyChanged = Clock.System.now())
        }
    } else {
        newDatabase
    }
}
