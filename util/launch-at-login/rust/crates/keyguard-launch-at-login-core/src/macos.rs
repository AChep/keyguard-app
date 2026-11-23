use crate::{Error, Failure, Operation, Status};

unsafe extern "C" {
    fn kg_lal_macos(operation: i32, diagnostic: *mut u8, capacity: usize) -> i32;
    fn kg_lal_macos_launch_event() -> i32;
    fn kg_lal_macos_prepare_launch_detection();
}

pub(crate) fn execute(operation: Operation) -> Result<Status, Failure> {
    let mut diagnostic = [0u8; 4096];
    // SAFETY: The synchronous shim only writes within the supplied live buffer.
    let result =
        unsafe { kg_lal_macos(operation as i32, diagnostic.as_mut_ptr(), diagnostic.len()) };
    match result {
        0 => Ok(Status::Enabled),
        1 => Ok(Status::Disabled),
        2 => Ok(Status::RequiresApproval),
        4 => Ok(Status::Unavailable),
        6 => Ok(Status::Unknown),
        _ => {
            let length = diagnostic
                .iter()
                .position(|b| *b == 0)
                .unwrap_or(diagnostic.len());
            let message = String::from_utf8_lossy(&diagnostic[..length]);
            Err(Failure::new(
                if result == -2 {
                    Error::Unavailable
                } else {
                    Error::System
                },
                message,
            ))
        }
    }
}

/// The shim latches a positive result for the process lifetime.
pub(crate) fn launched_at_login() -> bool {
    // SAFETY: The shim checks its thread and reads the current Apple event without retaining it.
    unsafe { kg_lal_macos_launch_event() != 0 }
}

pub(crate) fn prepare_launch_detection() {
    // SAFETY: The shim installs one process-lifetime notification observer; it retains no Rust memory.
    unsafe { kg_lal_macos_prepare_launch_detection() };
}
