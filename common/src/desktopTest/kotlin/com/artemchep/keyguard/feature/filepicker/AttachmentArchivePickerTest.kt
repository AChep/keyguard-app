package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentArchivePickerTest {
    @Test
    fun cancellingThePickerCompletesTheExportRequest() = runTest {
        var completed = false
        val intent = FilePickerIntent.NewDocument("file.txt") { result ->
            completed = true
            assertNull(result)
        }
        handleNewDocument(intent, messages()) { null }
        assertTrue(completed)
    }

    @Test
    fun unavailablePickerCompletesTheRequestAndReportsTheFailure() = runTest {
        var completed = false
        val errors = mutableListOf<ToastMessage>()
        val intent = FilePickerIntent.NewDocument("file.txt") { result ->
            completed = true
            assertNull(result)
        }
        handleNewDocument(intent, messages(errors)) { throw IOException("Picker unavailable") }
        assertTrue(completed)
        assertEquals(ToastMessage.Type.ERROR, errors.single().type)
    }

    @Test
    fun closingThePreviewDoesNotDeliverALatePickerResult() = runTest {
        var completed = false
        val intent = FilePickerIntent.NewDocument("file.txt") { completed = true }
        assertFailsWith<CancellationException> {
            handleNewDocument(intent, messages()) { throw CancellationException() }
        }
        assertEquals(false, completed)
    }

    private fun messages(output: MutableList<ToastMessage> = mutableListOf()) = object : ShowMessage {
        override fun copy(value: ToastMessage, target: String?) {
            output += value
        }
    }
}
