//! Provider logic tests use only typed fake plugin functions.
use super::*;
use crate::{
    protocol::{ES256, Request, UserVerification},
    requests::Context,
};
use windows::Win32::Foundation::{E_FAIL, S_OK};

mod fake;
pub(super) use fake::provider;

#[test]
#[should_panic(
    expected = "missing required webauthn.dll export: \"WebAuthNPluginGetAuthenticatorState\""
)]
fn missing_integration_export_is_a_failure() {
    integration_tests::require_export(c"WebAuthNPluginGetAuthenticatorState", None);
}

#[test]
fn missing_required_exports_are_unsupported_but_status_callbacks_are_optional() {
    // This address is only resolved, never invoked through the resulting table.
    unsafe extern "system" fn address() -> isize {
        0
    }

    let mut names = Vec::new();
    assert!(
        Api::resolve(|name| {
            names.push(name.to_owned());
            Some(address)
        })
        .is_ok()
    );
    assert_eq!(names.len(), 21);

    for missing in &names {
        let result = Api::resolve(|name| (name != missing.as_c_str()).then_some(address));
        let optional = missing.as_c_str() == c"WebAuthNPluginRegisterStatusChangeCallback"
            || missing.as_c_str() == c"WebAuthNPluginUnregisterStatusChangeCallback";
        if optional {
            assert!(result.is_ok(), "{missing:?}");
        } else {
            assert!(matches!(result, Err(Error::Unsupported)), "{missing:?}");
        }
    }
}

#[test]
fn status_notifications_fall_back_without_optional_exports() {
    let (provider, _calls) = provider();
    provider.watch_status();
    assert!(!provider.watching_status());
    provider.unwatch_status();
}

#[test]
fn longest_accepted_text_reads_back() {
    let value = "a".repeat(MAX_TEXT);
    let encoded = wide(&value).unwrap();
    // SAFETY: `wide` returns a NUL-terminated buffer that outlives the read.
    let decoded = unsafe { decode::read_string(PCWSTR(encoded.as_ptr())) }.unwrap();
    assert_eq!(decoded, value);
    assert_eq!(wide(&"a".repeat(MAX_TEXT + 1)), Err(Error::Invalid));
}

#[test]
fn window_handles_round_trip_sign_extended_values() {
    for value in [0, 123, -2, i64::from(i32::MIN)] {
        assert_eq!(window_value(window_handle(value).unwrap()), value);
    }
}

#[test]
fn a_stopped_provider_restarts_with_open_requests_and_the_vault_lock_state() {
    let (provider, _calls) = provider();
    provider.locked.store(false, Ordering::Release);
    provider.requests.close();
    assert!(!provider.is_serving());
    let restarted = provider.restarted();
    assert!(!restarted.requests.is_closed());
    assert!(!restarted.locked.load(Ordering::Acquire));
}

#[test]
fn the_apartment_publishes_and_revokes_a_factory_again_and_again() {
    let (provider, _calls) = provider();
    let provider = Arc::new(provider);
    for _ in 0..2 {
        let cookie = com::register(&provider).unwrap();
        com::revoke(cookie).unwrap();
    }
}

#[test]
fn clearing_a_never_filled_index_succeeds() {
    unsafe extern "system" fn never_filled(_: *const GUID) -> HRESULT {
        HRESULT::from_win32(ERROR_FILE_NOT_FOUND.0)
    }
    unsafe extern "system" fn failing(_: *const GUID) -> HRESULT {
        E_FAIL
    }

    let (mut provider, _calls) = provider();
    // An empty vault never fills the index, which must not block unregistering or erasing.
    provider.api.clear = never_filled;
    assert!(provider.clear_index().is_ok());
    provider.api.clear = failing;
    assert_eq!(provider.clear_index().err(), Some(Error::Os(E_FAIL.0)));
}

#[test]
fn disabling_provider_rejects_a_pending_response() {
    unsafe extern "system" fn disabled(_: *const GUID, state: *mut u32) -> HRESULT {
        // SAFETY: Provider::status supplies a live scalar output for this test callback.
        unsafe { state.write(0) };
        S_OK
    }

    let (mut provider, _calls) = provider();
    provider.api.state = disabled;
    let id = provider.requests.begin(registration()).unwrap();
    assert_eq!(
        provider.complete(id, Some(authenticator_data_only()), Failure::Unspecified),
        Err(Error::Cancelled)
    );
    assert_eq!(provider.requests.wait(id), Err(Error::Cancelled));
}

#[test]
fn an_unencodable_response_fails_windows_and_the_host() {
    unsafe extern "system" fn enabled(_: *const GUID, state: *mut u32) -> HRESULT {
        // SAFETY: Provider::status supplies a live scalar output for this test callback.
        unsafe { state.write(1) };
        S_OK
    }

    let (mut provider, _calls) = provider();
    provider.api.state = enabled;
    let mut context = registration();
    context.request.create = false;
    let id = provider.requests.begin(context).unwrap();
    // An assertion without its credential ID and signature cannot be encoded.
    assert_eq!(
        provider.complete(id, Some(authenticator_data_only()), Failure::Unspecified),
        Err(Error::Invalid)
    );
    assert_eq!(provider.requests.wait(id), Err(Error::Invalid));
}

thread_local! {
    /// The requests that a stubbed Hello prompt cancels while it is open.
    static PROMPT_CANCELS: std::cell::Cell<*const Requests> = const { std::cell::Cell::new(ptr::null()) };
}

#[test]
fn a_closed_hello_prompt_dismisses_only_a_live_request() {
    unsafe extern "system" fn closed(
        _: *const VerifyRequest,
        _: *mut u32,
        _: *mut *mut u8,
    ) -> HRESULT {
        let requests = PROMPT_CANCELS.get();
        // SAFETY: The test sets this only while its provider is borrowed for `verify`.
        if let Some(requests) = unsafe { requests.as_ref() } {
            requests.cancel();
        }
        NTE_USER_CANCELLED
    }

    let (mut provider, _calls) = provider();
    provider.api.verify = closed;
    let id = provider.requests.begin(registration()).unwrap();
    assert_eq!(
        provider.verify(id, 0, "alice", "example.com", false),
        Err(Error::Dismissed)
    );
    // The vault locked while the prompt was open: the request already has its answer.
    PROMPT_CANCELS.set(&raw const provider.requests);
    let result = provider.verify(id, 0, "alice", "example.com", false);
    PROMPT_CANCELS.set(ptr::null());
    assert_eq!(result, Err(Error::Cancelled));
}

#[test]
fn a_response_reaches_windows_only_for_a_verified_user() {
    unsafe extern "system" fn enabled(_: *const GUID, state: *mut u32) -> HRESULT {
        // SAFETY: Provider::status supplies a live scalar output for this test callback.
        unsafe { state.write(1) };
        S_OK
    }

    let (mut provider, _calls) = provider();
    provider.api.state = enabled;
    // Keyguard answered without verifying the user: the request ends instead.
    let id = provider.requests.begin(registration()).unwrap();
    assert_eq!(
        provider.complete(id, Some(authenticator_data_only()), Failure::Unspecified),
        Err(Error::Unverified)
    );
    assert_eq!(provider.requests.wait(id), Err(Error::Cancelled));

    // Unlocking the vault during the request verified the user, without Windows Hello.
    let id = provider.requests.begin(registration()).unwrap();
    provider
        .verify(id, 0, "alice", "example.com", true)
        .unwrap();
    provider
        .complete(id, Some(authenticator_data_only()), Failure::Unspecified)
        .unwrap();
    assert!(provider.requests.wait(id).is_ok());
}

#[test]
fn a_reported_failure_reaches_windows() {
    let (provider, _calls) = provider();
    for (failure, expected) in [
        (Failure::Unspecified, Error::Cancelled),
        (Failure::Dismissed, Error::Dismissed),
        (Failure::Excluded, Error::Excluded),
        (Failure::NotFound, Error::NotFound),
    ] {
        let id = provider.requests.begin(registration()).unwrap();
        provider.complete(id, None, failure).unwrap();
        assert_eq!(provider.requests.wait(id), Err(expected));
    }
}

/// A response with only authenticator data: a registration answer, or an incomplete assertion.
fn authenticator_data_only() -> Response {
    Response {
        authenticator_data: vec![0; 37],
        credential_id: None,
        signature: None,
        user_handle: None,
    }
}

fn registration() -> Context {
    Context {
        transaction: 1,
        hash: [0; 32],
        request: Request {
            create: true,
            rp_id: "example.com".into(),
            client_data_hash: vec![0; 32],
            user_verification: UserVerification::Required.into(),
            algorithms: vec![ES256],
            discoverable: true,
            ..Request::default()
        },
    }
}
