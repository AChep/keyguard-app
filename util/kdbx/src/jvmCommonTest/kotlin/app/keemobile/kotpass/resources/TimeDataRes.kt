package app.keemobile.kotpass.resources

import kotlin.time.Instant

internal object TimeDataRes {
    const val DateTimeText = "2020-01-12T14:15:00Z"
    const val Base64BinaryDateTimeText = "5CCt1Q4AAAA="

    val ParsedDateTime: Instant = Instant.parse(DateTimeText)

    fun getBaseXml(dateTimeText: String) = """
    <?xml version="1.0" encoding="utf-8" standalone="yes"?>
    <Times>
        <CreationTime>$dateTimeText</CreationTime>
        <LastModificationTime>$dateTimeText</LastModificationTime>
        <LastAccessTime>$dateTimeText</LastAccessTime>
        <ExpiryTime/>
        <Expires>False</Expires>
        <UsageCount>7</UsageCount>
        <LocationChanged>$dateTimeText</LocationChanged>
    </Times>
    """.trimIndent()
}
