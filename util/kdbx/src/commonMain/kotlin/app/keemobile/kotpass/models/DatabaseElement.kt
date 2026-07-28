package app.keemobile.kotpass.models

import app.keemobile.kotpass.constants.PredefinedIcon
import kotlin.uuid.Uuid

sealed interface DatabaseElement {
    val uuid: Uuid
    val times: TimeData?
    val icon: PredefinedIcon
    val customIconUuid: Uuid?
    val tags: List<String>
}
