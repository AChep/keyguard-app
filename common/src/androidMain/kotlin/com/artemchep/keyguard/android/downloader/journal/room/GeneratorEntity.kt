package com.artemchep.keyguard.android.downloader.journal.room

import androidx.room.Entity
import kotlinx.datetime.Instant

@Entity(
    primaryKeys = [
        "id",
    ],
)
data class GeneratorEntity(
    val id: String,
    val value: String,
    val createdDate: Instant,
    val isPassword: Boolean,
    val isUsername: Boolean,
)
