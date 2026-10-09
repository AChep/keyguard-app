//! Windows brokers FIDO access for ordinary users and owns the PIN prompt.
use crate::{
    Error, INPUT_LENGTH, MAX_CREDENTIAL, Operation, POLL_INTERVAL, RP_ID, Request, SECRET_LENGTH,
    TIMEOUT, validate_auth_data,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use std::{
    ptr,
    sync::atomic::{AtomicBool, Ordering},
};
use windows_sys::{
    Win32::{Networking::WindowsWebServices::*, UI::WindowsAndMessaging::GetForegroundWindow},
    core::{GUID, w},
};
use zeroize::Zeroize;

pub(crate) fn execute(request: &Request, canceled: &AtomicBool) -> Result<Vec<u8>, Error> {
    let minimum_api = if request.assertion.is_some() { 3 } else { 6 };
    // SAFETY: This version query takes no pointers and does not access a device.
    if unsafe { WebAuthNGetApiVersionNumber() } < minimum_api {
        return Err(Error::Unsupported);
    }
    let mut cancellation = GUID::default();
    // SAFETY: The GUID is writable for this call and lives through the operation.
    check(unsafe { WebAuthNGetCancellationId(&mut cancellation) })?;
    let done = AtomicBool::new(false);
    std::thread::scope(|scope| {
        let cancel_id = cancellation;
        let done = &done;
        scope.spawn(move || {
            while !done.load(Ordering::Acquire) {
                if canceled.load(Ordering::Acquire) {
                    // SAFETY: The copied cancellation ID was issued by WebAuthn for this operation.
                    unsafe {
                        WebAuthNCancelCurrentOperation(&cancel_id);
                    }
                }
                std::thread::sleep(POLL_INTERVAL);
            }
        });
        let result = perform(request, &mut cancellation);
        done.store(true, Ordering::Release);
        if canceled.load(Ordering::Acquire) {
            if let Ok(mut bytes) = result {
                bytes.zeroize();
            }
            Err(Error::Canceled)
        } else {
            result
        }
    })
}

fn perform(request: &Request, cancellation: &mut GUID) -> Result<Vec<u8>, Error> {
    let kind = match request.operation {
        Operation::Register => "webauthn.create",
        Operation::Derive { .. } | Operation::Assert => "webauthn.get",
    };
    let mut json = format!(
        "{{\"type\":\"{kind}\",\"challenge\":\"{}\",\"origin\":\"https://{RP_ID}\"}}",
        URL_SAFE_NO_PAD.encode(request.challenge)
    )
    .into_bytes();
    if let Some(assertion) = &request.assertion {
        json = assertion.client_data.clone();
    }
    let client_data = WEBAUTHN_CLIENT_DATA {
        dwVersion: 1,
        cbClientDataJSON: json.len() as u32,
        pbClientDataJSON: json.as_mut_ptr(),
        pwszHashAlgId: WEBAUTHN_HASH_ALGORITHM_SHA_256,
    };
    let rp_id = request
        .assertion
        .as_ref()
        .map_or(RP_ID, |a| a.rp_id.as_str());
    let rp: Vec<u16> = rp_id.encode_utf16().chain(Some(0)).collect();
    // SAFETY: Windows returns a borrowed foreground window handle; WebAuthn accepts it as its parent.
    let window = unsafe { GetForegroundWindow() };
    let timeout = TIMEOUT.as_millis() as u32;
    match request.operation {
        Operation::Assert => {
            let assertion = request.assertion.as_ref().ok_or(Error::InvalidArgument)?;
            let mut ids: Vec<Vec<u8>> = assertion
                .credentials
                .iter()
                .map(|(id, _)| id.clone())
                .collect();
            let mut items: Vec<WEBAUTHN_CREDENTIAL_EX> = ids
                .iter_mut()
                .zip(&assertion.credentials)
                .map(|(id, (_, transports))| WEBAUTHN_CREDENTIAL_EX {
                    dwVersion: 1,
                    cbId: id.len() as u32,
                    pbId: id.as_mut_ptr(),
                    pwszCredentialType: WEBAUTHN_CREDENTIAL_TYPE_PUBLIC_KEY,
                    dwTransports: *transports,
                })
                .collect();
            let mut pointers: Vec<_> = items.iter_mut().map(|c| c as *mut _).collect();
            let mut credentials = WEBAUTHN_CREDENTIAL_LIST {
                cCredentials: pointers.len() as u32,
                ppCredentials: pointers.as_mut_ptr(),
            };
            let app_id: Option<Vec<u16>> = assertion
                .app_id
                .as_ref()
                .map(|id| id.encode_utf16().chain(Some(0)).collect());
            let mut app_id_used = 0;
            let options = WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS {
                dwVersion: 4,
                dwTimeoutMilliseconds: assertion.timeout.as_millis() as u32,
                dwAuthenticatorAttachment: WEBAUTHN_AUTHENTICATOR_ATTACHMENT_CROSS_PLATFORM,
                dwUserVerificationRequirement: match assertion.verification {
                    0 => WEBAUTHN_USER_VERIFICATION_REQUIREMENT_DISCOURAGED,
                    1 => WEBAUTHN_USER_VERIFICATION_REQUIREMENT_PREFERRED,
                    _ => WEBAUTHN_USER_VERIFICATION_REQUIREMENT_REQUIRED,
                },
                pCancellationId: cancellation,
                pAllowCredentialList: &mut credentials,
                pwszU2fAppId: app_id.as_ref().map_or(ptr::null(), |id| id.as_ptr()),
                pbU2fAppId: &mut app_id_used,
                ..Default::default()
            };
            let mut output = ptr::null_mut();
            // SAFETY: All pointers refer to storage alive through the synchronous call.
            check(unsafe {
                WebAuthNAuthenticatorGetAssertion(
                    window,
                    rp.as_ptr(),
                    &client_data,
                    &options,
                    &mut output,
                )
            })?;
            let output = Assertion(output);
            // SAFETY: The guard owns the successful WebAuthn allocation and its buffers.
            let data = unsafe { output.0.as_ref() }.ok_or(Error::Protocol)?;
            // SAFETY: The guard keeps each length-checked buffer alive until encoding finishes.
            unsafe {
                assertion.encode_result(
                    borrow_buffer(
                        data.Credential.pbId,
                        data.Credential.cbId,
                        1,
                        MAX_CREDENTIAL,
                    )?,
                    borrow_buffer(
                        data.pbAuthenticatorData,
                        data.cbAuthenticatorData,
                        37,
                        65536,
                    )?,
                    borrow_buffer(data.pbSignature, data.cbSignature, 1, 4096)?,
                    if data.cbUserId == 0 {
                        &[]
                    } else {
                        borrow_buffer(data.pbUserId, data.cbUserId, 1, 64)?
                    },
                    app_id_used != 0,
                )
            }
        }
        Operation::Register => {
            let relying_party = WEBAUTHN_RP_ENTITY_INFORMATION {
                dwVersion: 1,
                pwszId: rp.as_ptr(),
                pwszName: w!("Keyguard"),
                ..Default::default()
            };
            let mut user_id = request.input;
            let user = WEBAUTHN_USER_ENTITY_INFORMATION {
                dwVersion: 1,
                cbId: INPUT_LENGTH as u32,
                pbId: user_id.as_mut_ptr(),
                pwszName: w!("Keyguard vault"),
                pwszDisplayName: w!("Keyguard vault"),
                ..Default::default()
            };
            let mut algorithm = WEBAUTHN_COSE_CREDENTIAL_PARAMETER {
                dwVersion: 1,
                pwszCredentialType: WEBAUTHN_CREDENTIAL_TYPE_PUBLIC_KEY,
                lAlg: -7,
            };
            let algorithms = WEBAUTHN_COSE_CREDENTIAL_PARAMETERS {
                cCredentialParameters: 1,
                pCredentialParameters: &mut algorithm,
            };
            let options = WEBAUTHN_AUTHENTICATOR_MAKE_CREDENTIAL_OPTIONS {
                dwVersion: 6,
                dwTimeoutMilliseconds: timeout,
                dwAuthenticatorAttachment: WEBAUTHN_AUTHENTICATOR_ATTACHMENT_CROSS_PLATFORM,
                // Windows requires a discoverable credential when enabling PRF.
                bRequireResidentKey: 1,
                bEnablePrf: 1,
                dwUserVerificationRequirement: WEBAUTHN_USER_VERIFICATION_REQUIREMENT_REQUIRED,
                dwAttestationConveyancePreference: WEBAUTHN_ATTESTATION_CONVEYANCE_PREFERENCE_NONE,
                pCancellationId: cancellation,
                ..Default::default()
            };
            let mut output = ptr::null_mut();
            // SAFETY: Every input pointer refers to storage alive until the synchronous call returns.
            check(unsafe {
                WebAuthNAuthenticatorMakeCredential(
                    window,
                    &relying_party,
                    &user,
                    &algorithms,
                    &client_data,
                    &options,
                    &mut output,
                )
            })?;
            let output = Attestation(output);
            // SAFETY: A successful WebAuthn call owns this allocation until our guard frees it.
            let data = unsafe { output.0.as_ref() }.ok_or(Error::Protocol)?;
            if data.dwVersion < 5 || data.bPrfEnabled == 0 {
                return Err(Error::Unsupported);
            }
            // SAFETY: Both buffers are borrowed from the live WebAuthn allocation.
            unsafe {
                validate_raw_auth_data(data.pbAuthenticatorData, data.cbAuthenticatorData)?;
                borrow_buffer(data.pbCredentialId, data.cbCredentialId, 1, MAX_CREDENTIAL)
                    .map(<[u8]>::to_vec)
            }
        }
        Operation::Derive {
            credential: allowed,
        } => {
            let mut credential_id = allowed.to_vec();
            let mut credential = WEBAUTHN_CREDENTIAL_EX {
                dwVersion: 1,
                cbId: credential_id.len() as u32,
                pbId: credential_id.as_mut_ptr(),
                pwszCredentialType: WEBAUTHN_CREDENTIAL_TYPE_PUBLIC_KEY,
                dwTransports: WEBAUTHN_CTAP_TRANSPORT_USB,
            };
            let mut credential_ptr = &mut credential as *mut _;
            let mut credentials = WEBAUTHN_CREDENTIAL_LIST {
                cCredentials: 1,
                ppCredentials: &mut credential_ptr,
            };
            let mut input = request.input;
            let mut salt = WEBAUTHN_HMAC_SECRET_SALT {
                cbFirst: INPUT_LENGTH as u32,
                pbFirst: input.as_mut_ptr(),
                ..Default::default()
            };
            let mut salts = WEBAUTHN_HMAC_SECRET_SALT_VALUES {
                pGlobalHmacSalt: &mut salt,
                ..Default::default()
            };
            let options = WEBAUTHN_AUTHENTICATOR_GET_ASSERTION_OPTIONS {
                dwVersion: 6,
                dwTimeoutMilliseconds: timeout,
                dwAuthenticatorAttachment: WEBAUTHN_AUTHENTICATOR_ATTACHMENT_CROSS_PLATFORM,
                dwUserVerificationRequirement: WEBAUTHN_USER_VERIFICATION_REQUIREMENT_REQUIRED,
                pCancellationId: cancellation,
                pAllowCredentialList: &mut credentials,
                // No HMAC_SECRET_VALUES flag: Windows applies WebAuthn PRF domain separation.
                pHmacSecretSaltValues: &mut salts,
                ..Default::default()
            };
            let mut output = ptr::null_mut();
            // SAFETY: The borrowed inputs and output pointer outlive this synchronous call.
            check(unsafe {
                WebAuthNAuthenticatorGetAssertion(
                    window,
                    rp.as_ptr(),
                    &client_data,
                    &options,
                    &mut output,
                )
            })?;
            let output = Assertion(output);
            // SAFETY: A successful call owns this allocation until our guard frees it.
            let data = unsafe { output.0.as_ref() }.ok_or(Error::Protocol)?;
            // SAFETY: The buffers belong to the live WebAuthn allocation and are checked before access.
            unsafe {
                validate_raw_auth_data(data.pbAuthenticatorData, data.cbAuthenticatorData)?;
                if borrow_buffer(
                    data.Credential.pbId,
                    data.Credential.cbId,
                    1,
                    MAX_CREDENTIAL,
                )? != allowed
                {
                    return Err(Error::Protocol);
                }
                if data.dwVersion < 3 {
                    return Err(Error::Unsupported);
                }
                let secret = data.pHmacSecret.as_ref().ok_or(Error::Unsupported)?;
                borrow_buffer(secret.pbFirst, secret.cbFirst, SECRET_LENGTH, SECRET_LENGTH)
                    .map(<[u8]>::to_vec)
            }
        }
    }
}

fn check(status: i32) -> Result<(), Error> {
    match status as u32 {
        0 => Ok(()),
        0x800704c7 | 0x80090036 => Err(Error::Canceled),
        0x80090029 | 0x80070032 => Err(Error::Unsupported),
        _ => Err(Error::Rejected),
    }
}

unsafe fn borrow_buffer<'a>(
    pointer: *const u8,
    length: u32,
    min: usize,
    max: usize,
) -> Result<&'a [u8], Error> {
    if pointer.is_null() || !(min..=max).contains(&(length as usize)) {
        return Err(Error::Protocol);
    }
    // SAFETY: The caller guarantees the OS allocation is alive and has length readable bytes.
    Ok(unsafe { std::slice::from_raw_parts(pointer, length as usize) })
}

unsafe fn validate_raw_auth_data(pointer: *const u8, length: u32) -> Result<(), Error> {
    // The data starts with the RP ID hash (32), flags (1) and signature counter (4).
    // SAFETY: The caller guarantees this buffer belongs to the live WebAuthn allocation.
    let data = unsafe { borrow_buffer(pointer, length, 37, 65536) }?;
    validate_auth_data(&data[..32], data[32])
}

struct Attestation(*mut WEBAUTHN_CREDENTIAL_ATTESTATION);
impl Drop for Attestation {
    fn drop(&mut self) {
        // SAFETY: This guard uniquely owns the allocation returned by WebAuthn.
        unsafe {
            WebAuthNFreeCredentialAttestation(self.0);
        }
    }
}
struct Assertion(*mut WEBAUTHN_ASSERTION);
impl Drop for Assertion {
    fn drop(&mut self) {
        // SAFETY: This guard uniquely owns the OS buffers; clear PRF output before freeing them.
        unsafe {
            if let Some(data) = self.0.as_ref()
                && data.dwVersion >= 3
                && let Some(secret) = data.pHmacSecret.as_ref()
                && secret.cbFirst as usize == SECRET_LENGTH
                && !secret.pbFirst.is_null()
            {
                std::slice::from_raw_parts_mut(secret.pbFirst, SECRET_LENGTH).zeroize();
            }
            WebAuthNFreeAssertion(self.0);
        }
    }
}
