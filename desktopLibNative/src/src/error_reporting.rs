use crate::ffi::with_ffi_boundary;
use std::ffi::c_int;
use windows::Win32::System::ErrorReporting::{
    WerGetFlags, WerSetFlags, WER_FAULT_REPORTING, WER_FAULT_REPORTING_FLAG_NOHEAP,
};
use windows::Win32::System::Threading::GetCurrentProcess;

fn current_flags() -> Result<WER_FAULT_REPORTING, String> {
    // SAFETY: The pseudo-handle is valid for the current process and needs no cleanup.
    unsafe { WerGetFlags(GetCurrentProcess()) }.map_err(|e| format!("WerGetFlags: {e}"))
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn excludeWindowsHeapFromErrorReports() {
    with_ffi_boundary("excludeWindowsHeapFromErrorReports", (), || {
        let flags = current_flags()?;
        // SAFETY: Only the current process is affected. Preserve its other WER settings.
        unsafe { WerSetFlags(flags | WER_FAULT_REPORTING_FLAG_NOHEAP) }
            .map_err(|e| format!("WerSetFlags: {e}"))
    })
}

#[cfg_attr(not(test), no_mangle)]
pub extern "C" fn windowsErrorReportsExcludeHeap() -> c_int {
    with_ffi_boundary("windowsErrorReportsExcludeHeap", 0, || {
        Ok(c_int::from(
            current_flags()?.contains(WER_FAULT_REPORTING_FLAG_NOHEAP),
        ))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::System::ErrorReporting::WER_FAULT_REPORTING_FLAG_QUEUE;

    #[test]
    fn heap_exclusion_preserves_existing_wer_flags() {
        let original = current_flags().unwrap();
        let seeded = (original | WER_FAULT_REPORTING_FLAG_QUEUE) & !WER_FAULT_REPORTING_FLAG_NOHEAP;
        // SAFETY: Only the test process's WER flags are changed.
        unsafe { WerSetFlags(seeded) }.unwrap();
        excludeWindowsHeapFromErrorReports();
        let observed = current_flags();
        let excluded = windowsErrorReportsExcludeHeap();
        // SAFETY: Restore the same process's original flags before asserting.
        unsafe { WerSetFlags(original) }.unwrap();
        assert_eq!(observed.unwrap(), seeded | WER_FAULT_REPORTING_FLAG_NOHEAP);
        assert_eq!(excluded, 1);
    }
}
