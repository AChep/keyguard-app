use crate::ffi::{require_string, BiometricsVerifyCallback};
use std::ffi::{c_char, CString};
use windows::core::{factory, HSTRING};
use windows::Security::Credentials::UI::{
    UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability,
};
use windows::Win32::Foundation::{HWND, RPC_E_CHANGED_MODE};
use windows::Win32::System::Threading::GetCurrentProcessId;
use windows::Win32::System::WinRT::{
    IUserConsentVerifierInterop, RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED,
};
use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};
use windows_future::IAsyncOperation;

pub(crate) fn is_supported() -> bool {
    check_availability()
        .map(|availability| availability == UserConsentVerifierAvailability::Available)
        .unwrap_or(false)
}

pub(crate) fn verify(title: *const c_char, callback: BiometricsVerifyCallback) {
    let Some(callback) = callback else {
        return;
    };

    let result = require_string(title, "title").and_then(request_verification);
    match result {
        Ok(()) => {
            // SAFETY: The non-null callback came from the exported C ABI and is
            // invoked synchronously with the declared arguments. A null error
            // pointer is the documented success representation.
            unsafe { callback(true, std::ptr::null()) }
        }
        Err(message) => callback_failure(callback, message),
    }
}

fn check_availability() -> Result<UserConsentVerifierAvailability, String> {
    let _apartment = initialize_winrt()?;
    UserConsentVerifier::CheckAvailabilityAsync()
        .and_then(|operation| operation.join())
        .map_err(|error| error.to_string())
}

fn request_verification(title: String) -> Result<(), String> {
    let _apartment = initialize_winrt()?;
    let hwnd = foreground_window_for_current_process()?;
    let interop = factory::<UserConsentVerifier, IUserConsentVerifierInterop>()
        .map_err(|error| error.to_string())?;
    // SAFETY: `hwnd` is non-null and was verified to belong to this process;
    // `interop` and the HSTRING remain alive until the call returns its async
    // operation object.
    let operation: IAsyncOperation<UserConsentVerificationResult> =
        unsafe { interop.RequestVerificationForWindowAsync(hwnd, &HSTRING::from(title)) }
            .map_err(|error| error.to_string())?;
    let result = operation.join().map_err(|error| error.to_string())?;
    if result == UserConsentVerificationResult::Verified {
        Ok(())
    } else {
        Err(format!(
            "Windows Hello verification did not succeed: {result:?}"
        ))
    }
}

struct WinRtApartment {
    uninitialize: bool,
}

impl Drop for WinRtApartment {
    fn drop(&mut self) {
        if self.uninitialize {
            // SAFETY: This guard calls RoUninitialize exactly once only when
            // the matching RoInitialize invocation succeeded on this thread.
            unsafe {
                RoUninitialize();
            }
        }
    }
}

fn initialize_winrt() -> Result<WinRtApartment, String> {
    // SAFETY: RoInitialize is called on the current thread and balanced by the
    // guard below when it succeeds. RPC_E_CHANGED_MODE means another valid
    // apartment already owns this thread and must not be uninitialized here.
    match unsafe { RoInitialize(RO_INIT_MULTITHREADED) } {
        Ok(()) => Ok(WinRtApartment { uninitialize: true }),
        Err(error) if error.code() == RPC_E_CHANGED_MODE => Ok(WinRtApartment {
            uninitialize: false,
        }),
        Err(error) => Err(error.to_string()),
    }
}

fn foreground_window_for_current_process() -> Result<HWND, String> {
    // SAFETY: GetForegroundWindow takes no pointers and returns a borrowed HWND
    // that is checked for null before any use.
    let hwnd = unsafe { GetForegroundWindow() };
    if hwnd.0.is_null() {
        return Err("Keyguard does not have a foreground window".to_owned());
    }

    let mut owner_process_id = 0_u32;
    // SAFETY: `hwnd` is non-null and the output pointer refers to a live local
    // u32 for the duration of the call.
    unsafe {
        GetWindowThreadProcessId(hwnd, Some(&mut owner_process_id));
    }
    // SAFETY: GetCurrentProcessId takes no arguments and has no preconditions.
    let current_process_id = unsafe { GetCurrentProcessId() };
    if owner_process_id != current_process_id {
        return Err("Keyguard is not the foreground application".to_owned());
    }

    Ok(hwnd)
}

fn callback_failure(callback: unsafe extern "C" fn(bool, *const c_char), message: String) {
    let message = CString::new(message)
        .unwrap_or_else(|_| CString::new("Windows Hello verification failed").unwrap());
    // SAFETY: The callback came from the exported C ABI, and `message` remains
    // alive and NUL-terminated for the entire synchronous invocation.
    unsafe {
        callback(false, message.as_ptr());
    }
}

#[cfg(test)]
mod tests {
    use super::{callback_failure, check_availability};
    use std::ffi::{c_char, CStr};
    use std::sync::Mutex;

    static MESSAGE: Mutex<Option<String>> = Mutex::new(None);

    unsafe extern "C" fn record_failure(success: bool, error: *const c_char) {
        assert!(!success);
        assert!(!error.is_null());
        // SAFETY: The production callback supplies a non-null, NUL-terminated
        // CString which stays alive for this synchronous callback.
        let message = unsafe { CStr::from_ptr(error) }
            .to_string_lossy()
            .into_owned();
        *MESSAGE.lock().unwrap() = Some(message);
    }

    #[test]
    fn windows_hello_availability_query_completes() {
        let result = check_availability();
        assert!(result.is_ok(), "availability query failed: {result:?}");
    }

    #[test]
    fn failure_callback_receives_an_error_message() {
        callback_failure(record_failure, "not verified".to_owned());
        assert_eq!(MESSAGE.lock().unwrap().as_deref(), Some("not verified"));
    }
}
