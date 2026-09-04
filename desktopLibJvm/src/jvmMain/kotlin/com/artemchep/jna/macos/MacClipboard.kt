package com.artemchep.jna.macos

import com.artemchep.jna.util.asMemory
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer

// Apple's public option for keeping pasteboard contents off Universal Clipboard and on this Mac.
// https://developer.apple.com/documentation/appkit/nspasteboard/contentsoptions/currenthostonly
private const val NS_PASTEBOARD_CONTENTS_CURRENT_HOST_ONLY = 1L shl 0
private const val NS_PASTEBOARD_TYPE_STRING = "public.utf8-plain-text"

// De facto confidentiality marker respected by participating clipboard-history applications.
// It is advisory and does not prevent other local applications from reading the plaintext.
// https://nspasteboard.org/
private const val NS_PASTEBOARD_TYPE_CONCEALED = "org.nspasteboard.ConcealedType"

private val macClipboardWriter = MacClipboardWriter(NativeMacClipboardOperations)

public fun setMacClipboardText(
    value: String,
    concealed: Boolean,
): Boolean = macClipboardWriter.setText(value, concealed)

internal class MacClipboardWriter(
    private val operations: MacClipboardOperations,
) {
    fun setText(
        value: String,
        concealed: Boolean,
    ): Boolean {
        if (!operations.isMac) {
            return false
        }
        return runCatching {
            operations.setText(value, concealed)
        }.getOrDefault(false)
    }
}

internal interface MacClipboardOperations {
    val isMac: Boolean

    fun setText(
        value: String,
        concealed: Boolean,
    ): Boolean
}

private object NativeMacClipboardOperations : MacClipboardOperations {
    override val isMac: Boolean
        get() = Platform.isMac()

    // NSPasteboard is a client of the pasteboard server and may be used from any thread.
    // Do not hop onto the AppKit main queue with dispatch_sync here: AWT waits for the
    // event dispatch thread by spinning the main run loop in a private mode that does not
    // drain the GCD main queue, so a synchronous hop from the event dispatch thread can
    // deadlock the app.
    override fun setText(
        value: String,
        concealed: Boolean,
    ): Boolean = nativeSetText(value, concealed)
}

private fun nativeSetText(
    value: String,
    concealed: Boolean,
): Boolean {
    val autoreleasePool = MacClipboardObjectiveC.sendPointer(
        receiver = MacClipboardObjectiveC.sendPointer(
            receiver = MacClipboardObjectiveC.clazz("NSAutoreleasePool"),
            selector = MacClipboardObjectiveC.selector("alloc"),
        ),
        selector = MacClipboardObjectiveC.selector("init"),
    )
    return try {
        val pasteboard = MacClipboardObjectiveC.sendPointer(
            receiver = MacClipboardObjectiveC.clazz("NSPasteboard"),
            selector = MacClipboardObjectiveC.selector("generalPasteboard"),
        )
        // prepareForNewContents options persist, so explicitly pass zero for ordinary copies to
        // ensure a previous concealed copy does not leave Universal Clipboard disabled.
        val options = if (concealed) {
            NS_PASTEBOARD_CONTENTS_CURRENT_HOST_ONLY
        } else {
            0L
        }
        MacClipboardObjectiveC.sendVoid(
            receiver = pasteboard,
            selector = MacClipboardObjectiveC.selector("prepareForNewContentsWithOptions:"),
            value = options,
        )

        val text = MacClipboardObjectiveC.nsString(value)
        val stringType = MacClipboardObjectiveC.nsString(NS_PASTEBOARD_TYPE_STRING)
        val textWritten = MacClipboardObjectiveC.sendBoolean(
            receiver = pasteboard,
            selector = MacClipboardObjectiveC.selector("setString:forType:"),
            text,
            stringType,
        )
        if (!textWritten || !concealed) {
            return textWritten
        }

        val emptyData = MacClipboardObjectiveC.sendPointer(
            receiver = MacClipboardObjectiveC.clazz("NSData"),
            selector = MacClipboardObjectiveC.selector("data"),
        )
        val concealedType = MacClipboardObjectiveC.nsString(NS_PASTEBOARD_TYPE_CONCEALED)
        MacClipboardObjectiveC.sendBoolean(
            receiver = pasteboard,
            selector = MacClipboardObjectiveC.selector("setData:forType:"),
            emptyData,
            concealedType,
        )
    } finally {
        MacClipboardObjectiveC.sendVoid(
            receiver = autoreleasePool,
            selector = MacClipboardObjectiveC.selector("drain"),
        )
    }
}

private object MacClipboardObjectiveC {
    private val appKit: NativeLibrary by lazy {
        NativeLibrary.getInstance("AppKit")
    }
    private val library: NativeLibrary by lazy {
        NativeLibrary.getInstance("objc")
    }
    private val objcGetClass: Function by lazy {
        library.getFunction("objc_getClass")
    }
    private val objcMsgSend: Function by lazy {
        library.getFunction("objc_msgSend")
    }
    private val selRegisterName: Function by lazy {
        library.getFunction("sel_registerName")
    }

    fun clazz(name: String): Pointer {
        appKit
        return objcGetClass.invokePointer(arrayOf(name))
    }

    fun selector(name: String): Pointer =
        selRegisterName.invokePointer(arrayOf(name))

    fun nsString(value: String): Pointer {
        val disposableMemory = value.asMemory()
        return try {
            sendPointer(
                receiver = clazz("NSString"),
                selector = selector("stringWithUTF8String:"),
                disposableMemory.memory,
            )
        } finally {
            disposableMemory.dispose()
        }
    }

    fun sendBoolean(
        receiver: Pointer,
        selector: Pointer,
        vararg args: Any?,
    ): Boolean = invokeArgs(receiver, selector, args)
        .let(objcMsgSend::invokeInt) != 0

    fun sendPointer(
        receiver: Pointer,
        selector: Pointer,
        vararg args: Any?,
    ): Pointer = objcMsgSend.invokePointer(
        invokeArgs(receiver, selector, args),
    )

    fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
        vararg args: Any?,
    ) {
        objcMsgSend.invokeVoid(
            invokeArgs(receiver, selector, args),
        )
    }

    fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
        value: Long,
    ) {
        objcMsgSend.invokeVoid(
            arrayOf(
                receiver,
                selector,
                NativeLong(value),
            ),
        )
    }

    private fun invokeArgs(
        receiver: Pointer,
        selector: Pointer,
        args: Array<out Any?>,
    ): Array<Any?> = arrayOf(
        receiver,
        selector,
        *args,
    )
}
