package app.keemobile.kotpass.xml

internal object KeyfileXml {
    object Tags {
        const val Document = "KeyFile"
        const val Meta = "Meta"
        const val Version = "Version"
        const val Key = "Key"
        const val Data = "Data"
    }

    object Attributes {
        const val Hash = "Hash"
    }
}
