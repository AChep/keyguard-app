package com.artemchep.keyguard.common.model

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeBiometricCipher
import org.kodein.di.DI

sealed interface VaultState {
    class Create(
        val createWithMasterPassword: WithPassword,
        val createWithMasterPasswordAndBiometric: WithBiometric?,
    ) : VaultState {
        class WithPassword(
            val getCreateIo: (String) -> IO<Unit>,
        )

        class WithBiometric(
            val getCipher: suspend () -> Either<Throwable, LeBiometricCipher>,
            val getCreateIo: (String) -> IO<Unit>,
            val requireConfirmation: Boolean,
        )
    }

    class Unlock(
        val unlockWithMasterPassword: WithPassword,
        val unlockWithBiometric: WithBiometric?,
        val lockReason: String?,
    ) : VaultState {
        class WithPassword(
            val getCreateIo: (String) -> IO<Unit>,
        )

        class WithBiometric(
            val getCipher: suspend () -> Either<Throwable, LeBiometricCipher>,
            val getCreateIo: () -> IO<Unit>,
            val requireConfirmation: Boolean,
        )
    }

    class Main(
        val masterKey: MasterKey,
        val changePassword: ChangePassword,
        val di: DI,
    ) : VaultState {
        class ChangePassword(
            private val key: Any,
            val withMasterPassword: WithPassword,
            val withMasterPasswordAndBiometric: WithBiometric?,
        ) {
            class WithPassword(
                val getCreateIo: (String, String) -> IO<Unit>,
            )

            class WithBiometric(
                val getCipher: suspend () -> Either<Throwable, LeBiometricCipher>,
                val getCreateIo: (String, String) -> IO<Unit>,
            )

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ChangePassword

                if (key != other.key) return false

                return true
            }

            override fun hashCode(): Int {
                return key.hashCode()
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Main

            if (masterKey != other.masterKey) return false
            if (changePassword != other.changePassword) return false

            return true
        }

        override fun hashCode(): Int {
            var result = masterKey.hashCode()
            result = 31 * result + changePassword.hashCode()
            return result
        }
    }

    data object Loading : VaultState
}
