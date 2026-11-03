//! No function in this table loads or calls the Windows plugin API.
use super::*;
use crate::windows::abi::{CredentialDetails, GetRequest, MakeRequest};
use std::cell::Cell;

thread_local! {
    static UNEXPECTED: Cell<u32> = const { Cell::new(0) };
}

pub(in crate::windows) struct Calls;

impl Drop for Calls {
    fn drop(&mut self) {
        let count = UNEXPECTED.replace(0);
        if !std::thread::panicking() {
            assert_eq!(count, 0, "unexpected fake plugin calls");
        }
    }
}

// Never panic across an extern boundary. Check unexpected calls when the test ends.
macro_rules! failing {
    ($name:ident($($arg:ty),*)) => {
        unsafe extern "system" fn $name($(_: $arg),*) -> HRESULT {
            UNEXPECTED.set(UNEXPECTED.get() + 1);
            E_FAIL
        }
    };
}

failing!(state(*const GUID, *mut u32));
failing!(add(*const AddOptions, *mut *mut c_void));
failing!(update(*const UpdateOptions));
failing!(remove(*const GUID));
failing!(key(*const GUID, *mut u32, *mut *mut u8));
failing!(verify(*const VerifyRequest, *mut u32, *mut *mut u8));
failing!(credentials(*const GUID, u32, *const CredentialDetails<'_>));
failing!(get_credentials(*const GUID, *mut u32, *mut *mut CredentialDetails<'_>));
failing!(decode_make(u32, *const u8, *mut *mut MakeRequest));
failing!(decode_get(u32, *const u8, *mut *mut GetRequest));

unsafe extern "system" fn free(_: *mut c_void) {
    UNEXPECTED.set(UNEXPECTED.get() + 1);
}

unsafe extern "system" fn free_credentials(_: u32, _: *mut CredentialDetails<'_>) {
    UNEXPECTED.set(UNEXPECTED.get() + 1);
}

pub(in crate::windows) fn provider() -> (Provider, Calls) {
    assert_eq!(UNEXPECTED.replace(0), 0);
    let api = Api {
        state,
        add,
        update,
        free_add: free,
        remove,
        signing_key: key,
        uv_key: key,
        free_key: free,
        verify,
        free_verify: free,
        add_credentials: credentials,
        remove_credentials: credentials,
        clear: remove,
        get_credentials,
        free_credentials,
        decode_make,
        decode_get,
        free_make: free,
        free_get: free,
        register_status: None,
        unregister_status: None,
    };
    (
        Provider::new(api, GUID::from_u128(0x3182b386_7a7e_4f75_80af_a60876594f5d)),
        Calls,
    )
}
