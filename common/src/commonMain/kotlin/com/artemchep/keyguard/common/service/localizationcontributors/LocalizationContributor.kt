package com.artemchep.keyguard.common.service.localizationcontributors

import com.artemchep.keyguard.feature.favicon.PictureUrl

data class LocalizationContributor(
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
    data class User(
        val username: String,
        val fullName: String,
        val avatarUrl: PictureUrl,
    )

    data class Language(
        val id: String,
        val name: String,
    )
}
