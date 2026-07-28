package app.keemobile.kotpass.errors

sealed class KeyfileError : Exception() {
    class InvalidVersion : KeyfileError()

    class InvalidHash : KeyfileError()

    class NoKeyData : KeyfileError()
}
