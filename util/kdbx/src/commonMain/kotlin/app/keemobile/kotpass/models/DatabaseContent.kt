package app.keemobile.kotpass.models

data class DatabaseContent(
    val meta: Meta,
    val group: Group,
    val deletedObjects: List<DeletedObject>
)
