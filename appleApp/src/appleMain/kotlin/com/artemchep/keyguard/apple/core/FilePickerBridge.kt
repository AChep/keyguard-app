package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult

/** Main-confined picker continuations owned by one screen session. */
internal class FilePickerBridge {
    var onRequest: ((AddFilePickerRequest) -> Unit)? = null
    private val handlers = mutableMapOf<String, (FilePickerResult?) -> Unit>()
    private var nextRequest = 0L
    private var closed = false

    fun pick(intent: FilePickerIntent<*>) {
        if (closed) return
        val handler = onRequest
        if (handler == null) {
            intent.onFilePickerResult(null)
            return
        }
        val id = "file:${nextRequest++}"
        handlers[id] = intent.onFilePickerResult
        handler(intent.toFilePickerRequest(id, ::AddFilePickerRequest))
    }

    fun resolve(requestId: String, uri: String, name: String?, size: Long, accessToken: String?) {
        handlers.remove(requestId)?.invoke(filePickerResultOf(uri, name, size, accessToken))
    }

    fun cancel(requestId: String) {
        handlers.remove(requestId)?.invoke(null)
    }

    /** The owner's scope cancels outstanding work; late picker callbacks are ignored. */
    fun close() {
        closed = true
        handlers.clear()
        onRequest = null
    }
}
