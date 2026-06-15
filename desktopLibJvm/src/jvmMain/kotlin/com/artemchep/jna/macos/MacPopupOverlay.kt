package com.artemchep.jna.macos

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.suspendCoroutine

private const val NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_SPACES = 1L shl 0
private const val NS_WINDOW_COLLECTION_BEHAVIOR_MOVE_TO_ACTIVE_SPACE = 1L shl 1
private const val NS_WINDOW_COLLECTION_BEHAVIOR_MANAGED = 1L shl 2
private const val NS_WINDOW_COLLECTION_BEHAVIOR_TRANSIENT = 1L shl 3
private const val NS_WINDOW_COLLECTION_BEHAVIOR_STATIONARY = 1L shl 4
private const val NS_WINDOW_COLLECTION_BEHAVIOR_IGNORES_CYCLE = 1L shl 6
private const val NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_PRIMARY = 1L shl 7
private const val NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_AUXILIARY = 1L shl 8
private const val NS_WINDOW_COLLECTION_BEHAVIOR_PRIMARY = 1L shl 16
private const val NS_WINDOW_COLLECTION_BEHAVIOR_AUXILIARY = 1L shl 17
private const val NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_APPLICATIONS = 1L shl 18

private const val NS_MODAL_PANEL_WINDOW_LEVEL = 8L
private const val NS_APPLICATION_ACTIVATION_POLICY_REGULAR = 0L
private const val NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY = 1L

public val macPopupOverlayManager: MacPopupOverlayManager = MacPopupOverlayManager()

public class MacPopupOverlayManager internal constructor(
    private val operations: MacPopupOverlayOperations,
) {
    private val lock = Any()
    private val beginMutex = Mutex()
    private var referenceCount = 0
    private var generation = 0L

    public constructor() : this(NativeMacPopupOverlayOperations)

    public suspend fun beginPopupSession(): AutoCloseable? {
        if (!operations.isMac) {
            return null
        }

        return beginMutex.withLock {
            val generationToApply = synchronized(lock) {
                if (referenceCount > 0) {
                    referenceCount += 1
                    return@withLock Session(::end)
                }

                generation += 1
                generation
            }

            val applied = runCatching {
                operations.runOnMainThread {
                    operations.setApplicationActivationPolicyOnMainThread(
                        NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY,
                    )
                }
            }.getOrDefault(false)
            if (!applied) {
                runCatching {
                    operations.runOnMainThread {
                        operations.setApplicationActivationPolicyOnMainThread(
                            NS_APPLICATION_ACTIVATION_POLICY_REGULAR,
                        )
                    }
                }
                return@withLock null
            }

            synchronized(lock) {
                if (referenceCount == 0 && generation == generationToApply) {
                    referenceCount = 1
                    Session(::end)
                } else {
                    null
                }
            }
        }
    }

    public fun applyOverlay(
        windowHandle: Long,
        makeKeyWindow: Boolean = true,
    ): Boolean? {
        if (!operations.isMac || windowHandle == 0L) {
            return null
        }

        return runCatching {
            operations.runOnMainThreadAsync {
                applyOverlayOnMainThread(windowHandle, makeKeyWindow)
            }
        }.getOrNull()
    }

    public suspend fun applyOverlayAndWait(
        windowHandle: Long,
        makeKeyWindow: Boolean = true,
    ): Boolean? {
        if (!operations.isMac || windowHandle == 0L) {
            return null
        }

        return runCatching {
            operations.runOnMainThread {
                applyOverlayOnMainThread(windowHandle, makeKeyWindow)
            }
        }.getOrNull()
    }

    private fun applyOverlayOnMainThread(
        windowHandle: Long,
        makeKeyWindow: Boolean,
    ): Boolean {
        operations.setPopupOverlayCollectionBehaviorOnMainThread(windowHandle)
        operations.preparePopupOverlayWindowOnMainThread(windowHandle)
        operations.orderFrontRegardlessOnMainThread(windowHandle)
        operations.activateApplicationOnMainThread()
        return !makeKeyWindow ||
            operations.makeKeyWindowIfPossibleOnMainThread(windowHandle)
    }

    private fun end() {
        val generationToRestore = synchronized(lock) {
            if (referenceCount == 0) {
                return
            }

            referenceCount -= 1
            if (referenceCount == 0) {
                generation
            } else {
                null
            }
        }

        generationToRestore?.let(::scheduleRegularActivationPolicy)
    }

    private fun scheduleRegularActivationPolicy(generationToRestore: Long) {
        runCatching {
            operations.runOnMainThreadAsync {
                val shouldRestore = synchronized(lock) {
                    referenceCount == 0 && generation == generationToRestore
                }
                if (shouldRestore) {
                    operations.setApplicationActivationPolicyOnMainThread(
                        NS_APPLICATION_ACTIVATION_POLICY_REGULAR,
                    )
                }
            }
        }
    }

    private class Session(
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose()
            }
        }
    }
}

internal interface MacPopupOverlayOperations {
    val isMac: Boolean

    suspend fun <T> runOnMainThread(
        block: () -> T,
    ): T

    fun runOnMainThreadAsync(
        block: () -> Unit,
    ): Boolean

    fun setApplicationActivationPolicyOnMainThread(
        activationPolicy: Long,
    ): Boolean

    fun setPopupOverlayCollectionBehaviorOnMainThread(
        windowHandle: Long,
    )

    fun preparePopupOverlayWindowOnMainThread(
        windowHandle: Long,
    )

    fun orderFrontRegardlessOnMainThread(
        windowHandle: Long,
    )

    fun activateApplicationOnMainThread()

    fun makeKeyWindowIfPossibleOnMainThread(
        windowHandle: Long,
    ): Boolean
}

private object NativeMacPopupOverlayOperations : MacPopupOverlayOperations {
    override val isMac: Boolean
        get() = Platform.isMac()

    override suspend fun <T> runOnMainThread(
        block: () -> T,
    ): T = Dispatch.runOnMainThread(block)

    override fun runOnMainThreadAsync(
        block: () -> Unit,
    ): Boolean = Dispatch.runOnMainThreadAsync(block)

    override fun setApplicationActivationPolicyOnMainThread(
        activationPolicy: Long,
    ): Boolean = nativeSetApplicationActivationPolicyOnMainThread(activationPolicy)

    override fun setPopupOverlayCollectionBehaviorOnMainThread(
        windowHandle: Long,
    ) {
        val nsWindow = Pointer.createConstant(windowHandle)
        nativeSetPopupOverlayCollectionBehaviorOnMainThread(nsWindow)
    }

    override fun preparePopupOverlayWindowOnMainThread(
        windowHandle: Long,
    ) {
        val nsWindow = Pointer.createConstant(windowHandle)
        nativePreparePopupOverlayWindowOnMainThread(nsWindow)
    }

    override fun orderFrontRegardlessOnMainThread(
        windowHandle: Long,
    ) {
        val nsWindow = Pointer.createConstant(windowHandle)
        ObjectiveC.sendVoid(
            receiver = nsWindow,
            selector = ObjectiveC.selector("orderFrontRegardless"),
        )
    }

    override fun activateApplicationOnMainThread() {
        nativeActivateApplicationOnMainThread()
    }

    override fun makeKeyWindowIfPossibleOnMainThread(
        windowHandle: Long,
    ): Boolean {
        val nsWindow = Pointer.createConstant(windowHandle)
        return nativeMakeKeyWindowIfPossibleOnMainThread(nsWindow)
    }
}

private fun nativeSetPopupOverlayCollectionBehaviorOnMainThread(
    nsWindow: Pointer,
) {
    val requiredBehavior =
        NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_SPACES or
            NS_WINDOW_COLLECTION_BEHAVIOR_STATIONARY or
            NS_WINDOW_COLLECTION_BEHAVIOR_IGNORES_CYCLE or
            NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_AUXILIARY or
            NS_WINDOW_COLLECTION_BEHAVIOR_CAN_JOIN_ALL_APPLICATIONS
    val conflictingBehavior =
        NS_WINDOW_COLLECTION_BEHAVIOR_MOVE_TO_ACTIVE_SPACE or
            NS_WINDOW_COLLECTION_BEHAVIOR_MANAGED or
            NS_WINDOW_COLLECTION_BEHAVIOR_TRANSIENT or
            NS_WINDOW_COLLECTION_BEHAVIOR_PRIMARY or
            NS_WINDOW_COLLECTION_BEHAVIOR_AUXILIARY or
            NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_PRIMARY

    val collectionBehaviorSelector = ObjectiveC.selector("collectionBehavior")
    val currentBehavior = ObjectiveC.sendLong(
        receiver = nsWindow,
        selector = collectionBehaviorSelector,
    )
    val newBehavior = (currentBehavior and conflictingBehavior.inv()) or requiredBehavior
    ObjectiveC.sendVoid(
        receiver = nsWindow,
        selector = ObjectiveC.selector("setCollectionBehavior:"),
        value = newBehavior,
    )
}

private fun nativePreparePopupOverlayWindowOnMainThread(
    nsWindow: Pointer,
) {
    ObjectiveC.sendVoid(
        receiver = nsWindow,
        selector = ObjectiveC.selector("setCanHide:"),
        value = false,
    )
    ObjectiveC.sendVoid(
        receiver = nsWindow,
        selector = ObjectiveC.selector("setHidesOnDeactivate:"),
        value = false,
    )
    ObjectiveC.sendVoid(
        receiver = nsWindow,
        selector = ObjectiveC.selector("setLevel:"),
        value = NS_MODAL_PANEL_WINDOW_LEVEL,
    )
}

private fun nativeMakeKeyWindowIfPossibleOnMainThread(
    nsWindow: Pointer,
): Boolean {
    val canBecomeKeyWindow = ObjectiveC.sendBoolean(
        receiver = nsWindow,
        selector = ObjectiveC.selector("canBecomeKeyWindow"),
    )
    if (!canBecomeKeyWindow) {
        return false
    }

    val canBecomeMainWindow = ObjectiveC.sendBoolean(
        receiver = nsWindow,
        selector = ObjectiveC.selector("canBecomeMainWindow"),
    )
    if (canBecomeMainWindow) {
        ObjectiveC.sendVoid(
            receiver = nsWindow,
            selector = ObjectiveC.selector("makeMainWindow"),
        )
    }
    ObjectiveC.sendVoid(
        receiver = nsWindow,
        selector = ObjectiveC.selector("makeKeyAndOrderFront:"),
        Pointer.NULL,
    )
    return true
}

private fun nativeSetApplicationActivationPolicyOnMainThread(
    activationPolicy: Long,
): Boolean =
    ObjectiveC.sendBoolean(
        receiver = nativeApplicationOnMainThread(),
        selector = ObjectiveC.selector("setActivationPolicy:"),
        value = activationPolicy,
    )

private fun nativeActivateApplicationOnMainThread() {
    ObjectiveC.sendVoid(
        receiver = nativeApplicationOnMainThread(),
        selector = ObjectiveC.selector("activateIgnoringOtherApps:"),
        value = true,
    )
}

private fun nativeApplicationOnMainThread(): Pointer =
    ObjectiveC.sendPointer(
        receiver = ObjectiveC.clazz("NSApplication"),
        selector = ObjectiveC.selector("sharedApplication"),
    )

private object ObjectiveC {
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

    fun clazz(name: String): Pointer =
        objcGetClass.invokePointer(arrayOf(name))

    fun selector(name: String): Pointer =
        selRegisterName.invokePointer(arrayOf(name))

    fun sendBoolean(
        receiver: Pointer,
        selector: Pointer,
    ): Boolean = objcMsgSend.invokeInt(
        arrayOf(
            receiver,
            selector,
        ),
    ) != 0

    fun sendBoolean(
        receiver: Pointer,
        selector: Pointer,
        value: Long,
    ): Boolean = objcMsgSend.invokeInt(
        arrayOf(
            receiver,
            selector,
            NativeLong(value),
        ),
    ) != 0

    fun sendLong(
        receiver: Pointer,
        selector: Pointer,
    ): Long = objcMsgSend.invokeLong(
        arrayOf(
            receiver,
            selector,
        ),
    )

    fun sendPointer(
        receiver: Pointer,
        selector: Pointer,
        vararg args: Any?,
    ): Pointer {
        val allArgs = arrayOfNulls<Any>(2 + args.size)
        allArgs[0] = receiver
        allArgs[1] = selector
        args.forEachIndexed { index, arg ->
            allArgs[index + 2] = arg
        }
        return objcMsgSend.invokePointer(allArgs)
    }

    fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
        vararg args: Any?,
    ) {
        val allArgs = arrayOfNulls<Any>(2 + args.size)
        allArgs[0] = receiver
        allArgs[1] = selector
        args.forEachIndexed { index, arg ->
            allArgs[index + 2] = arg
        }
        objcMsgSend.invokeVoid(allArgs)
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

    fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
        value: Boolean,
    ) {
        objcMsgSend.invokeVoid(
            arrayOf(
                receiver,
                selector,
                if (value) 1 else 0,
            ),
        )
    }
}

private object Dispatch {
    private val library: NativeLibrary by lazy {
        NativeLibrary.getInstance("System")
    }
    private val dispatchMainQueue: Pointer by lazy {
        library.getGlobalVariableAddress("_dispatch_main_q")
    }
    private val dispatchAsyncF: Function by lazy {
        library.getFunction("dispatch_async_f")
    }
    private val asyncCallbacks = Collections.synchronizedSet(
        Collections.newSetFromMap(
            IdentityHashMap<DispatchFunction, Boolean>(),
        ),
    )

    suspend fun <T> runOnMainThread(block: () -> T): T {
        if (isMainThread()) {
            return block()
        }

        return suspendCoroutine { continuation ->
            lateinit var work: DispatchFunction
            work = object : DispatchFunction {
                override fun callback(context: Pointer?) {
                    try {
                        continuation.resumeWith(runCatching(block))
                    } finally {
                        asyncCallbacks.remove(this)
                    }
                }
            }
            asyncCallbacks.add(work)
            try {
                dispatchAsyncF.invokeVoid(
                    arrayOf(
                        dispatchMainQueue,
                        Pointer.NULL,
                        work,
                    ),
                )
            } catch (e: LinkageError) {
                asyncCallbacks.remove(work)
                continuation.resumeWith(Result.failure(e))
            } catch (e: RuntimeException) {
                asyncCallbacks.remove(work)
                continuation.resumeWith(Result.failure(e))
            }
        }
    }

    fun runOnMainThreadAsync(block: () -> Unit): Boolean {
        if (isMainThread()) {
            block()
            return true
        }

        lateinit var work: DispatchFunction
        work = object : DispatchFunction {
            override fun callback(context: Pointer?) {
                try {
                    runCatching(block)
                } finally {
                    asyncCallbacks.remove(this)
                }
            }
        }
        asyncCallbacks.add(work)
        return try {
            dispatchAsyncF.invokeVoid(
                arrayOf(
                    dispatchMainQueue,
                    Pointer.NULL,
                    work,
                ),
            )
            true
        } catch (e: LinkageError) {
            asyncCallbacks.remove(work)
            false
        } catch (e: RuntimeException) {
            asyncCallbacks.remove(work)
            false
        }
    }

    private fun isMainThread(): Boolean {
        val nsThread = ObjectiveC.clazz("NSThread")
        return ObjectiveC.sendBoolean(
            receiver = nsThread,
            selector = ObjectiveC.selector("isMainThread"),
        )
    }

    private interface DispatchFunction : com.sun.jna.Callback {
        fun callback(context: Pointer?)
    }
}
