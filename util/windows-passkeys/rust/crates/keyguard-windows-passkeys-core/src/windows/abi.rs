//! The version-one ABI from Microsoft's webauthnplugin.h and pluginauthenticator.h.
//! Pinned to microsoft/webauthn ef82c157125a0490e05f6ea82a7adb1b8e1bad08.
//! `abi_layout.rs` checks this against the headers in util/windows-passkeys/abi.
//! Resolve at runtime: these exports are absent on older, otherwise supported Windows.
use std::{ffi::c_void, marker::PhantomData};
use windows::{
    Win32::{
        Foundation::HWND,
        Networking::WindowsWebServices::{
            WEBAUTHN_COSE_CREDENTIAL_PARAMETERS, WEBAUTHN_CREDENTIAL_LIST,
            WEBAUTHN_RP_ENTITY_INFORMATION, WEBAUTHN_USER_ENTITY_INFORMATION,
        },
    },
    core::{GUID, HRESULT, PCWSTR},
};

#[repr(C)]
pub struct Operation {
    pub window: HWND,
    pub transaction: GUID,
    pub signature_len: u32,
    pub signature: *const u8,
    pub request_type: u32,
    pub request_len: u32,
    pub request: *const u8,
}
#[repr(C)]
pub struct OperationResponse {
    pub len: u32,
    pub bytes: *mut u8,
}
#[repr(C)]
pub struct Cancel {
    pub transaction: GUID,
    pub signature_len: u32,
    pub signature: *const u8,
}
#[repr(C)]
pub struct AddOptions {
    pub name: PCWSTR,
    pub clsid: *const GUID,
    pub rp: PCWSTR,
    pub light_logo: PCWSTR,
    pub dark_logo: PCWSTR,
    pub info_len: u32,
    pub info: *const u8,
    pub rp_count: u32,
    pub rps: *const PCWSTR,
}
#[repr(C)]
pub struct UpdateOptions {
    pub name: PCWSTR,
    pub clsid: *const GUID,
    pub new_clsid: *const GUID,
    pub light_logo: PCWSTR,
    pub dark_logo: PCWSTR,
    pub info_len: u32,
    pub info: *const u8,
    pub rp_count: u32,
    pub rps: *const PCWSTR,
}
/// Borrows its buffers for `'a`, from owned storage or the OS array that returned it.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CredentialDetails<'a> {
    pub id_len: u32,
    pub id: *const u8,
    pub rp_id: PCWSTR,
    pub rp_name: PCWSTR,
    pub user_len: u32,
    pub user: *const u8,
    pub username: PCWSTR,
    pub display_name: PCWSTR,
    /// Zero-sized, so the C layout still ends at `display_name`.
    pub buffers: PhantomData<&'a [u8]>,
}
#[repr(C)]
pub struct VerifyRequest {
    pub window: HWND,
    pub transaction: *const GUID,
    pub username: PCWSTR,
    pub hint: PCWSTR,
}
#[repr(C)]
pub struct Options {
    pub version: u32,
    pub up: i32,
    pub uv: i32,
    pub rk: i32,
}

// Only read the documented prefix; the decoder owns and frees the complete allocation.
#[repr(C)]
pub struct MakeRequest {
    pub version: u32,
    pub rp_len: u32,
    pub rp: *const u8,
    pub hash_len: u32,
    pub hash: *const u8,
    pub rp_info: *const WEBAUTHN_RP_ENTITY_INFORMATION,
    pub user: *const WEBAUTHN_USER_ENTITY_INFORMATION,
    pub algorithms: WEBAUTHN_COSE_CREDENTIAL_PARAMETERS,
    pub credentials: WEBAUTHN_CREDENTIAL_LIST,
    pub extensions_len: u32,
    pub extensions: *const u8,
    pub options: *const Options,
}
#[repr(C)]
pub struct GetRequest {
    pub version: u32,
    pub rp_utf16: PCWSTR,
    pub rp_len: u32,
    pub rp: *const u8,
    pub hash_len: u32,
    pub hash: *const u8,
    pub credentials: WEBAUTHN_CREDENTIAL_LIST,
    pub extensions_len: u32,
    pub extensions: *const u8,
    pub options: *const Options,
}

pub type GetState = unsafe extern "system" fn(*const GUID, *mut u32) -> HRESULT;
pub type Add = unsafe extern "system" fn(*const AddOptions, *mut *mut c_void) -> HRESULT;
pub type Update = unsafe extern "system" fn(*const UpdateOptions) -> HRESULT;
pub type Free = unsafe extern "system" fn(*mut c_void);
pub type Remove = unsafe extern "system" fn(*const GUID) -> HRESULT;
pub type GetKey = unsafe extern "system" fn(*const GUID, *mut u32, *mut *mut u8) -> HRESULT;
pub type Verify =
    unsafe extern "system" fn(*const VerifyRequest, *mut u32, *mut *mut u8) -> HRESULT;
pub type Credentials =
    unsafe extern "system" fn(*const GUID, u32, *const CredentialDetails<'_>) -> HRESULT;
pub type GetCredentials =
    unsafe extern "system" fn(*const GUID, *mut u32, *mut *mut CredentialDetails<'_>) -> HRESULT;
pub type FreeCredentials = unsafe extern "system" fn(u32, *mut CredentialDetails<'_>);
pub type DecodeMake = unsafe extern "system" fn(u32, *const u8, *mut *mut MakeRequest) -> HRESULT;
pub type DecodeGet = unsafe extern "system" fn(u32, *const u8, *mut *mut GetRequest) -> HRESULT;
pub type StatusCallback = unsafe extern "system" fn(*mut c_void);
pub type RegisterStatus =
    unsafe extern "system" fn(StatusCallback, *mut c_void, *const GUID, *mut u32) -> HRESULT;
pub type UnregisterStatus = unsafe extern "system" fn(*mut u32) -> HRESULT;
