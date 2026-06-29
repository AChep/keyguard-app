package app.keemobile.kotpass.constants

internal object KdfConst {
    object Keys {
        const val Uuid = "\$UUID"
        const val Rounds = "R"
        const val SaltOrSeed = "S"
        const val Parallelism = "P"
        const val Memory = "M"
        const val Iterations = "I"
        const val Version = "V"
        const val SecretKey = "K" // Unsupported
        const val AssocData = "A" // Unsupported
    }
}
