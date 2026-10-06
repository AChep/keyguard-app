package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupSetupDraft
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.verifyAndSaveBackupSetup
import com.artemchep.keyguard.feature.s3.S3FormError
import com.artemchep.keyguard.feature.s3.S3FormInput
import com.artemchep.keyguard.feature.s3.S3SettingsRoute
import com.artemchep.keyguard.feature.s3.locationUriOrNull
import com.artemchep.keyguard.feature.s3.s3EndpointHostOrNull
import com.artemchep.keyguard.feature.s3.validateS3Form
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-confined editor. Its scope is a child of the observed unlocked vault session. */
internal class BackupSetupEditor(
    initial: BackupConfig,
    private val scope: CoroutineScope,
    private val verify: suspend (BackupConfig) -> Unit,
    private val save: suspend (BackupConfig) -> Unit,
    private val invalidDestinationMessage: String,
    private val publish: (BackupSetupSnapshot) -> Unit,
    private val complete: () -> Unit,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var initial: BackupConfig? = initial
    private var draft: BackupSetupDraft? = BackupSetupDraft(initial)
    private var saveJob: Job? = null
    private var saving = false
    private var error: String? = null

    fun publish() {
        val config = draft?.config ?: return
        val store = config.store
        val s3Store = store as? BackupStoreConfig.S3
        publish(
            BackupSetupSnapshot(
                loaded = true,
                enabled = config.enabled,
                isTestingLocation = saving,
                error = error,
                storeKind = store.bridgeKind(),
                localPath = (store as? BackupStoreConfig.Local)?.path,
                webDavUrl = (store as? BackupStoreConfig.WebDav)?.url,
                webDavUsername = (store as? BackupStoreConfig.WebDav)?.username,
                hasWebDavPassword = (store as? BackupStoreConfig.WebDav)?.password != null,
                s3Endpoint = s3Store?.endpoint,
                s3Region = s3Store?.region,
                s3Bucket = s3Store?.bucket,
                s3Prefix = s3Store?.prefix,
                s3AccessKeyId = s3Store?.accessKeyId,
                s3PathStyle = s3Store?.pathStyle ?: true,
                s3Location = s3Store?.locationUriOrNull(),
                s3EndpointHost = s3Store?.let { s3EndpointHostOrNull(it.endpoint) },
                hasPassword = config.password != null,
                includeAttachments = config.includeAttachments,
                retentionMaxSnapshots = config.retention.maxSnapshots,
            ),
        )
    }

    fun edit(block: BackupSetupDraft.() -> Unit) {
        if (saving) return
        draft?.block() ?: return
        error = null
        publish()
    }

    fun restorePassword() = edit { restorePassword(initial ?: return@edit) }

    fun keepsS3SecretAccessKey(
        endpoint: String,
        bucket: String,
        accessKeyId: String,
    ): Boolean = draft?.keepsS3SecretAccessKey(endpoint, bucket, accessKeyId) == true

    fun s3Error(
        endpoint: String,
        region: String,
        bucket: String,
        prefix: String,
        accessKeyId: String,
        secretAccessKey: String,
        pathStyle: Boolean,
    ): S3FormError? = validateS3Form(
        input = S3FormInput(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            path = prefix,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            pathStyle = pathStyle,
        ),
        purpose = S3SettingsRoute.Purpose.Prefix,
        hasSavedSecret = keepsS3SecretAccessKey(endpoint, bucket, accessKeyId),
    )

    fun submit() {
        if (saving) return
        val config = draft?.config?.copy(enabled = true) ?: return
        if (config.canRun()) {
            verifyAndSave(config)
        } else {
            error = invalidDestinationMessage
            publish()
        }
    }

    private fun verifyAndSave(config: BackupConfig) {
        error = null
        saving = true
        publish()
        if (draft == null) return
        saveJob = scope.launch {
            try {
                runCatchingNonFatal {
                    val saved = verifyAndSaveBackupSetup(
                        config = config,
                        verify = verify,
                        save = save,
                        isCurrent = { draft != null },
                        dispatcher = storageDispatcher,
                    )
                    if (saved) complete()
                }.onFailure { failure ->
                    error = failure.message ?: failure::class.simpleName
                }
            } finally {
                saving = false
                saveJob = null
                publish()
            }
        }
    }

    fun close() {
        draft = null
        initial = null
        saveJob?.cancel()
        saveJob = null
    }
}
