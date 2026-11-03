//! Refresh must use only Update, including when registration disappeared after a status read.
use super::*;
use std::cell::{Cell, RefCell};
use std::slice;

#[derive(Debug)]
struct Details {
    name: String,
    clsid: GUID,
    new_clsid: GUID,
    info: Vec<u8>,
    no_logos: bool,
    all_rps: bool,
}

thread_local! {
    static RESULT: Cell<HRESULT> = const { Cell::new(S_OK) };
    static UPDATES: RefCell<Vec<Details>> = const { RefCell::new(Vec::new()) };
}

unsafe extern "system" fn update(options: *const UpdateOptions) -> HRESULT {
    // SAFETY: The provider passes initialized options, live until this call returns.
    let options = unsafe { &*options };
    // SAFETY: The options borrow a NUL-terminated name for this call.
    let name = unsafe { decode::read_string(options.name) }.unwrap_or_default();
    // SAFETY: Both CLSID pointers borrow the provider's live GUID.
    let clsid = unsafe { *options.clsid };
    // SAFETY: Both CLSID pointers borrow the provider's live GUID.
    let new_clsid = unsafe { *options.new_clsid };
    // SAFETY: The options borrow exactly info_len bytes for this call.
    let info = unsafe { slice::from_raw_parts(options.info, options.info_len as usize) }.to_vec();
    UPDATES.with_borrow_mut(|updates| {
        updates.push(Details {
            name,
            clsid,
            new_clsid,
            info,
            no_logos: options.light_logo.is_null() && options.dark_logo.is_null(),
            all_rps: options.rp_count == 0 && options.rps.is_null(),
        });
    });
    RESULT.get()
}

fn setup(result: HRESULT) -> (Provider, fake::Calls) {
    RESULT.set(result);
    UPDATES.with_borrow_mut(Vec::clear);
    let (mut provider, calls) = provider();
    provider.api.update = update;
    (provider, calls)
}

fn assert_details(provider: &Provider) {
    UPDATES.with_borrow(|updates| {
        let [details] = updates.as_slice() else {
            panic!("expected one update, got {updates:?}");
        };
        assert_eq!(details.name, "Keyguard");
        assert_eq!(details.clsid, provider.clsid);
        assert_eq!(details.new_clsid, provider.clsid);
        assert_eq!(details.info, authenticator_info().unwrap());
        assert!(details.no_logos);
        assert!(details.all_rps);
    });
}

#[test]
fn refresh_updates_current_metadata_under_the_same_clsid_without_add_or_remove() {
    let (provider, _calls) = setup(S_OK);
    provider.refresh_registration().unwrap();
    assert_details(&provider);
}

#[test]
fn a_missing_registration_is_not_recreated() {
    for result in [NTE_NOT_FOUND, HRESULT::from_win32(ERROR_NOT_FOUND.0)] {
        let (provider, _calls) = setup(result);
        provider.refresh_registration().unwrap();
        assert_details(&provider);
    }
}

#[test]
fn a_failed_refresh_can_be_retried() {
    let (provider, _calls) = setup(E_FAIL);
    assert!(provider.refresh_registration().is_err());
    assert_details(&provider);

    RESULT.set(S_OK);
    UPDATES.with_borrow_mut(Vec::clear);
    provider.refresh_registration().unwrap();
    assert_details(&provider);
}

#[test]
fn explicit_registration_of_an_existing_provider_uses_the_same_metadata() {
    unsafe extern "system" fn exists(_: *const AddOptions, _: *mut *mut c_void) -> HRESULT {
        NTE_EXISTS
    }

    let (mut provider, _calls) = setup(S_OK);
    provider.api.add = exists;
    provider.register().unwrap();
    assert_details(&provider);
}
