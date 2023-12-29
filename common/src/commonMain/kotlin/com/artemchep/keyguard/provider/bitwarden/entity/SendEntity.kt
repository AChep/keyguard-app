package com.artemchep.keyguard.provider.bitwarden.entity

data class SendEntity(
    val id: String,
    val accessId: String,
    val userId: String,
    val type: SendTypeEntity,
    val name: String,
    val notes: String,
    val file: SendFileEntity,
    val text: SendTextEntity,
    val key: String,
    val maxAccessCount: Int?,
    val accessCount: Int,
    val revisionDate: String,
    val expirationDate: String,
    val deletionDate: String,
    val password: String,
    val disabled: Boolean,
    val hideEmail: Boolean,
)
