package app.keemobile.kotpass.resources

internal object CustomDataRes {
    const val BasicXml = "<CustomData>" +
        "<Item><Key>k1</Key><Value>v1</Value></Item>" +
        "<Item><Key>k2</Key><Value>v2</Value></Item>" +
        "</CustomData>"
    const val UnknownTagsXml = "<CustomData>" +
        "<Item><Key>k1</Key><Value>v1</Value>" +
        "<x></x></Item><Something></Something>" +
        "</CustomData>"
    const val EmptyTagXml = "<CustomData></CustomData>"
    const val EmptyKeysXml = "<CustomData><Item><Key></Key><Value>v</Value></Item></CustomData>"
}
