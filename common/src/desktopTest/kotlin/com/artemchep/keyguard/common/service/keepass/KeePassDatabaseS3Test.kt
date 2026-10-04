package com.artemchep.keyguard.common.service.keepass

import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
import com.artemchep.keyguard.common.exception.KeePassFileAlreadyExistsException
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.S3AccessKey
import com.artemchep.keyguard.common.model.S3Bucket
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.s3.InMemoryS3ClientFactory
import com.artemchep.keyguard.common.service.s3.toFileLocation
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class KeePassDatabaseS3Test {
    private val fileService = FileServiceImpl()
    private val base64Service = Base64ServiceJvm()

    private val location = S3Location.Object(
        bucket = S3Bucket(
            endpoint = "http://minio.lan:9000",
            region = "eu-west-1",
            name = "vaults",
            pathStyle = true,
        ),
        accessKey = S3AccessKey(
            accessKeyId = "AKID",
            secretAccessKey = Password("secret-key"),
        ),
        key = "keepass/vault.kdbx",
    )

    @Test
    fun `new database is created with If-None-Match and opens from the token`() = runTest {
        val factory = InMemoryS3ClientFactory()

        prepareKeePassDatabase(
            fileService = fileService,
            params = params(AddKeePassAccountParams.Mode.New(allowOverwrite = false)),
            s3ClientFactory = factory,
        )
        val database = openKeePassDatabase(
            token = token(),
            fileService = fileService,
            base64Service = base64Service,
            s3ClientFactory = factory,
        )

        assertEquals("Keyguard database", database.content.meta.name)
        assertEquals(S3WritePrecondition.IfNoneMatch, factory.client.preconditions.last().second)
        assertNotNull(factory.client.objects["keepass/vault.kdbx"])
        val config = factory.configs.first()
        assertEquals("http://minio.lan:9000", config.endpoint)
        assertEquals("eu-west-1", config.region)
        assertEquals("vaults", config.bucket)
        assertEquals(true, config.pathStyle)
        assertEquals("AKID", config.credentials.accessKeyId)
    }

    @Test
    fun `new database refuses to overwrite an existing object`() = runTest {
        val factory = InMemoryS3ClientFactory()
        factory.client.objects["keepass/vault.kdbx"] = "existing".encodeToByteArray()

        assertFailsWith<KeePassFileAlreadyExistsException> {
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(AddKeePassAccountParams.Mode.New(allowOverwrite = false)),
                s3ClientFactory = factory,
            )
        }
    }

    @Test
    fun `create race maps to keepass file already exists`() = runTest {
        val factory = InMemoryS3ClientFactory()
        factory.client.putError = S3Exception.AlreadyExists(S3Operation.Put, "keepass/vault.kdbx")

        assertFailsWith<KeePassFileAlreadyExistsException> {
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(AddKeePassAccountParams.Mode.New(allowOverwrite = false)),
                s3ClientFactory = factory,
            )
        }
    }

    @Test
    fun `save replaces the object only while the ETag matches`() = runTest {
        val factory = InMemoryS3ClientFactory()
        prepareKeePassDatabase(
            fileService = fileService,
            params = params(AddKeePassAccountParams.Mode.New(allowOverwrite = false)),
            s3ClientFactory = factory,
        )
        val token = token()
        val database = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
            s3ClientFactory = factory,
        )
        val metadata = assertNotNull(
            getKeePassDatabaseMetadata(
                fileService = fileService,
                token = token,
                s3ClientFactory = factory,
            ),
        )

        val saved = assertNotNull(
            saveKeePassDatabase(
                fileService = fileService,
                token = token,
                database = database,
                base64Service = base64Service,
                s3ClientFactory = factory,
                expectedMetadata = metadata,
            ),
        )
        assertEquals(S3WritePrecondition.IfMatch(metadata.etag!!), factory.client.preconditions.last().second)

        // The stale metadata no longer matches the object.
        assertFailsWith<KeePassDatabaseModifiedExternallyException> {
            saveKeePassDatabase(
                fileService = fileService,
                token = token,
                database = database,
                base64Service = base64Service,
                s3ClientFactory = factory,
                expectedMetadata = metadata,
            )
        }
        assertNotEquals(metadata.etag, saved.etag)
    }

    @Test
    fun `missing s3 client factory fails`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(AddKeePassAccountParams.Mode.Open),
            )
        }
    }

    private fun params(
        mode: AddKeePassAccountParams.Mode,
    ) = AddKeePassAccountParams(
        mode = mode,
        dbUri = "s3://vaults/keepass/vault.kdbx",
        dbFileName = "vault.kdbx",
        s3 = location,
        keyUri = null,
        password = "secret",
    )

    private fun token() = KeePassToken(
        id = "account-id",
        key = KeePassToken.Key(
            passwordBase64 = base64Service.encodeToString("secret"),
            keyBase64 = null,
        ),
        database = KeePassToken.Database(
            fileName = "vault.kdbx",
            location = location.toFileLocation(displayName = "vault.kdbx"),
        ),
    )
}
