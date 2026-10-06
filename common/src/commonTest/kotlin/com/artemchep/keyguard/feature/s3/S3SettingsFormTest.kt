package com.artemchep.keyguard.feature.s3

import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.feature.remotepicker.RemotePickerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class S3SettingsFormTest {
    private val valid = S3FormInput(
        endpoint = " https://minio.lan:9000/ ",
        region = " ",
        bucket = " backups ",
        path = "/keyguard//daily",
        accessKeyId = " AKID ",
        secretAccessKey = "secret",
        pathStyle = true,
    )

    @Test
    fun `valid prefix input builds a normalized location`() {
        assertNull(validateS3Form(valid, S3SettingsRoute.Purpose.Prefix))

        val location = assertIs<S3Location.Prefix>(buildS3Location(valid, S3SettingsRoute.Purpose.Prefix))

        assertEquals("https://minio.lan:9000", location.bucket.endpoint)
        assertNull(location.bucket.region)
        assertEquals("backups", location.bucket.name)
        assertEquals("keyguard/daily/", location.prefix)
        assertEquals("AKID", location.accessKey.accessKeyId)
    }

    @Test
    fun `a blank endpoint means Amazon S3`() {
        val location = buildS3Location(valid.copy(endpoint = ""), S3SettingsRoute.Purpose.Prefix)

        assertNull(location.bucket.endpoint)
    }

    @Test
    fun `connection fields are validated in order`() {
        val purpose = S3SettingsRoute.Purpose.Prefix
        assertEquals(S3FormError.EndpointInvalid, validateS3Form(valid.copy(endpoint = "ftp://x"), purpose))
        assertEquals(S3FormError.EndpointInvalid, validateS3Form(valid.copy(endpoint = "https://u:p@x"), purpose))
        assertEquals(S3FormError.BucketRequired, validateS3Form(valid.copy(bucket = " "), purpose))
        assertEquals(
            S3FormError.BucketInvalid,
            validateS3Form(valid.copy(bucket = "My_Bucket", pathStyle = false), purpose),
        )
        assertNull(validateS3Form(valid.copy(bucket = "My_Bucket", pathStyle = true), purpose))
        assertEquals(S3FormError.AccessKeyIdRequired, validateS3Form(valid.copy(accessKeyId = " "), purpose))
        // The key is sent in a header, which a space or non-ASCII letter breaks.
        for (accessKeyId in listOf("AK ID", "AKÏD")) {
            assertEquals(S3FormError.AccessKeyIdInvalid, validateS3Form(valid.copy(accessKeyId = accessKeyId), purpose))
        }
        assertNull(validateS3Form(valid.copy(accessKeyId = " AKID "), purpose))
        assertEquals(S3FormError.SecretAccessKeyRequired, validateS3Form(valid.copy(secretAccessKey = ""), purpose))
        assertNull(validateS3Form(valid.copy(secretAccessKey = ""), purpose, hasSavedSecret = true))
        assertEquals(S3FormError.PrefixInvalid, validateS3Form(valid.copy(path = "a/../b"), purpose))
    }

    @Test
    fun `keepass keys must name a kdbx object`() {
        val purpose = S3SettingsRoute.Purpose.KeePassDatabase
        assertEquals(S3FormError.KeyRequired, validateS3Form(valid.copy(path = " / "), purpose))
        assertEquals(S3FormError.KeyExtensionRequired, validateS3Form(valid.copy(path = "dir/"), purpose))
        assertEquals(S3FormError.KeyInvalid, validateS3Form(valid.copy(path = "a/./b.kdbx"), purpose))
        assertEquals(S3FormError.KeyExtensionRequired, validateS3Form(valid.copy(path = "vault.txt"), purpose))

        val location = assertIs<S3Location.Object>(
            buildS3Location(valid.copy(path = "/dir/Vault.KDBX"), purpose),
        )
        assertEquals("dir/Vault.KDBX", location.key)
    }

    @Test
    fun `keepass keys preserve repeated slashes`() {
        val purpose = S3SettingsRoute.Purpose.KeePassDatabase
        val input = valid.copy(path = "a//vault.kdbx")

        assertNull(validateS3Form(input, purpose))

        val location = assertIs<S3Location.Object>(buildS3Location(input, purpose))
        assertEquals("a//vault.kdbx", location.key)
    }

    @Test
    fun `picker args start at the prefix or next to the database`() {
        val prefix = buildS3PickerArgs(
            input = valid,
            purpose = S3SettingsRoute.Purpose.Prefix,
            keePassMode = S3SettingsRoute.KeePassMode.Open,
        )
        assertEquals(RemotePickerMode.SelectFolder, prefix.mode)
        assertEquals("keyguard/daily", prefix.initialPath)

        val create = buildS3PickerArgs(
            input = valid.copy(path = "dir/vault.kdbx"),
            purpose = S3SettingsRoute.Purpose.KeePassDatabase,
            keePassMode = S3SettingsRoute.KeePassMode.Create,
        )
        assertEquals(RemotePickerMode.CreateKeePassDatabase, create.mode)
        assertEquals("dir", create.initialPath)
        assertEquals("vault.kdbx", create.initialFileName)
        assertEquals("backups", create.bucket.name)

        val open = buildS3PickerArgs(
            input = valid.copy(path = "vault.kdbx"),
            purpose = S3SettingsRoute.Purpose.KeePassDatabase,
            keePassMode = S3SettingsRoute.KeePassMode.Open,
        )
        assertEquals(RemotePickerMode.OpenKeePassDatabase, open.mode)
        assertEquals("", open.initialPath)
    }

    @Test
    fun `creating a database tests its parent prefix`() {
        val location = buildS3Location(valid.copy(path = "dir/vault.kdbx"), S3SettingsRoute.Purpose.KeePassDatabase)

        val create = s3ConnectionTestLocation(location, S3SettingsRoute.KeePassMode.Create)
        val open = s3ConnectionTestLocation(location, S3SettingsRoute.KeePassMode.Open)

        assertEquals("dir/", assertIs<S3Location.Prefix>(create).prefix)
        assertEquals(location, open)
        val root = s3ConnectionTestLocation(
            buildS3Location(valid.copy(path = "vault.kdbx"), S3SettingsRoute.Purpose.KeePassDatabase),
            S3SettingsRoute.KeePassMode.Create,
        )
        assertEquals("", assertIs<S3Location.Prefix>(root).prefix)
    }

    @Test
    fun `location formatting`() {
        assertEquals("s3://backups/keyguard/", s3LocationUri("backups", "keyguard/"))
        assertEquals("s3://backups/", s3LocationUri("backups", null))
        assertEquals("minio.lan:9000", s3EndpointHostOrNull("https://minio.lan:9000/"))
        assertEquals("s3.example.com", s3EndpointHostOrNull("https://s3.example.com"))
        assertNull(s3EndpointHostOrNull(" "))
    }
}
