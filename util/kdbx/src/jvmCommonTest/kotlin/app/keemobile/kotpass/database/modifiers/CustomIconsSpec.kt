package app.keemobile.kotpass.database.modifiers

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.traverse
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.common.runKotpassSpec
import kotlin.test.Test
import app.keemobile.kotpass.common.matchers.shouldBe
import kotlin.uuid.Uuid

class CustomIconsSpec {
    @Test
    fun portedKotpassSpec() = runKotpassSpec {

    describe("CustomIcons modifier") {
        it("Properly cleans up invalid references to custom icons") {
            val uuid = Uuid.random()
            val customIcons = mapOf(
                uuid to CustomIcon(byteArrayOf(0x1), null, null)
            )
            val database = KeePassDatabase.decode(
                ClassLoader.getSystemResourceAsStream("ver4_argon2.kdbx")!!,
                Credentials.from(EncryptedValue.fromString("1"))
            ).modifyCustomIcons {
                customIcons
            }.modifyEntries {
                copy(customIconUuid = uuid)
            }.modifyGroups {
                copy(customIconUuid = uuid)
            }
            database.traverse { element ->
                element.customIconUuid shouldBe uuid
            }
            val noCustomIcons = database.modifyCustomIcons { mapOf() }

            noCustomIcons.traverse { element ->
                element.customIconUuid shouldBe null
            }
        }
    }
    }
}
