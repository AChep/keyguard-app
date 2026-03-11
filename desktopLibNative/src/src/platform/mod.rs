#[cfg(target_os = "macos")]
#[path = "macos/autotype.rs"]
pub(crate) mod autotype;
#[cfg(not(target_os = "macos"))]
#[path = "stub/autotype.rs"]
pub(crate) mod autotype;

#[cfg(target_os = "macos")]
#[path = "macos/biometrics.rs"]
pub(crate) mod biometrics;
#[cfg(not(target_os = "macos"))]
#[path = "stub/biometrics.rs"]
pub(crate) mod biometrics;

#[cfg(target_os = "macos")]
#[path = "macos/keychain.rs"]
pub(crate) mod keychain;
#[cfg(not(target_os = "macos"))]
#[path = "stub/keychain.rs"]
pub(crate) mod keychain;

#[cfg(target_os = "macos")]
#[path = "macos/notification.rs"]
pub(crate) mod notification;
#[cfg(not(target_os = "macos"))]
#[path = "stub/notification.rs"]
pub(crate) mod notification;

#[cfg(target_os = "macos")]
#[path = "macos/hotkey.rs"]
pub(crate) mod hotkey;
#[cfg(target_os = "windows")]
#[path = "windows/hotkey.rs"]
pub(crate) mod hotkey;
#[cfg(target_os = "linux")]
#[path = "linux/hotkey.rs"]
pub(crate) mod hotkey;
#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
#[path = "stub/hotkey.rs"]
pub(crate) mod hotkey;
