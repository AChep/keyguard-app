package com.artemchep.keyguard.feature.auth.companion

import android.app.Application
import android.content.Intent
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.artemchep.keyguard.android.util.await
import com.artemchep.keyguard.common.io.readByteArrayAndClose
import com.artemchep.keyguard.common.io.toInputStream
import com.artemchep.keyguard.common.service.file.FileService
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

internal interface CompanionAuthPhoneCapabilitySource {
    suspend fun getReachablePhoneNodes(): List<Node>

    fun observeReachablePhoneNodes(): Flow<List<Node>>
}

internal fun companionAuthReachablePhoneNodesFlow(
    source: CompanionAuthPhoneCapabilitySource,
): Flow<List<Node>> = flow {
    emit(source.getReachablePhoneNodes())
    emitAll(source.observeReachablePhoneNodes())
}

internal fun companionAuthPhoneAvailabilityFlow(
    source: CompanionAuthPhoneCapabilitySource,
): Flow<Boolean> = flow {
    emitAll(
        companionAuthReachablePhoneNodesFlow(source).map { nodes ->
            nodes.isNotEmpty()
        },
    )
}.distinctUntilChanged()

private class GooglePlayCompanionAuthPhoneCapabilitySource(
    private val capabilityClient: CapabilityClient,
) : CompanionAuthPhoneCapabilitySource {
    override suspend fun getReachablePhoneNodes(): List<Node> =
        capabilityClient
            .getCapability(
                CompanionAuthProtocol.PHONE_CAPABILITY,
                CapabilityClient.FILTER_REACHABLE,
            )
            .await()
            .nodes
            .toList()

    override fun observeReachablePhoneNodes(): Flow<List<Node>> = callbackFlow {
        val listener = CapabilityClient.OnCapabilityChangedListener {
            trySend(it.nodes.toList())
        }
        capabilityClient
            .addListener(listener, CompanionAuthProtocol.PHONE_CAPABILITY)
            .await()
        awaitClose {
            capabilityClient.removeListener(listener, CompanionAuthProtocol.PHONE_CAPABILITY)
        }
    }
}

internal class CompanionAuthTransportAndroid(
    private val application: Application,
    private val fileService: FileService,
) {
    private val messageClient by lazy {
        Wearable.getMessageClient(application)
    }
    private val capabilityClient by lazy {
        Wearable.getCapabilityClient(application)
    }
    private val channelClient by lazy {
        Wearable.getChannelClient(application)
    }
    private val nodeClient by lazy {
        Wearable.getNodeClient(application)
    }
    private val remoteActivityExecutor by lazy {
        Executors.newSingleThreadExecutor()
    }
    private val remoteActivityHelper by lazy {
        RemoteActivityHelper(application, remoteActivityExecutor)
    }
    private val phoneCapabilitySource by lazy {
        GooglePlayCompanionAuthPhoneCapabilitySource(capabilityClient)
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        application = directDI.instance(),
        fileService = directDI.instance(),
    )

    fun phoneAvailabilityFlow(): Flow<Boolean> =
        companionAuthPhoneAvailabilityFlow(phoneCapabilitySource)

    fun reachablePhoneNodesFlow(): Flow<List<Node>> =
        companionAuthReachablePhoneNodesFlow(phoneCapabilitySource)

    suspend fun getReachablePhoneNodes(): List<Node> =
        phoneCapabilitySource.getReachablePhoneNodes()

    suspend fun getLocalNodeId(): String =
        nodeClient
            .localNode
            .await()
            .id

    suspend fun sendMessage(
        nodeId: String,
        path: String,
        payload: ByteArray,
    ) {
        messageClient
            .sendMessage(
                nodeId,
                path,
                payload,
            )
            .await()
    }

    suspend fun openOutgoingChannelAndCopy(
        nodeId: String,
        path: String,
        sourceUri: String,
    ) {
        openOutgoingChannel(nodeId, path) { sink ->
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                fileService.readFromFile(sourceUri)
                    .toInputStream()
                    .use { input ->
                        input.copyTo(sink)
                        sink.flush()
                    }
            }
        }
    }

    suspend fun openOutgoingChannelAndWrite(
        nodeId: String,
        path: String,
        payload: ByteArray,
    ) {
        openOutgoingChannel(nodeId, path) { sink ->
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                sink.write(payload)
                sink.flush()
            }
        }
    }

    suspend fun readFromFile(
        sourceUri: String,
    ): ByteArray = withContext(kotlinx.coroutines.Dispatchers.IO) {
        fileService.readFromFile(sourceUri).readByteArrayAndClose()
    }

    private suspend fun openOutgoingChannel(
        nodeId: String,
        path: String,
        copy: suspend (java.io.OutputStream) -> Unit,
    ) {
        val channel = channelClient.openChannel(nodeId, path).await()
        try {
            val output = channelClient.getOutputStream(channel).await()
            try {
                copy(output)
            } finally {
                output.close()
            }
        } finally {
            runCatching {
                channelClient.close(channel).await()
            }
        }
    }

    suspend fun receiveIncomingChannel(
        channel: ChannelClient.Channel,
        targetFile: File,
        maxBytes: Long,
    ) {
        try {
            val input = channelClient.getInputStream(channel).await()
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                input.use { source ->
                    targetFile.outputStream().use { sink ->
                        source.copyToWithLimit(
                            sink = sink,
                            maxBytes = maxBytes,
                        )
                    }
                }
            }
        } finally {
            runCatching {
                channelClient.close(channel).await()
            }
        }
    }

    suspend fun receiveIncomingChannel(
        channel: ChannelClient.Channel,
        maxBytes: Long,
        onBytes: suspend (ByteArray) -> Unit,
    ) {
        try {
            val input = channelClient.getInputStream(channel).await()
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                input.use { source ->
                    onBytes(
                        source.readBoundedBytes(
                            maxBytes = maxBytes,
                        ),
                    )
                }
            }
        } finally {
            runCatching {
                channelClient.close(channel).await()
            }
        }
    }

    suspend fun startRemoteActivity(
        intent: Intent,
        nodeId: String,
    ) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            remoteActivityHelper
                .startRemoteActivity(intent, nodeId)
                .get()
        }
    }
}

internal class CompanionAuthPayloadTooLargeException(
    maxBytes: Long,
) : IllegalStateException("Companion auth payload exceeds ${maxBytes} bytes.")

private fun java.io.InputStream.readBoundedBytes(
    maxBytes: Long,
): ByteArray {
    val buffer = ByteArray(CHANNEL_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var totalBytes = 0L
    while (true) {
        val bytesRead = read(buffer)
        if (bytesRead < 0) {
            break
        }
        totalBytes += bytesRead
        if (totalBytes > maxBytes) {
            throw CompanionAuthPayloadTooLargeException(maxBytes)
        }
        output.write(buffer, 0, bytesRead)
    }
    return output.toByteArray()
}

private fun java.io.InputStream.copyToWithLimit(
    sink: java.io.OutputStream,
    maxBytes: Long,
) {
    val buffer = ByteArray(CHANNEL_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val bytesRead = read(buffer)
        if (bytesRead < 0) {
            break
        }
        totalBytes += bytesRead
        if (totalBytes > maxBytes) {
            throw CompanionAuthPayloadTooLargeException(maxBytes)
        }
        sink.write(buffer, 0, bytesRead)
    }
    sink.flush()
}

private const val CHANNEL_BUFFER_SIZE = 8 * 1024
