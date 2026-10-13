mod abi;
mod abi_layout;
mod com;
mod crypto;
mod decode;
#[cfg(test)]
mod integration_tests;
mod metadata;
#[cfg(test)]
mod tests;

use crate::{
    error::Error,
    protocol::{
        self, CompleteCommand, Empty, Failure, MAX_TEXT, NextCommand, Response, SetLockedCommand,
        Status, SyncCommand, VerifyCommand, authenticator_info, command::Command, reply::Outcome,
    },
    requests::Requests,
};
use abi::{
    Add, AddOptions, Credentials, DecodeGet, DecodeMake, Free, FreeCredentials, GetCredentials,
    GetKey, GetState, RegisterStatus, Remove, UnregisterStatus, Update, UpdateOptions, Verify,
    VerifyRequest,
};
use decode::{read_ref, read_slice};
use keyguard_ffi::{PanicHook, contained};
use std::{
    ffi::{CStr, OsString, c_void},
    os::windows::ffi::OsStringExt,
    path::PathBuf,
    ptr,
    sync::{
        Arc, Mutex, MutexGuard, OnceLock, PoisonError, TryLockError,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use windows::{
    Win32::{
        Foundation::{
            ERROR_CANCELLED, ERROR_FILE_NOT_FOUND, ERROR_INSUFFICIENT_BUFFER, ERROR_NOT_FOUND,
            HWND, NTE_EXISTS, NTE_NOT_FOUND, NTE_USER_CANCELLED,
        },
        Storage::Packaging::Appx::GetCurrentPackagePath,
        System::LibraryLoader::{GetProcAddress, LOAD_LIBRARY_SEARCH_SYSTEM32, LoadLibraryExW},
    },
    core::{GUID, HRESULT, PCSTR, PCWSTR, PWSTR, w},
};

/// Unit tests keep Rust's default hook so a caught assertion still reports its payload.
const PANIC_HOOK: PanicHook = if cfg!(test) {
    PanicHook::Keep
} else {
    PanicHook::Redact
};
const MAX_SIGNATURE: usize = 16384;
/// UTF-16 units of the package path, terminator included.
const MAX_PACKAGE_PATH: u32 = 32768;
/// What cannot change while the process runs: Windows' provider API and the package's
/// CLSID. Only these failures are kept; any other failure to start can be retried.
static ENVIRONMENT: OnceLock<Result<(Api, GUID), Error>> = OnceLock::new();
/// The current provider. Its server serves from a start until a stop or retirement; the next
/// start replaces a provider that no longer accepts requests.
static PROVIDER: Mutex<Option<Arc<Provider>>> = Mutex::new(None);
/// Serializes starts with each other and with revocation, so a factory is never left published.
static STARTING: Mutex<()> = Mutex::new(());

struct Provider {
    api: Api,
    clsid: GUID,
    requests: Requests,
    locked: AtomicBool,
    /// The cookie of the published class factory while the server serves.
    registration: Mutex<Option<u32>>,
    metadata_lock: Mutex<()>,
    verification_lock: Mutex<()>,
    status_cookie: Mutex<Option<u32>>,
}

#[derive(Clone, Copy)]
struct Api {
    state: GetState,
    add: Add,
    update: Update,
    free_add: Free,
    remove: Remove,
    signing_key: GetKey,
    uv_key: GetKey,
    free_key: Free,
    verify: Verify,
    free_verify: Free,
    add_credentials: Credentials,
    remove_credentials: Credentials,
    clear: Remove,
    get_credentials: GetCredentials,
    free_credentials: FreeCredentials,
    decode_make: DecodeMake,
    decode_get: DecodeGet,
    free_make: Free,
    free_get: Free,
    // Without these, the host reads the status when its event wait times out.
    register_status: Option<RegisterStatus>,
    unregister_status: Option<UnregisterStatus>,
}

impl Api {
    fn load() -> Result<Self, Error> {
        // SAFETY: A fixed system DLL name, restricted to System32. Keep the module loaded
        // for the process lifetime because COM and JNI calls may still hold its functions.
        let module =
            unsafe { LoadLibraryExW(w!("webauthn.dll"), None, LOAD_LIBRARY_SEARCH_SYSTEM32) }
                .map_err(|_| Error::Unsupported)?;
        Self::resolve(|name| {
            // SAFETY: The module remains loaded; names are NUL-terminated literals.
            unsafe { GetProcAddress(module, PCSTR(name.as_ptr().cast())) }
        })
    }

    /// Keep symbol selection separate so tests can exercise missing exports without a DLL.
    fn resolve(
        mut export: impl FnMut(&CStr) -> Option<unsafe extern "system" fn() -> isize>,
    ) -> Result<Self, Error> {
        macro_rules! optional {
            ($name:literal, $ty:ty) => {{
                export($name).map(|address| {
                    // SAFETY: The resolver supplies the named system export. The signatures
                    // are checked against the pinned Microsoft headers in abi_layout.rs.
                    unsafe {
                        std::mem::transmute::<unsafe extern "system" fn() -> isize, $ty>(address)
                    }
                })
            }};
        }
        macro_rules! symbol {
            ($name:literal, $ty:ty) => {
                optional!($name, $ty).ok_or(Error::Unsupported)?
            };
        }
        Ok(Self {
            state: symbol!(c"WebAuthNPluginGetAuthenticatorState", GetState),
            add: symbol!(c"WebAuthNPluginAddAuthenticator", Add),
            update: symbol!(c"WebAuthNPluginUpdateAuthenticatorDetails", Update),
            free_add: symbol!(c"WebAuthNPluginFreeAddAuthenticatorResponse", Free),
            remove: symbol!(c"WebAuthNPluginRemoveAuthenticator", Remove),
            signing_key: symbol!(c"WebAuthNPluginGetOperationSigningPublicKey", GetKey),
            uv_key: symbol!(c"WebAuthNPluginGetUserVerificationPublicKey", GetKey),
            free_key: symbol!(c"WebAuthNPluginFreePublicKeyResponse", Free),
            verify: symbol!(c"WebAuthNPluginPerformUserVerification", Verify),
            free_verify: symbol!(c"WebAuthNPluginFreeUserVerificationResponse", Free),
            add_credentials: symbol!(c"WebAuthNPluginAuthenticatorAddCredentials", Credentials),
            remove_credentials: symbol!(
                c"WebAuthNPluginAuthenticatorRemoveCredentials",
                Credentials
            ),
            clear: symbol!(c"WebAuthNPluginAuthenticatorRemoveAllCredentials", Remove),
            get_credentials: symbol!(
                c"WebAuthNPluginAuthenticatorGetAllCredentials",
                GetCredentials
            ),
            free_credentials: symbol!(
                c"WebAuthNPluginAuthenticatorFreeCredentialDetailsArray",
                FreeCredentials
            ),
            decode_make: symbol!(c"WebAuthNDecodeMakeCredentialRequest", DecodeMake),
            decode_get: symbol!(c"WebAuthNDecodeGetAssertionRequest", DecodeGet),
            free_make: symbol!(c"WebAuthNFreeDecodedMakeCredentialRequest", Free),
            free_get: symbol!(c"WebAuthNFreeDecodedGetAssertionRequest", Free),
            register_status: optional!(
                c"WebAuthNPluginRegisterStatusChangeCallback",
                RegisterStatus
            ),
            unregister_status: optional!(
                c"WebAuthNPluginUnregisterStatusChangeCallback",
                UnregisterStatus
            ),
        })
    }
}

/// Windows calls this from its own thread. It wakes the current provider, whichever server
/// runs.
extern "system" fn status_changed(_context: *mut c_void) {
    let _ = contained(PANIC_HOOK, || {
        if let Some(provider) = current() {
            provider.requests.status_changed();
        }
    });
}

fn package_clsid() -> Result<GUID, Error> {
    #[derive(serde::Deserialize)]
    struct Config {
        clsid: String,
    }

    let mut length = 0;
    // SAFETY: Size query with no output buffer.
    if unsafe { GetCurrentPackagePath(&raw mut length, None) } != ERROR_INSUFFICIENT_BUFFER
        || length > MAX_PACKAGE_PATH
    {
        return Err(Error::Unpackaged);
    }
    let mut path = vec![0; length as usize];
    // SAFETY: The size query supplied this buffer's capacity.
    unsafe { GetCurrentPackagePath(&raw mut length, Some(PWSTR(path.as_mut_ptr()))) }
        .ok()
        .map_err(|_| Error::Unpackaged)?;
    if path.last() == Some(&0) {
        path.pop();
    }
    let root = PathBuf::from(OsString::from_wide(&path));
    // The config is shipped next to AppxManifest.xml in the immutable installed package.
    let bytes =
        std::fs::read(root.join("keyguard-passkeys.json")).map_err(|_| Error::Unpackaged)?;
    let config: Config = serde_json::from_slice(&bytes).map_err(|_| Error::Unpackaged)?;
    GUID::try_from(config.clsid.as_str()).map_err(|_| Error::Unpackaged)
}

fn environment() -> Result<(Api, GUID), Error> {
    *ENVIRONMENT.get_or_init(|| {
        // Most installs are unpackaged: check that first, without loading webauthn.dll.
        let clsid = package_clsid()?;
        Ok((Api::load()?, clsid))
    })
}

/// The current provider, if any. Never creates one.
fn current() -> Option<Arc<Provider>> {
    PROVIDER
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .clone()
}

/// The current provider, created without serving. The provider API relies on the process's
/// COM apartment, so this initializes it first: the first command sets the COM security.
fn provider() -> Result<Arc<Provider>, Error> {
    let (api, clsid) = environment()?;
    com::initialize()?;
    let mut current = PROVIDER.lock().unwrap_or_else(PoisonError::into_inner);
    Ok(current
        .get_or_insert_with(|| Arc::new(Provider::new(api, clsid)))
        .clone())
}

/// Serves Windows' requests until a stop or retirement; serving already is no change. A
/// failure leaves the provider to start again. A stopped one accepts no request, so serving
/// again replaces it.
fn start() -> Result<(), Error> {
    let _starting = STARTING.lock().unwrap_or_else(PoisonError::into_inner);
    let mut provider = provider()?;
    if provider.is_serving() {
        return Ok(());
    }
    if provider.requests.is_closed() {
        provider = Arc::new(provider.restarted());
        *PROVIDER.lock().unwrap_or_else(PoisonError::into_inner) = Some(provider.clone());
    }
    let cookie = com::register(&provider)?;
    *provider.registration.lock()? = Some(cookie);
    Ok(())
}

/// Wakes the event waits to check for cancellation. Never creates a provider.
pub fn wake() {
    if let Some(provider) = current() {
        provider.requests.wake();
    }
}

/// Runs the command; a `canceled` operation ends its event wait.
pub fn dispatch(command: Command, canceled: &AtomicBool) -> Result<Outcome, Error> {
    let provider = match provider() {
        Ok(provider) => provider,
        Err(error) if matches!(command, Command::Status(_)) => {
            let status = match error {
                Error::Unsupported => Status::Unsupported,
                Error::Unpackaged => Status::Unpackaged,
                _ => Status::Unavailable,
            };
            return Ok(Outcome::Status(status.into()));
        }
        // Without a running server, nothing can be waiting on this process.
        Err(_) if matches!(command, Command::Retire(_)) => return Ok(Outcome::Retired(true)),
        Err(error) => return Err(error),
    };
    match command {
        Command::Status(_) => {
            let status = provider.status()?;
            // Retried on each read until a registered provider is subscribed.
            if status != Status::Unregistered {
                provider.watch_status();
            }
            return Ok(Outcome::Status(status.into()));
        }
        Command::Start(_) => start()?,
        Command::Register(_) => provider.register()?,
        Command::Unregister(_) => {
            let _lock = provider.clear_index()?;
            // SAFETY: Same registered CLSID; removing it also invalidates its keys.
            unsafe { (provider.api.remove)(&raw const provider.clsid) }.ok()?;
            provider.unwatch_status();
        }
        Command::Clear(_) => {
            let _lock = provider.clear_index()?;
        }
        Command::Next(NextCommand { active, timeout_ms }) => {
            // No request would ever arrive; the host starts serving again instead.
            if !provider.is_serving() && !provider.requests.is_closed() {
                return Err(Error::NotServing);
            }
            let timeout = timeout_ms.map(Duration::from_millis);
            let mut event = provider.requests.next(active, timeout, canceled)?;
            event.watching = provider.watching_status();
            return Ok(Outcome::Event(event));
        }
        // Answered before dispatch; kept for an exhaustive match.
        Command::Limits(_) => return Ok(Outcome::Limits(protocol::limits())),
        Command::SetLocked(SetLockedCommand { locked }) => {
            let previous = provider.locked.swap(locked, Ordering::AcqRel);
            if locked && !previous {
                provider.requests.cancel();
            }
        }
        Command::Complete(CompleteCommand {
            id,
            response,
            failure,
        }) => {
            let failure = Failure::try_from(failure).map_err(|_| Error::Invalid)?;
            provider.complete(id, response, failure)?;
        }
        Command::Verify(VerifyCommand {
            id,
            window,
            username,
            hint,
            unlocked,
        }) => provider.verify(id, window, &username, &hint, unlocked)?,
        Command::Sync(SyncCommand { identities }) => provider.sync(identities)?,
        Command::Stop(_) => {
            provider.requests.close();
            provider.shut_down()?;
        }
        Command::Retire(_) => {
            let retired = provider.requests.close_if_idle()?;
            if retired {
                provider.shut_down()?;
            }
            return Ok(Outcome::Retired(retired));
        }
    }
    Ok(Outcome::Done(Empty {}))
}

fn wide(value: &str) -> Result<Vec<u16>, Error> {
    if value.contains('\0') || value.len() > MAX_TEXT {
        return Err(Error::Invalid);
    }
    Ok(value.encode_utf16().chain(Some(0)).collect())
}

/// A buffer length as the `u32` the OS ABI takes.
fn len_u32(len: usize) -> Result<u32, Error> {
    u32::try_from(len).map_err(|_| Error::Invalid)
}

/// A window handle from its wire value. Handles are sign-extended 32-bit values, so a
/// negative value is a valid handle.
fn window_handle(value: i64) -> Result<HWND, Error> {
    let value = isize::try_from(value).map_err(|_| Error::Invalid)?;
    // A handle does not address memory, so it carries no provenance.
    Ok(HWND(ptr::without_provenance_mut(value.cast_unsigned())))
}

/// The wire value of a window handle; the inverse of [`window_handle`].
fn window_value(handle: HWND) -> i64 {
    // Lossless: isize is at most 64 bits wide on every Windows target.
    handle.0.addr().cast_signed() as i64
}

/// An OS allocation, freed once on drop. Borrows from it cannot outlive it.
struct Allocation {
    pointer: *mut c_void,
    free: Free,
}
impl Allocation {
    /// Borrows the allocation as a `T`. A null or misaligned allocation is invalid.
    ///
    /// # Safety
    /// A non-null allocation must start with an initialized `T`.
    unsafe fn get<T>(&self) -> Result<&T, Error> {
        // SAFETY: The caller guarantees the contents; the borrow is tied to `self`.
        unsafe { read_ref(self, self.pointer.cast_const().cast()) }
    }

    /// Borrows the first `count` bytes of the allocation, rejecting more than `max`.
    ///
    /// # Safety
    /// For a nonzero `count`, the allocation must hold `count` initialized bytes.
    unsafe fn bytes(&self, count: u32, max: usize) -> Result<&[u8], Error> {
        // SAFETY: The caller guarantees the extent; the borrow is tied to `self`.
        unsafe { read_slice(self, self.pointer.cast_const().cast(), count, max) }
    }
}
impl Drop for Allocation {
    fn drop(&mut self) {
        if !self.pointer.is_null() {
            // SAFETY: This allocation came from the matching OS API and is freed once.
            unsafe { (self.free)(self.pointer) };
        }
    }
}

impl Provider {
    fn new(api: Api, clsid: GUID) -> Self {
        Self {
            api,
            clsid,
            requests: Requests::default(),
            locked: AtomicBool::new(true),
            registration: Mutex::new(None),
            metadata_lock: Mutex::new(()),
            verification_lock: Mutex::new(()),
            status_cookie: Mutex::new(None),
        }
    }

    /// A provider for a new server. It keeps reporting the vault's lock state to Windows.
    fn restarted(&self) -> Self {
        let provider = Self::new(self.api, self.clsid);
        let locked = self.locked.load(Ordering::Acquire);
        provider.locked.store(locked, Ordering::Release);
        provider
    }

    /// Whether the class factory is published. A broken lock reads as not serving.
    fn is_serving(&self) -> bool {
        self.registration
            .lock()
            .is_ok_and(|registration| registration.is_some())
    }

    /// Serializes OS registration and index changes. The lock guards no data, so a panic
    /// while it was held left nothing inconsistent.
    fn metadata(&self) -> MutexGuard<'_, ()> {
        self.metadata_lock
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
    }

    /// Cancels the pending request and empties the OS index. The guard blocks metadata writes.
    fn clear_index(&self) -> Result<MutexGuard<'_, ()>, Error> {
        self.requests.cancel();
        let lock = self.metadata();
        // SAFETY: The CLSID lives throughout this synchronous OS call.
        let result = unsafe { (self.api.clear)(&raw const self.clsid) };
        // An index that was never filled is already empty; see `Provider::existing`.
        if result != HRESULT::from_win32(ERROR_FILE_NOT_FOUND.0) {
            result.ok()?;
        }
        Ok(lock)
    }

    fn complete(&self, id: u64, response: Option<Response>, failure: Failure) -> Result<(), Error> {
        let context = self.requests.context(id)?;
        // Windows settings can disable the provider while the picker/Hello is open.
        // Cancellation remains possible regardless of its registration state.
        if response.is_some() && self.status()? != Status::Enabled {
            self.requests.complete(id, Err(Error::Cancelled))?;
            return Err(Error::Cancelled);
        }
        let Some(response) = response else {
            return self.requests.complete(id, Err(failure.into()));
        };
        let result = protocol::response(context.request.create, response);
        // A response Windows cannot receive fails the request, and Keyguard must learn it.
        let encoding = result.as_ref().map(|_| ()).map_err(|error| *error);
        self.requests.complete(id, result)?;
        encoding
    }

    /// Subscribes once to enablement changes, which wake `Requests::next`. A failure is
    /// harmless: the host also reads the status whenever its event wait times out.
    fn watch_status(&self) {
        let Some(register) = self.api.register_status else {
            return;
        };
        let Ok(mut cookie) = self.status_cookie.lock() else {
            return;
        };
        if cookie.is_some() {
            return;
        }
        let mut value = 0;
        // SAFETY: A static callback that ignores its null context; the CLSID and output slot
        // are live for this synchronous call.
        let result = unsafe {
            register(
                status_changed,
                ptr::null_mut(),
                &raw const self.clsid,
                &raw mut value,
            )
        };
        if result.is_ok() {
            *cookie = Some(value);
        }
    }

    fn watching_status(&self) -> bool {
        self.status_cookie
            .lock()
            .is_ok_and(|cookie| cookie.is_some())
    }

    fn unwatch_status(&self) {
        let Some(unregister) = self.api.unregister_status else {
            return;
        };
        if let Ok(mut cookie) = self.status_cookie.lock()
            && let Some(mut value) = cookie.take()
        {
            // SAFETY: The cookie came from the matching registration and is released once.
            let _ = unsafe { unregister(&raw mut value) };
        }
    }

    /// Revokes the class factory once the requests are closed. COM then starts a new server
    /// for the next request, unless this process starts serving again.
    fn shut_down(&self) -> Result<(), Error> {
        self.unwatch_status();
        // Waits for a start in progress, so that its factory is revoked too.
        let _starting = STARTING.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(cookie) = self.registration.lock()?.take() {
            com::revoke(cookie)?;
        }
        Ok(())
    }

    fn status(&self) -> Result<Status, Error> {
        let mut state = 0;
        // SAFETY: Correctly sized output and a stable CLSID.
        let result = unsafe { (self.api.state)(&raw const self.clsid, &raw mut state) };
        if result == NTE_NOT_FOUND || result == HRESULT::from_win32(ERROR_NOT_FOUND.0) {
            return Ok(Status::Unregistered);
        }
        result.ok()?;
        match state {
            0 => Ok(Status::Disabled),
            1 => Ok(Status::Enabled),
            _ => Err(Error::Invalid),
        }
    }

    fn register(&self) -> Result<(), Error> {
        let _lock = self.metadata();
        let info = authenticator_info()?;
        let options = AddOptions {
            name: w!("Keyguard"),
            clsid: &raw const self.clsid,
            rp: w!("keyguard.dev"),
            light_logo: PCWSTR::null(),
            dark_logo: PCWSTR::null(),
            info_len: len_u32(info.len())?,
            info: info.as_ptr(),
            rp_count: 0,
            rps: ptr::null(),
        };
        let mut response = ptr::null_mut();
        // SAFETY: All borrowed fields live until the synchronous call returns.
        let result = unsafe { (self.api.add)(&raw const options, &raw mut response) };
        let _response = Allocation {
            pointer: response,
            free: self.api.free_add,
        };
        if result == NTE_EXISTS {
            let options = UpdateOptions {
                name: options.name,
                clsid: options.clsid,
                new_clsid: options.clsid,
                light_logo: options.light_logo,
                dark_logo: options.dark_logo,
                info_len: options.info_len,
                info: options.info,
                rp_count: 0,
                rps: ptr::null(),
            };
            // SAFETY: Same borrowed storage as AddOptions; update preserves enablement.
            unsafe { (self.api.update)(&raw const options) }.ok()?;
        } else {
            result.ok()?;
        }
        Ok(())
    }

    fn check_signature(
        &self,
        key_api: GetKey,
        hash: &[u8; 32],
        signature: &[u8],
    ) -> Result<(), Error> {
        let mut size = 0;
        let mut pointer = ptr::null_mut();
        // SAFETY: OS writes its allocated buffer and length to live output slots.
        let result = unsafe { key_api(&raw const self.clsid, &raw mut size, &raw mut pointer) };
        // Own the buffer before checking the result, so that no path leaks it.
        let key = Allocation {
            pointer: pointer.cast(),
            free: self.api.free_key,
        };
        result.ok()?;
        // SAFETY: The OS returned `size` key bytes in this buffer.
        let bytes = unsafe { key.bytes(size, MAX_SIGNATURE) }?;
        crypto::verify_signature(bytes, hash, signature)
    }

    /// Verifies the user for the request, which [`Requests::complete`] requires of a response:
    /// with Windows Hello, whose signature this checks, or, when Keyguard reports that the user
    /// `unlocked` the vault during the request, by that unlock.
    fn verify(
        &self,
        id: u64,
        window: i64,
        username: &str,
        hint: &str,
        unlocked: bool,
    ) -> Result<(), Error> {
        if unlocked {
            return self.requests.verified(id);
        }
        let _lock = match self.verification_lock.try_lock() {
            Ok(lock) => lock,
            // The lock guards no data, so a panic while it was held left nothing inconsistent.
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
            Err(TryLockError::WouldBlock) => return Err(Error::Busy),
        };
        let context = self.requests.context(id)?;
        let transaction = GUID::from_u128(context.transaction);
        let username = wide(username)?;
        let hint = wide(hint)?;
        let request = VerifyRequest {
            window: window_handle(window)?,
            transaction: &raw const transaction,
            username: PCWSTR(username.as_ptr()),
            hint: PCWSTR(hint.as_ptr()),
        };
        let mut len = 0;
        let mut pointer = ptr::null_mut();
        // SAFETY: The transaction and strings stay alive while Hello is displayed.
        let result =
            unsafe { (self.api.verify)(&raw const request, &raw mut len, &raw mut pointer) };
        let response = Allocation {
            pointer: pointer.cast(),
            free: self.api.free_verify,
        };
        // The OS has no dismiss API. Reject a result returned after cancellation/timeout,
        // including the failure of a prompt the user closed after the request ended.
        self.requests.context(id)?;
        if result == NTE_USER_CANCELLED || result == HRESULT::from_win32(ERROR_CANCELLED.0) {
            return Err(Error::Dismissed);
        }
        result.ok()?;
        // SAFETY: The OS returned `len` signature bytes in this buffer.
        let signature = unsafe { response.bytes(len, MAX_SIGNATURE) }?;
        self.check_signature(self.api.uv_key, &context.hash, signature)?;
        self.requests.verified(id)
    }
}
