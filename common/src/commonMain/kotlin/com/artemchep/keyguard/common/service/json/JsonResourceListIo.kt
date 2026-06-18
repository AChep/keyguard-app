package com.artemchep.keyguard.common.service.json

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

inline fun <reified Entity, Domain> jsonResourceListIo(
    textService: TextService,
    json: Json,
    resource: FileResource,
    tag: String,
    crossinline mapper: suspend (Entity) -> Domain,
): IO<List<Domain>> = suspend {
    textService.readFromResourcesAsText(resource)
}
    .effectMap { jsonString ->
        json.decodeFromString<List<Entity>>(jsonString)
            .map { entity ->
                mapper(entity)
            }
    }
    .sharedSoftRef(tag)
