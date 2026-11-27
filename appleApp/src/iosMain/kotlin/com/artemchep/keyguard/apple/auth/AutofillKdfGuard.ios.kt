package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.common.model.MasterKdf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.TASK_VM_INFO_COUNT
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t

// iOS kills an AutoFill extension over its memory limit, which is much
// lower than the one of the app.
internal actual fun platformAutofillKdfAllows(): ((MasterKdf) -> Boolean)? =
    { kdf -> isKdfWithinAutofillBudget(kdf, ::availableMemoryBytes) }

/**
 * The memory the system still lets the process allocate, the same value
 * `os_proc_available_memory()` returns. `null` if it is unknown: the call
 * fails, or the process has no limit (the Simulator).
 */
@OptIn(ExperimentalForeignApi::class)
private fun availableMemoryBytes(): Long? = memScoped {
    val info = alloc<task_vm_info_data_t>()
    val count = alloc<mach_msg_type_number_tVar>()
    count.value = TASK_VM_INFO_COUNT
    val result = task_info(
        mach_task_self_,
        TASK_VM_INFO.toUInt(),
        info.ptr.reinterpret(),
        count.ptr,
    )
    val remaining = info.limit_bytes_remaining.toLong()
    remaining.takeIf { result == KERN_SUCCESS && it > 0L }
}
