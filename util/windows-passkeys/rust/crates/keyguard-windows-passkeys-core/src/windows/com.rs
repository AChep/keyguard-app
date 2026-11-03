#![allow(non_snake_case)]
use super::{
    MAX_SIGNATURE, PANIC_HOOK, Provider,
    abi::{Cancel, Operation, OperationResponse},
    decode::read_slice,
    len_u32, window_value,
};
use crate::{
    error::Error,
    protocol::{MAX_REQUEST, Status},
    requests::Context,
};
use keyguard_ffi::contained;
use sha2::{Digest, Sha256};
use std::{
    ffi::c_void,
    ptr,
    sync::{
        Arc, Mutex, PoisonError,
        atomic::Ordering,
        mpsc::{self, Receiver, Sender, SyncSender},
    },
    time::Duration,
};
use windows::{
    Win32::{
        Foundation::{
            CLASS_E_NOAGGREGATION, CO_E_SERVER_STOPPING, E_FAIL, E_INVALIDARG, E_OUTOFMEMORY,
            RPC_E_TOO_LATE, S_OK,
        },
        System::Com::{
            CLSCTX_LOCAL_SERVER, COINIT_MULTITHREADED, CoInitializeEx, CoInitializeSecurity,
            CoRegisterClassObject, CoRevokeClassObject, CoTaskMemAlloc, CoUninitialize, EOAC_NONE,
            IClassFactory, IClassFactory_Impl, REGCLS_MULTIPLEUSE, RPC_C_AUTHN_LEVEL_DEFAULT,
            RPC_C_IMP_LEVEL_IMPERSONATE,
        },
    },
    core::{BOOL, GUID, HRESULT, IUnknown, IUnknown_Vtbl, Interface, Ref, implement, interface},
};

// The IID, vtable slots and signatures are checked by abi_layout.rs.
#[interface("d26bcf6f-b54c-43ff-9f06-d5bf148625f7")]
pub(super) unsafe trait IPluginAuthenticator: IUnknown {
    fn MakeCredential(
        &self,
        request: *const Operation,
        response: *mut OperationResponse,
    ) -> HRESULT;
    fn GetAssertion(&self, request: *const Operation, response: *mut OperationResponse) -> HRESULT;
    fn CancelOperation(&self, request: *const Cancel) -> HRESULT;
    fn GetLockStatus(&self, status: *mut u32) -> HRESULT;
}

#[implement(IClassFactory)]
struct Factory(Arc<Provider>);
impl IClassFactory_Impl for Factory_Impl {
    fn CreateInstance(
        &self,
        outer: Ref<IUnknown>,
        iid: *const GUID,
        object: *mut *mut c_void,
    ) -> windows::core::Result<()> {
        contained(PANIC_HOOK, || {
            if !outer.is_null() {
                return Err(CLASS_E_NOAGGREGATION.into());
            }
            if iid.is_null() || !iid.is_aligned() || object.is_null() || !object.is_aligned() {
                return Err(E_INVALIDARG.into());
            }
            // Retired, but not yet revoked: COM retries this activation in a new server.
            if self.0.requests.is_closed() {
                return Err(CO_E_SERVER_STOPPING.into());
            }
            let instance: IPluginAuthenticator = Authenticator(self.0.clone()).into();
            // SAFETY: COM supplied aligned output and IID pointers. Query adds the caller's
            // reference.
            unsafe { instance.query(iid, object) }.ok()
        })
        .unwrap_or_else(|_| Err(E_FAIL.into()))
    }
    fn LockServer(&self, _lock: BOOL) -> windows::core::Result<()> {
        Ok(())
    }
}

#[implement(IPluginAuthenticator)]
struct Authenticator(Arc<Provider>);

fn boundary(body: impl FnOnce() -> Result<(), Error>) -> HRESULT {
    match contained(PANIC_HOOK, body) {
        Ok(Ok(())) => S_OK,
        Ok(Err(error)) => error.hresult(),
        Err(_) => E_FAIL,
    }
}

impl Authenticator {
    /// Verifies Windows' signature over the request, decodes it, and blocks until Kotlin
    /// completes the ceremony or it ends.
    ///
    /// # Safety
    /// Each pointer must be null or valid for this call, as COM supplies them.
    unsafe fn operation(
        &self,
        create: bool,
        request: *const Operation,
        response: *mut OperationResponse,
    ) -> Result<(), Error> {
        if request.is_null()
            || !request.is_aligned()
            || response.is_null()
            || !response.is_aligned()
        {
            return Err(Error::Invalid);
        }
        // SAFETY: COM supplied this output structure, validated above, for this call.
        unsafe {
            response.write(OperationResponse {
                len: 0,
                bytes: ptr::null_mut(),
            });
        }
        // Windows already routed this call here: retirement must wait until it returns.
        let _call = self.0.requests.enter()?;
        // SAFETY: COM owns this request structure, validated above, for this call only.
        let request = unsafe { &*request };
        if request.request_type != 1 || self.0.status()? != Status::Enabled {
            return Err(Error::Invalid);
        }
        // SAFETY: COM marshaled these buffers; read_slice bounds them before access.
        let bytes =
            unsafe { read_slice(request, request.request, request.request_len, MAX_REQUEST) }?;
        // SAFETY: Same COM buffer ownership for the signature.
        let signature = unsafe {
            read_slice(
                request,
                request.signature,
                request.signature_len,
                MAX_SIGNATURE,
            )
        }?;
        let hash: [u8; 32] = Sha256::digest(bytes).into();
        self.0
            .check_signature(self.0.api.signing_key, &hash, signature)?;
        let decoded = self.0.decode(create, bytes, window_value(request.window))?;
        let id = self.0.requests.begin(Context {
            transaction: request.transaction.to_u128(),
            hash,
            request: decoded,
        })?;
        let data = self.0.requests.wait(id)?;
        let len = len_u32(data.len())?;
        if len == 0 {
            return Err(Error::Internal);
        }
        // Allocate using the COM allocator expected by the Windows proxy. Never pass Rust storage.
        // SAFETY: Any nonzero bounded size is accepted by the COM allocator.
        let output = unsafe { CoTaskMemAlloc(data.len()) }.cast::<u8>();
        if output.is_null() {
            return Err(Error::Os(E_OUTOFMEMORY.0));
        }
        // SAFETY: The new allocation holds exactly `data.len()` bytes.
        unsafe { ptr::copy_nonoverlapping(data.as_ptr(), output, data.len()) };
        // SAFETY: COM supplied this output structure, validated above, for this call.
        unsafe { response.write(OperationResponse { len, bytes: output }) };
        Ok(())
    }
}

impl IPluginAuthenticator_Impl for Authenticator_Impl {
    unsafe fn MakeCredential(
        &self,
        request: *const Operation,
        response: *mut OperationResponse,
    ) -> HRESULT {
        // SAFETY: COM supplies the operation buffers; operation validates them.
        boundary(|| unsafe { self.operation(true, request, response) })
    }
    unsafe fn GetAssertion(
        &self,
        request: *const Operation,
        response: *mut OperationResponse,
    ) -> HRESULT {
        // SAFETY: Same COM call contract as MakeCredential.
        boundary(|| unsafe { self.operation(false, request, response) })
    }
    unsafe fn CancelOperation(&self, request: *const Cancel) -> HRESULT {
        boundary(|| {
            if request.is_null() || !request.is_aligned() {
                return Err(Error::Invalid);
            }
            // SAFETY: Non-null aligned COM request structure.
            let request = unsafe { &*request };
            let context = self.0.requests.current().ok_or(Error::Invalid)?;
            if request.transaction.to_u128() != context.transaction {
                return Err(Error::Invalid);
            }
            // SAFETY: COM signature buffer, bounded before access.
            let signature = unsafe {
                read_slice(
                    request,
                    request.signature,
                    request.signature_len,
                    MAX_SIGNATURE,
                )
            }?;
            // Cancellation is signed over the original operation's hash, not the GUID.
            self.0
                .check_signature(self.0.api.signing_key, &context.hash, signature)?;
            self.0
                .requests
                .complete(context.request.id, Err(Error::Cancelled))
        })
    }
    unsafe fn GetLockStatus(&self, status: *mut u32) -> HRESULT {
        boundary(|| {
            if status.is_null() || !status.is_aligned() {
                return Err(Error::Invalid);
            }
            // SAFETY: Valid COM scalar output; no vault access/unlock occurs here.
            unsafe { status.write(u32::from(!self.0.locked.load(Ordering::Acquire))) };
            Ok(())
        })
    }
}

/// Work for the apartment thread.
type Job = Box<dyn FnOnce() + Send>;

/// How long a caller waits for the apartment thread to start or to run a job.
const APARTMENT_TIMEOUT: Duration = Duration::from_secs(5);

/// The thread that keeps the process's multithreaded apartment. COM sets the process's
/// security only once, and the provider API relies on the apartment from any thread, so both
/// last as long as the process. Class factories come and go with the servers.
static APARTMENT: Mutex<Option<Sender<Job>>> = Mutex::new(None);

/// Initializes the apartment and the process's COM security, once. A failure is retried on
/// the next call.
pub(super) fn initialize() -> Result<(), Error> {
    apartment().map(drop)
}

/// Publishes the provider's class factory until [`revoke`]; returns its registration cookie.
pub(super) fn register(provider: &Arc<Provider>) -> Result<u32, Error> {
    let (reply, cookie) = mpsc::channel();
    let server = provider.clone();
    on_apartment(move || {
        let factory: IClassFactory = Factory(server.clone()).into();
        // SAFETY: COM takes its own reference to the factory until revocation, and the CLSID
        // lives for this synchronous call.
        let result = unsafe {
            CoRegisterClassObject(
                &raw const server.clsid,
                &factory,
                CLSCTX_LOCAL_SERVER,
                REGCLS_MULTIPLEUSE,
            )
        }
        .map_err(Error::from);
        // A caller that stopped waiting can never revoke this registration, so revoke it here.
        if let Err(mpsc::SendError(Ok(cookie))) = reply.send(result) {
            revoke_class(cookie);
        }
    })?;
    cookie
        .recv_timeout(APARTMENT_TIMEOUT)
        .map_err(|_| Error::Internal)?
}

/// Revokes a class factory that [`register`] published. COM routes no new activation here.
pub(super) fn revoke(cookie: u32) -> Result<(), Error> {
    let (reply, revoked) = mpsc::channel();
    on_apartment(move || {
        revoke_class(cookie);
        let _ = reply.send(());
    })?;
    revoked
        .recv_timeout(APARTMENT_TIMEOUT)
        .map_err(|_| Error::Internal)
}

fn revoke_class(cookie: u32) {
    // SAFETY: The cookie came from a registration in this apartment and is revoked once.
    let _ = unsafe { CoRevokeClassObject(cookie) };
}

/// Runs the job on the apartment thread. A panic fails only the job: its reply is dropped.
fn on_apartment(job: impl FnOnce() + Send + 'static) -> Result<(), Error> {
    let job: Job = Box::new(move || {
        let _ = contained(PANIC_HOOK, job);
    });
    apartment()?.send(job).map_err(|_| Error::Internal)
}

fn apartment() -> Result<Sender<Job>, Error> {
    let mut apartment = APARTMENT.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(jobs) = apartment.as_ref() {
        return Ok(jobs.clone());
    }
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    let (jobs, queue) = mpsc::channel();
    std::thread::Builder::new()
        .name("Keyguard passkey COM".into())
        .spawn(move || {
            let result =
                contained(PANIC_HOOK, || run(&ready_tx, &queue)).unwrap_or(Err(Error::Internal));
            if let Err(error) = result {
                let _ = ready_tx.send(Err(error));
            }
        })
        .map_err(|_| Error::Internal)?;
    // A thread that starts too late finds its queue closed and ends.
    ready_rx
        .recv_timeout(APARTMENT_TIMEOUT)
        .map_err(|_| Error::Internal)??;
    *apartment = Some(jobs.clone());
    Ok(jobs)
}

/// Joins the apartment on this thread and runs its jobs until every sender is gone, which the
/// static's sender prevents for the life of the process. A failure before `ready` is the
/// result; the caller reports it.
fn run(ready: &SyncSender<Result<(), Error>>, queue: &Receiver<Job>) -> Result<(), Error> {
    // MTA lets a signed cancellation arrive while a ceremony waits for Kotlin/Hello.
    // SAFETY: Initializes COM on this dedicated thread, paired below with uninitialize.
    unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.ok()?;
    let result = initialize_security();
    if result.is_ok() {
        let _ = ready.send(Ok(()));
        for job in queue {
            job();
        }
    }
    // SAFETY: Paired with this thread's successful CoInitializeEx.
    unsafe { CoUninitialize() };
    result
}

/// Sets the process's COM security before any class factory is published. COM keeps the
/// settings of whoever sets them first, so Keyguard starts the provider before anything else
/// in the process uses COM. Settings that are already in place are kept.
fn initialize_security() -> Result<(), Error> {
    // SAFETY: Default security services; all reserved parameters are null.
    let security = unsafe {
        CoInitializeSecurity(
            None,
            -1,
            None,
            None,
            RPC_C_AUTHN_LEVEL_DEFAULT,
            RPC_C_IMP_LEVEL_IMPERSONATE,
            None,
            EOAC_NONE,
            None,
        )
    };
    match security {
        Err(error) if error.code() != RPC_E_TOO_LATE => Err(error.into()),
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plugin_vtable_dispatches_without_plugin_exports() {
        let (provider, _calls) = super::super::tests::provider();
        let provider = Arc::new(provider);
        let instance: IPluginAuthenticator = Authenticator(provider.clone()).into();
        let vtable = instance.vtable();
        let object = instance.as_raw();
        let mut status = u32::MAX;
        assert_eq!(
            // SAFETY: The interface owns `object`, and status is a live output scalar.
            unsafe { (vtable.GetLockStatus)(object, &raw mut status) },
            S_OK
        );
        assert_eq!(status, 0);
        provider.locked.store(false, Ordering::Release);
        assert_eq!(
            // SAFETY: Same live interface and output scalar.
            unsafe { (vtable.GetLockStatus)(object, &raw mut status) },
            S_OK
        );
        assert_eq!(status, 1);
        assert_eq!(
            // SAFETY: The COM methods explicitly reject null request/output pointers.
            unsafe { (vtable.MakeCredential)(object, ptr::null(), ptr::null_mut()) },
            E_INVALIDARG
        );
        assert_eq!(
            // SAFETY: Same null-pointer validation contract.
            unsafe { (vtable.GetAssertion)(object, ptr::null(), ptr::null_mut()) },
            E_INVALIDARG
        );
        assert_eq!(
            // SAFETY: CancelOperation explicitly rejects a null request pointer.
            unsafe { (vtable.CancelOperation)(object, ptr::null()) },
            E_INVALIDARG
        );
        // QueryInterface must preserve this interface's identity through IUnknown.
        let unknown: IUnknown = instance.cast().unwrap();
        let queried: IPluginAuthenticator = unknown.cast().unwrap();
        assert_eq!(queried.as_raw(), object);
    }
}
