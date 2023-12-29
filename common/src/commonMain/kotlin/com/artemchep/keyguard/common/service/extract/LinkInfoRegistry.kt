package com.artemchep.keyguard.common.service.extract

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfo

class LinkInfoRegistry(
    private val extractors: List<LinkInfoExtractor<LinkInfo, LinkInfo>>,
) {
    suspend fun process(uri: DSecret.Uri): List<LinkInfo> {
        val allArtifacts = mutableListOf<LinkInfo>()
        var newArtifacts = listOf<LinkInfo>(
            uri,
        )
        do {
            allArtifacts.addAll(newArtifacts)

            // Generate new list of artifacts from
            // the old one.
            newArtifacts = newArtifacts
                .flatMap { linkInfo ->
                    extractors
                        .mapNotNull { extractor ->
                            val inputMatches =
                                extractor.from.java.isAssignableFrom(linkInfo.javaClass) &&
                                        extractor.handles(linkInfo)
                            if (inputMatches) {
                                extractor.extractInfo(linkInfo)
                                    .attempt()
                                    .bind()
                                    // just ignore the ones that failed
                                    .getOrNull()
                            } else {
                                null
                            }
                        }
                }
        } while (newArtifacts.isNotEmpty())
        return allArtifacts
    }
}
