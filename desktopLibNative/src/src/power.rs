use crate::ffi::PowerEventCallback;

#[cfg(target_os = "macos")]
unsafe extern "C" {
    fn kg_register_native_power_events(callback: PowerEventCallback) -> i32;
    fn kg_unregister_native_power_events(id: i32) -> bool;
}

#[cfg(target_os = "macos")]
pub(crate) fn register(callback: PowerEventCallback) -> i32 {
    // SAFETY: The exported registration contract requires callback retention
    // through unregistration. The shim serializes registration, delivery,
    // and removal on the AppKit main thread using the matching C ABI.
    unsafe { kg_register_native_power_events(callback) }
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn register(_callback: PowerEventCallback) -> i32 {
    crate::ffi::REGISTER_STATUS_UNSUPPORTED_PLATFORM
}

#[cfg(target_os = "macos")]
pub(crate) fn unregister(id: i32) -> bool {
    // SAFETY: The shim accepts the scalar ID by value and returns only after
    // removing the observers; no callback can remain in flight on success.
    unsafe { kg_unregister_native_power_events(id) }
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn unregister(_id: i32) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use crate::ffi::REGISTER_STATUS_INTERNAL_ERROR;
    #[cfg(not(target_os = "macos"))]
    use crate::ffi::REGISTER_STATUS_UNSUPPORTED_PLATFORM;

    #[test]
    fn null_callback_is_rejected_before_platform_registration() {
        assert_eq!(
            // SAFETY: A null callback is rejected without dereferencing it.
            unsafe { crate::registerNativePowerEvents(None) },
            REGISTER_STATUS_INTERNAL_ERROR
        );
    }

    #[cfg(not(target_os = "macos"))]
    #[test]
    fn unsupported_platform_does_not_register() {
        assert_eq!(super::register(None), REGISTER_STATUS_UNSUPPORTED_PLATFORM);
        assert!(!super::unregister(1));
    }
}
