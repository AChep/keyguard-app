package app.keemobile.kotpass.resources

import app.keemobile.kotpass.models.DeletedObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal object DeletedObjectRes {
    const val Base64StringUuid = "LtoeZ26BBkqtr93N9tqO4g=="

    val BasicXml = """
    <DeletedObject>
        <UUID>$Base64StringUuid</UUID>
        <DeletionTime>2015-08-16T14:50:13Z</DeletionTime>
    </DeletedObject>
    """.trimIndent()

    val BasicObject = DeletedObject(
        id = Uuid.parse("2eda1e67-6e81-064a-adaf-ddcdf6da8ee2"),
        deletionTime = Instant.parse("2015-08-16T14:50:13Z")
    )
}
