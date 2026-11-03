package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupStoreKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ImportBackupStageTest {
    private val snapshots = Step.Snapshots(
        source = ImportSourceRef.Backup(BackupStoreConfig.Local("/tmp/backup")),
        snapshots = listOf(
            Step.Snapshots.Snapshot(
                id = "new",
                createdAt = "Today",
                itemCount = 3,
                attachmentCount = 1,
            ),
            Step.Snapshots.Snapshot(
                id = "old",
                createdAt = "Yesterday",
                itemCount = 2,
                attachmentCount = 0,
            ),
        ),
        selectedId = "new",
    )

    @Test
    fun `the start stage offers every backup location and asks for the tapped one`() {
        val picked = mutableListOf<BackupStoreConfig>()
        val stage = Step.Start.toTestStage(
            actions = importStageActions(
                onPickBackup = { store ->
                    picked += store
                },
            ),
        )

        val start = assertIs<ImportState.Stage.Start>(stage)
        assertEquals(
            listOf(BackupStoreKind.Local, BackupStoreKind.WebDav, BackupStoreKind.S3),
            start.backupLocations.map { it.kind },
        )
        assertNotNull(start.backupLocations[1].onClick).invoke()
        assertEquals(listOf(BackupStoreKind.WebDav), picked.map { it.kind })
    }

    @Test
    fun `the snapshot list marks the selected snapshot and forwards the choice`() {
        val selected = mutableListOf<String>()
        val confirmed = mutableListOf<Step.Snapshots>()
        val stage = snapshots.toTestStage(
            actions = importStageActions(
                onSnapshotSelect = { _, id ->
                    selected += id
                },
                onSnapshotConfirm = { step ->
                    confirmed += step
                },
            ),
        )

        val list = assertIs<ImportState.Stage.Snapshots>(stage)
        assertEquals(listOf(true, false), list.items.map { it.selected })
        assertNotNull(list.items[1].onClick).invoke()
        assertEquals(listOf("old"), selected)
        assertNotNull(list.onConfirm).invoke()
        assertEquals(listOf(snapshots), confirmed)
    }

    @Test
    fun `backup errors offer a retry`() {
        listOf(
            Retry.Pick(ImportSourceRef.Backup(BackupStoreConfig.Local())),
            Retry.ConfiguredBackup,
            Retry.Snapshots(snapshots),
        ).forEach { retry ->
            val stage = Step.Error(
                message = "The location couldn't be reached.",
                retry = retry,
            ).toTestStage()
            assertNotNull(assertIs<ImportState.Stage.Error>(stage).onRetry)
        }
    }
}
