package com.artemchep.keyguard.benchmark

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import app.keemobile.kotpass.database.encode
import com.artemchep.keyguard.Main
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountParams
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance

class VaultBenchmarkSeedActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "Preparing benchmark vault…"
            contentDescription = STATUS_SEEDING
        }
        setContentView(statusView)

        val entryCount = intent.getIntExtra(EXTRA_ENTRY_COUNT, -1)
        scope.launch {
            runCatching {
                seed(entryCount)
            }.onSuccess {
                showStatus(
                    text = "Benchmark vault ready: $entryCount items",
                    description = statusComplete(entryCount),
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not prepare the benchmark vault.", error)
                showStatus(
                    text = "Benchmark vault failed: ${error.message}",
                    description = "$STATUS_FAILED_PREFIX${error::class.simpleName}:${error.message}",
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun seed(entryCount: Int) {
        require(entryCount > 0) { "Missing or invalid '$EXTRA_ENTRY_COUNT' extra." }

        val markerFile = filesDir.resolve("benchmarks/vault-$entryCount-$CORPUS_VERSION.ready")
        val session = (application as Main).di.direct
            .instance<GetVaultSession>()()
            .filterIsInstance<MasterSession.Key>()
            .first()
        val sessionDi = session.di.direct
        val getCiphers = sessionDi.instance<GetCiphers>()

        if (markerFile.isFile) {
            awaitCipherCount(getCiphers, entryCount)
            return
        }

        val existingCiphers = getCiphers().first()
        if (existingCiphers.size == entryCount) {
            writeMarker(markerFile)
            return
        }
        require(existingCiphers.isEmpty()) {
            "Expected an empty vault before seeding, found ${existingCiphers.size} items."
        }

        val databaseFile = filesDir.resolve("benchmarks/vault-$entryCount-$CORPUS_VERSION.kdbx")
        withContext(Dispatchers.Default) {
            databaseFile.parentFile?.mkdirs()
            val encoded = BenchmarkVaultCorpus
                .create(entryCount = entryCount, password = DATABASE_PASSWORD)
                .encode()
            databaseFile.writeBytes(encoded)
        }

        sessionDi.instance<AddKeePassAccount>()(
            AddKeePassAccountParams(
                mode = AddKeePassAccountParams.Mode.Open,
                dbUri = Uri.fromFile(databaseFile).toString(),
                dbFileName = databaseFile.name,
                managedByApp = true,
                keyUri = null,
                password = DATABASE_PASSWORD,
                syncMode = AddKeePassAccountParams.SyncMode.Direct,
            ),
        ).bind()

        awaitCipherCount(getCiphers, entryCount)
        writeMarker(markerFile)
    }

    private suspend fun awaitCipherCount(
        getCiphers: GetCiphers,
        expected: Int,
    ) {
        withTimeout(CIPHER_LOAD_TIMEOUT_MS) {
            getCiphers().first { ciphers -> ciphers.size == expected }
        }
    }

    private suspend fun writeMarker(markerFile: File) = withContext(Dispatchers.IO) {
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(CORPUS_VERSION)
    }

    private fun showStatus(
        text: String,
        description: String,
    ) {
        statusView.text = text
        statusView.contentDescription = description
    }

    companion object {
        const val EXTRA_ENTRY_COUNT = "entryCount"
        const val STATUS_SEEDING = "benchmark:vault-seeding"
        const val STATUS_FAILED_PREFIX = "benchmark:vault-seed-failed:"

        fun statusComplete(entryCount: Int) = "benchmark:vault-seed-complete:$entryCount"

        private const val TAG = "VaultBenchmarkSeed"
        private const val CORPUS_VERSION = "v1"
        private const val DATABASE_PASSWORD = "benchmark-database-password"
        private const val CIPHER_LOAD_TIMEOUT_MS = 180_000L
    }
}
