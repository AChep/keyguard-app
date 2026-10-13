//! Real Windows codec/provider checks. Explicitly selected; missing APIs are failures.
use super::*;
use crate::protocol::{ES256, MAX_REQUEST, UserVerification, cbor, number, text};
use ciborium::value::Value as Cbor;
use windows::Win32::{
    Networking::WindowsWebServices::{
        WEBAUTHN_ASSERTION, WEBAUTHN_CREDENTIAL, WEBAUTHN_CREDENTIAL_ATTESTATION,
        WEBAUTHN_HMAC_SECRET_SALT, WEBAUTHN_USER_ENTITY_INFORMATION,
    },
    System::Com::CoTaskMemFree,
};
use windows::core::BOOL;

pub(super) type EncodeMake =
    unsafe extern "system" fn(*const AttestationV8, *mut u32, *mut *mut u8) -> HRESULT;
pub(super) type EncodeGet =
    unsafe extern "system" fn(*const GetAssertionResponse, *mut u32, *mut *mut u8) -> HRESULT;

// windows-rs currently exposes version 6; the plugin encoder requires version 8.
// Append the fields from Microsoft's webauthn.h without changing the version-6 prefix.
#[repr(C)]
#[derive(Default)]
pub(super) struct AttestationV8 {
    pub(super) v6: WEBAUTHN_CREDENTIAL_ATTESTATION,
    pub(super) hmac_secret: *mut WEBAUTHN_HMAC_SECRET_SALT,
    pub(super) third_party_payment: BOOL,
    pub(super) transports: u32,
    pub(super) client_data_len: u32,
    pub(super) client_data: *mut u8,
    pub(super) registration_response_len: u32,
    pub(super) registration_response: *mut u8,
}

// windows-rs currently exposes version 5; webauthnplugin.h embeds version 6 by value.
// Append the fields from Microsoft's webauthn.h without changing the version-5 prefix.
#[repr(C)]
#[derive(Default)]
pub(super) struct AssertionV6 {
    pub(super) v5: WEBAUTHN_ASSERTION,
    pub(super) client_data_len: u32,
    pub(super) client_data: *mut u8,
    pub(super) authentication_response_len: u32,
    pub(super) authentication_response: *mut u8,
}

/// WEBAUTHN_CTAPCBOR_GET_ASSERTION_RESPONSE from Microsoft's webauthnplugin.h.
#[repr(C)]
#[derive(Default)]
pub(super) struct GetAssertionResponse {
    pub(super) assertion: AssertionV6,
    pub(super) user: *const WEBAUTHN_USER_ENTITY_INFORMATION,
    pub(super) number_of_credentials: u32,
    pub(super) user_selected: i32,
    pub(super) large_blob_key_len: u32,
    pub(super) large_blob_key: *mut u8,
    pub(super) unsigned_extension_outputs_len: u32,
    pub(super) unsigned_extension_outputs: *mut u8,
}

/// Required export: failing here identifies the missing API instead of passing a test.
fn export(name: &CStr) -> unsafe extern "system" fn() -> isize {
    // SAFETY: Fixed system DLL name restricted to System32, retained until process exit.
    let module =
        unsafe { LoadLibraryExW(w!("webauthn.dll"), None, LOAD_LIBRARY_SEARCH_SYSTEM32) }.unwrap();
    // SAFETY: The module is loaded and the export name is NUL terminated.
    let address = unsafe { GetProcAddress(module, PCSTR(name.as_ptr().cast())) };
    require_export(name, address)
}

pub(super) fn require_export(
    name: &CStr,
    address: Option<unsafe extern "system" fn() -> isize>,
) -> unsafe extern "system" fn() -> isize {
    address.unwrap_or_else(|| panic!("missing required webauthn.dll export: {name:?}"))
}

/// Copies the `length` bytes that a `webauthn.dll` encoder returned, then frees them.
fn encoded(result: HRESULT, length: u32, pointer: *mut u8) -> Vec<u8> {
    unsafe extern "system" fn free_response(pointer: *mut c_void) {
        // SAFETY: The WebAuthNEncode* exports return COM-allocated memory.
        unsafe { CoTaskMemFree(Some(pointer)) };
    }

    let allocation = Allocation {
        pointer: pointer.cast(),
        free: free_response,
    };
    result.unwrap();
    // SAFETY: The encoder returned `length` bytes in this buffer.
    let bytes = unsafe { allocation.bytes(length, MAX_REQUEST) }.unwrap();
    bytes.to_vec()
}

#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn registration_response_matches_windows_encoder() {
    let address = export(c"WebAuthNEncodeMakeCredentialResponse");
    // SAFETY: This is the documented export signature from webauthnplugin.h.
    let encode_make =
        unsafe { std::mem::transmute::<unsafe extern "system" fn() -> isize, EncodeMake>(address) };
    let mut auth_data = [1; 37];
    let attestation = AttestationV8 {
        v6: WEBAUTHN_CREDENTIAL_ATTESTATION {
            dwVersion: 8,
            pwszFormatType: w!("none"),
            cbAuthenticatorData: u32::try_from(auth_data.len()).unwrap(),
            pbAuthenticatorData: auth_data.as_mut_ptr(),
            ..Default::default()
        },
        ..Default::default()
    };
    let mut length = 0;
    let mut pointer = ptr::null_mut();
    // SAFETY: The complete version-8 structure and its buffer outlive this synchronous call.
    let result = unsafe { encode_make(&raw const attestation, &raw mut length, &raw mut pointer) };
    let expected = encoded(result, length, pointer);
    let actual = protocol::response(
        true,
        Response {
            authenticator_data: auth_data.to_vec(),
            credential_id: None,
            signature: None,
            user_handle: None,
        },
    )
    .unwrap();
    assert_eq!(actual, expected);
}

#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn assertion_response_matches_windows_encoder() {
    let address = export(c"WebAuthNEncodeGetAssertionResponse");
    // SAFETY: This is the documented export signature from webauthnplugin.h.
    let encode_get =
        unsafe { std::mem::transmute::<unsafe extern "system" fn() -> isize, EncodeGet>(address) };
    let auth_data = [1; 37];
    let credential_id = [2; 16];
    let signature = [3; 70];
    let user_id = [4; 32];
    for handle in [Some(&user_id[..]), None] {
        // The encoder only reads these buffers.
        let buffer = |bytes: &[u8]| (len_u32(bytes.len()).unwrap(), bytes.as_ptr().cast_mut());
        let user = handle
            .map(buffer)
            .map(|(len, bytes)| WEBAUTHN_USER_ENTITY_INFORMATION {
                dwVersion: 1,
                cbId: len,
                pbId: bytes,
                ..Default::default()
            });
        let (auth_data_len, auth_data_bytes) = buffer(&auth_data);
        let (signature_len, signature_bytes) = buffer(&signature);
        let (credential_id_len, credential_id_bytes) = buffer(&credential_id);
        // The user follows the embedded assertion, so a wrong assertion size would drop it.
        let response = GetAssertionResponse {
            assertion: AssertionV6 {
                v5: WEBAUTHN_ASSERTION {
                    dwVersion: 6,
                    cbAuthenticatorData: auth_data_len,
                    pbAuthenticatorData: auth_data_bytes,
                    cbSignature: signature_len,
                    pbSignature: signature_bytes,
                    Credential: WEBAUTHN_CREDENTIAL {
                        dwVersion: 1,
                        cbId: credential_id_len,
                        pbId: credential_id_bytes,
                        pwszCredentialType: w!("public-key"),
                    },
                    ..Default::default()
                },
                ..Default::default()
            },
            user: user.as_ref().map_or(ptr::null(), ptr::from_ref),
            // Windows omits a count of one; a larger count would be encoded.
            number_of_credentials: 1,
            ..Default::default()
        };
        let mut length = 0;
        let mut pointer = ptr::null_mut();
        // SAFETY: The structure and every buffer it borrows outlive this synchronous call.
        let result = unsafe { encode_get(&raw const response, &raw mut length, &raw mut pointer) };
        let expected = encoded(result, length, pointer);
        let actual = protocol::response(
            false,
            Response {
                authenticator_data: auth_data.to_vec(),
                credential_id: Some(credential_id.to_vec()),
                signature: Some(signature.to_vec()),
                user_handle: handle.map(<[u8]>::to_vec),
            },
        )
        .unwrap();
        assert_eq!(actual, expected, "user handle {handle:?}");
    }
}
#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn windows_decoder_preserves_raw_rp_hash_and_credential_ids() {
    let provider = provider();
    let value = Cbor::Map(vec![
        (number(1), text("EXAMPLE.com")),
        (number(2), Cbor::Bytes(vec![42; 32])),
        (
            number(3),
            Cbor::Array(vec![Cbor::Map(vec![
                (text("id"), Cbor::Bytes(vec![17; 16])),
                (text("type"), text("public-key")),
            ])]),
        ),
        (number(5), Cbor::Map(vec![(text("uv"), Cbor::Bool(true))])),
    ]);
    // Windows passes the CBOR payload without the transport command byte.
    let request = provider.decode(false, &cbor(&value).unwrap(), 123).unwrap();
    assert_eq!(request.rp_id, "EXAMPLE.com");
    assert_eq!(request.client_data_hash, [42; 32]);
    assert_eq!(request.credentials[0].id, [17; 16]);
    assert_eq!(request.user_verification(), UserVerification::Required);
    assert_eq!(request.window, 123);
}

#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn windows_decoder_reads_registration_user_and_es256() {
    let provider = provider();
    let value = Cbor::Map(vec![
        (number(1), Cbor::Bytes(vec![42; 32])),
        (
            number(2),
            Cbor::Map(vec![
                (text("id"), text("example.com")),
                (text("name"), text("Example")),
            ]),
        ),
        (
            number(3),
            Cbor::Map(vec![
                (text("id"), Cbor::Bytes(vec![3; 16])),
                (text("name"), text("alice")),
                (text("displayName"), text("Alice")),
            ]),
        ),
        (
            number(4),
            Cbor::Array(vec![Cbor::Map(vec![
                (text("alg"), number(ES256)),
                (text("type"), text("public-key")),
            ])]),
        ),
        (
            number(7),
            Cbor::Map(vec![
                (text("rk"), Cbor::Bool(true)),
                (text("uv"), Cbor::Bool(true)),
            ]),
        ),
    ]);
    let request = provider.decode(true, &cbor(&value).unwrap(), 0).unwrap();
    assert_eq!(request.rp_id, "example.com");
    assert_eq!(request.rp_name.as_deref(), Some("Example"));
    assert_eq!(request.algorithms, [ES256]);
    assert_eq!(request.user.unwrap().display_name, "Alice");
    assert!(request.discoverable);
}
#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn unknown_clsid_reports_unregistered_without_registering() {
    let provider = provider();
    assert_eq!(provider.status().unwrap(), Status::Unregistered);
}

fn provider() -> Provider {
    // Resolve through the production symbol table, with a useful missing-symbol failure.
    let api = Api::resolve(|name| {
        if name == c"WebAuthNPluginRegisterStatusChangeCallback"
            || name == c"WebAuthNPluginUnregisterStatusChangeCallback"
        {
            None
        } else {
            Some(export(name))
        }
    })
    .unwrap();
    Provider::new(api, GUID::from_u128(0x3182b386_7a7e_4f75_80af_a60876594f5d))
}

#[test]
#[ignore = "requires Windows passkey plugin exports"]
fn required_exports_are_available() {
    let _ = provider();
    Api::load().expect("Windows passkey provider API is required for this suite");
    export(c"WebAuthNEncodeMakeCredentialResponse");
    export(c"WebAuthNEncodeGetAssertionResponse");
}
