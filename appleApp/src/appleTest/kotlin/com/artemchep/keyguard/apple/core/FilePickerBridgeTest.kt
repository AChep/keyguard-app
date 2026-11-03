package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePickerBridgeTest {
    @Test
    fun resultsAreConsumedOnceAndKeepFileAccessMetadata() {
        val bridge = FilePickerBridge()
        val requests = mutableListOf<AddFilePickerRequest>()
        val results = mutableListOf<FilePickerResult?>()
        bridge.onRequest = { requests += it }
        bridge.pick(intent { results += it })
        val id = requests.single().requestId

        bridge.resolve(id, "file:///certificate.p12", "certificate.p12", -1, "bookmark")
        bridge.resolve(id, "file:///late.p12", null, 0, null)
        bridge.cancel(id)

        val result = requireNotNull(results.single())
        assertEquals("file:///certificate.p12", result.uri.toString())
        assertEquals("certificate.p12", result.name)
        assertEquals("bookmark", result.accessToken)
        assertNull(result.size)
    }

    @Test
    fun staleResultsCannotConsumeAnotherRequestAndCloseDropsCallbacks() {
        val bridge = FilePickerBridge()
        val requests = mutableListOf<AddFilePickerRequest>()
        val results = mutableListOf<FilePickerResult?>()
        bridge.onRequest = { requests += it }
        bridge.pick(intent { results += it })
        bridge.cancel(requests.single().requestId)
        bridge.pick(intent { results += it })
        bridge.resolve(requests.first().requestId, "file:///old.p12", null, 0, null)
        assertEquals(listOf<FilePickerResult?>(null), results)
        assertEquals(2, requests.map { it.requestId }.toSet().size)

        bridge.close()
        bridge.close()
        bridge.resolve(requests.last().requestId, "file:///late.p12", null, 0, null)
        bridge.cancel(requests.last().requestId)
        bridge.pick(intent { error("Closed session must not receive a result") })
        assertEquals(listOf<FilePickerResult?>(null), results)
        assertNull(bridge.onRequest)
        assertEquals(2, requests.size)
    }

    @Test
    fun unavailablePresenterCancelsTheRequest() {
        var cancelled = false
        FilePickerBridge().pick(intent {
            assertNull(it)
            cancelled = true
        })
        assertTrue(cancelled)
    }

    private fun intent(onResult: (FilePickerResult?) -> Unit) = FilePickerIntent.OpenDocument(
        mimeTypes = FilePickerIntent.mimeTypesPkcs12,
        readUriPermission = false,
        onResult = onResult,
    )
}
