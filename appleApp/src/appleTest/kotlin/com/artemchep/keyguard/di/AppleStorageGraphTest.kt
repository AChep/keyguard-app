package com.artemchep.keyguard.di

import com.artemchep.keyguard.AppleKeyValueStoreFactory
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.store.DownloadFileStore
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.withTestAppleStorage
import com.artemchep.keyguard.provider.bitwarden.upload.EncryptedFilePendingUploadService
import com.artemchep.keyguard.util.io.readBytes
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppleStorageGraphTest {
    @Test
    fun fileStoresAreIsolatedBetweenGraphsAndSurviveReopening() = runTest {
        withTestAppleStorage { first ->
            withTestAppleStorage { second ->
                first.withKoin { koin ->
                    val factory = koin.get<KeyValueStoreFactory>()
                    assertSame(koin.get<AppleKeyValueStoreFactory>(), factory)
                    val preference = factory.get(Files.SETTINGS).getString("marker", "empty")
                    preference.setAndCommit("first").bind()
                    assertEquals("empty", factory.get(Files.UI_STATE).getString("marker", "empty").first())
                    second.withKoin { other ->
                        assertEquals("empty", other.get<KeyValueStoreFactory>()
                            .get(Files.SETTINGS).getString("marker", "empty").first())
                    }
                }
                first.withKoin { reopened ->
                    assertEquals("first", reopened.get<KeyValueStoreFactory>()
                        .get(Files.SETTINGS).getString("marker", "empty").first())
                }
            }
        }
    }

    @Test
    fun downloadsAndEncryptedUploadsUseTheTestRoot() = runTest {
        withTestAppleStorage { directories ->
            directories.withKoin { koin ->
                val downloads = koin.get<DownloadFileStore>()
                val info = DownloadInfoEntity(
                    id = "download", localCipherId = "cipher", remoteCipherId = null,
                    attachmentId = "attachment", url = "https://example.invalid/file",
                    urlIsOneTime = false, name = "document.txt", createdDate = Instant.fromEpochSeconds(0),
                )
                val bytes = "fixture contents".encodeToByteArray()
                val writer = assertIs<DownloadWriter.LocalPathWriter>(downloads.writer(info))
                assertTrue(writer.path.value.startsWith(directories.data.path.value + "/downloads/"))
                writer.writeBytes(bytes)
                assertContentEquals(bytes, writer.path.readBytes())
                assertTrue(downloads.exists(info))
                assertTrue(downloads.delete(info))
                assertFalse(downloads.exists(info))

                val uploads = koin.get<EncryptedFilePendingUploadService>()
                val key = ByteArray(64) { it.toByte() }
                val pending = uploads.stage(
                    accountId = "account", namespace = "attachments", fileId = "upload",
                    source = Buffer().apply { write(bytes) }, fileKey = key,
                )
                assertTrue(pending.path.startsWith(directories.data.path.value + "/pending_uploads/"))
                assertContentEquals(bytes, uploads.readPlaintext(pending, key))
                uploads.delete(pending)
                assertFalse(SystemFileSystem.exists(LocalPath(pending.path).toKotlinxIoPath()))
                assertEquals(directories.cache, koin.get<CacheDirProvider>().get())
                assertEquals(directories.temporary, koin.get<PrivateTemporaryDirProvider>().get())
            }
        }
    }

    @Test
    fun clearDataResetsLiveStoresAndPreservesOtherFixtures() = runTest {
        withTestAppleStorage { first ->
            withTestAppleStorage { second ->
                first.withKoin { koin ->
                    second.withKoin { other ->
                        val preference = koin.get<KeyValueStoreFactory>()
                            .get(Files.SETTINGS).getString("marker", "empty")
                        val unrelated = other.get<KeyValueStoreFactory>()
                            .get(Files.SETTINGS).getString("marker", "empty")
                        preference.setAndCommit("erase").bind()
                        unrelated.setAndCommit("keep").bind()
                        val vault = first.data.path.resolve("vault")
                        SystemFileSystem.createDirectories(vault.toKotlinxIoPath())
                        koin.get<ClearData>()().bind()
                        assertEquals("empty", preference.first())
                        assertFalse(SystemFileSystem.exists(vault.toKotlinxIoPath()))
                        assertFalse(SystemFileSystem.exists(first.cache.toKotlinxIoPath()))
                        assertEquals("keep", unrelated.first())
                    }
                }
                first.withKoin { reopened ->
                    assertEquals("empty", reopened.get<KeyValueStoreFactory>()
                        .get(Files.SETTINGS).getString("marker", "empty").first())
                }
                second.withKoin { reopened ->
                    assertEquals("keep", reopened.get<KeyValueStoreFactory>()
                        .get(Files.SETTINGS).getString("marker", "empty").first())
                }
            }
        }
    }

    @Test
    fun bothDatabaseManagersResolveWithoutAppGroupConfiguration() = runTest {
        withTestAppleStorage { directories ->
            directories.withKoin { koin ->
                assertNotNull(koin.get<ExposedDatabaseManager>())
                koin.withVaultSession(MasterKey(MasterKdfVersion.LATEST, ByteArray(32))) { session ->
                    assertNotNull(session.resolve { get<VaultDatabaseManager>() })
                }
            }
        }
    }

    @Test
    fun failedTestClosesItsGraphAndDeletesItsFiles() = runTest {
        var root: LocalPath? = null
        var worker: Job? = null
        assertFailsWith<IllegalStateException> {
            withTestAppleStorage { directories ->
                root = directories.root
                directories.withKoin { koin ->
                    worker = koin.get<WindowCoroutineScope>().launch { awaitCancellation() }
                    koin.get<KeyValueStoreFactory>().get(Files.SETTINGS)
                        .getString("marker", "empty").setAndCommit("written").bind()
                    error("test failure")
                }
            }
        }
        assertFalse(SystemFileSystem.exists(assertNotNull(root).toKotlinxIoPath()))
        assertTrue(assertNotNull(worker).isCompleted)
    }
}
