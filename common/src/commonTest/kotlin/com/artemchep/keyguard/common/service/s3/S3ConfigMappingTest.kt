package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class S3ConfigMappingTest {
    private val store = BackupStoreConfig.S3(
        endpoint = "https://minio.lan:9000",
        region = "eu-west-1",
        bucket = "backups",
        prefix = "keyguard",
        accessKeyId = "AKID",
        secretAccessKey = Password("secret"),
        pathStyle = false,
    )

    @Test
    fun `backup store maps to a prefix location and back`() {
        val location = assertNotNull(store.toS3LocationOrNull())

        assertEquals("keyguard/", location.prefix)
        assertEquals("backups", location.bucket.name)
        assertEquals(store.copy(prefix = "keyguard/"), location.toBackupStoreConfig())

        val config = location.toS3ClientConfig()
        assertEquals("https://minio.lan:9000", config.endpoint)
        assertEquals("eu-west-1", config.region)
        assertEquals("backups", config.bucket)
        assertEquals(false, config.pathStyle)
        assertEquals("AKID", config.credentials.accessKeyId)
        assertEquals("secret", config.credentials.secretAccessKey)
    }

    @Test
    fun `incomplete backup store has no location`() {
        assertNull(store.copy(bucket = " ").toS3LocationOrNull())
        assertNull(store.copy(accessKeyId = null).toS3LocationOrNull())
        assertNull(store.copy(secretAccessKey = null).toS3LocationOrNull())
        assertNull(store.copy(prefix = "a/../").toS3LocationOrNull())
    }
}
