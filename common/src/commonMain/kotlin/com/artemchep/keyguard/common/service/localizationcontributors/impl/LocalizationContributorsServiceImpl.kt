package com.artemchep.keyguard.common.service.localizationcontributors.impl

import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributor
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.text.readFromResourcesAsText
import com.artemchep.keyguard.feature.favicon.PictureUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

@Serializable
data class LocalizationContributorEntity(
    val user: User,
    val languages: List<Language> = emptyList(),
    val translated: Int,
    val target: Int,
    val approved: Int,
    val voted: Int,
    val positiveVotes: Int,
    val negativeVotes: Int,
    val winning: Int,
) {
    @Serializable
    data class User(
        val username: String,
        val fullName: String,
        val avatarUrl: String,
    )

    @Serializable
    data class Language(
        val id: String,
        val name: String,
    )
}

fun LocalizationContributorEntity.toDomain() = kotlin.run {
    val user = LocalizationContributor.User(
        username = user.username,
        fullName = user.fullName,
        avatarUrl = PictureUrl(user.avatarUrl),
    )
    val languages = languages
        .map { language ->
            LocalizationContributor.Language(
                id = language.id,
                name = language.name,
            )
        }
    LocalizationContributor(
        user = user,
        languages = languages,
        translated = translated,
        target = target,
        approved = approved,
        voted = voted,
        positiveVotes = positiveVotes,
        negativeVotes = negativeVotes,
        winning = winning,
    )
}

class LocalizationContributorsServiceImpl(
    private val textService: TextService,
    private val json: Json,
) : LocalizationContributorsService {
    companion object {
        private const val TAG = "LocalizationContributorsService"
    }

    private val listIo = ::loadLocalizationContributorsRawData
        .partially1(textService)
        .effectMap { jsonString ->
            val entities = json.decodeFromString<List<LocalizationContributorEntity>>(jsonString)
            val models = entities.map(LocalizationContributorEntity::toDomain)
            models
        }
        .sharedSoftRef(TAG)

    constructor(
        directDI: DirectDI,
    ) : this(
        textService = directDI.instance(),
        json = directDI.instance(),
    )

    override fun get() = listIo
}

private suspend fun loadLocalizationContributorsRawData(
    textService: TextService,
) = textService.readFromResourcesAsText(FileResource.localizationContributors)
