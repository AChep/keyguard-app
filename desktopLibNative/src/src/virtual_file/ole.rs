//! The OLE side of a virtual-file offer. One single-threaded apartment per
//! drag owns the data object, its streams and their JVM readers.
//!
//! Objects are not agile, so COM marshals every call, including the final
//! `Release`, to this thread. The thread keeps pumping messages after the
//! drag loop while a target still holds the data object or a stream, which
//! is how asynchronous extraction reaches it. A revoke from the JVM posts a
//! private message that only wakes the pump.

use super::java::{Java, JavaReader, OfferSeed};
use super::{
    buffer_capacity, contents_index, descriptor_offset, drag_step, drop_accepted, lifetime,
    seek_position, split_size, terminated_units, Control, DragStep, FileEntry, Handshake, Lifetime,
    OfferState, Registration, DESCRIPTOR_LINDEX, DESCRIPTOR_SIZE, GROUP_HEADER_SIZE,
    STARTUP_TIMEOUT,
};
use crate::drag::ReadStatus;
use crate::drag_jni::contained;
use std::cell::{Cell, RefCell};
use std::ffi::c_void;
use std::mem::{offset_of, size_of, ManuallyDrop};
use std::ptr;
use std::rc::Rc;
use std::sync::mpsc::{self, SyncSender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use windows::core::{implement, Error, IUnknown, Interface, Ref, Result, BOOL, HRESULT, PWSTR};
use windows::Win32::Foundation::{
    GlobalFree, DATA_S_SAMEFORMATETC, DRAGDROP_S_CANCEL, DRAGDROP_S_DROP,
    DRAGDROP_S_USEDEFAULTCURSORS, DV_E_DVASPECT, DV_E_DVTARGETDEVICE, DV_E_FORMATETC, DV_E_LINDEX,
    DV_E_TYMED, E_ABORT, E_FAIL, E_NOTIMPL, E_OUTOFMEMORY, E_POINTER, E_UNEXPECTED, LPARAM,
    OLE_E_ADVISENOTSUPPORTED, STG_E_ACCESSDENIED, STG_E_INVALIDFUNCTION, STG_E_INVALIDPOINTER,
    STG_E_MEDIUMFULL, STG_E_READFAULT, S_FALSE, S_OK, WPARAM,
};
use windows::Win32::Storage::FileSystem::FILE_ATTRIBUTE_NORMAL;
use windows::Win32::System::Com::{
    CoDisconnectObject, CoTaskMemAlloc, IAdviseSink, IBindCtx, IDataObject, IDataObject_Impl,
    IEnumFORMATETC, IEnumSTATDATA, ISequentialStream_Impl, IStream, IStream_Impl, DATADIR_GET,
    DVASPECT_CONTENT, FORMATETC, LOCKTYPE, STATFLAG, STATFLAG_NONAME, STATSTG, STGC, STGMEDIUM,
    STGMEDIUM_0, STGM_READ, STGTY_STREAM, STREAM_SEEK, TYMED, TYMED_HGLOBAL, TYMED_ISTREAM,
};
use windows::Win32::System::DataExchange::RegisterClipboardFormatW;
use windows::Win32::System::Memory::{
    GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE, GMEM_ZEROINIT,
};
use windows::Win32::System::Ole::{
    DoDragDrop, IDropSource, IDropSource_Impl, OleInitialize, OleUninitialize, DROPEFFECT,
    DROPEFFECT_COPY, DROPEFFECT_NONE,
};
use windows::Win32::System::SystemServices::{MK_LBUTTON, MODIFIERKEYS_FLAGS};
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::Shell::{
    IDataObjectAsyncCapability, IDataObjectAsyncCapability_Impl, SHCreateStdEnumFmtEtc,
    CFSTR_FILECONTENTS, CFSTR_FILEDESCRIPTORW, FD_ATTRIBUTES, FD_FILESIZE, FD_PROGRESSUI,
    FILEDESCRIPTORW, FILEGROUPDESCRIPTORW,
};
use windows::Win32::UI::WindowsAndMessaging::{
    DispatchMessageW, MsgWaitForMultipleObjectsEx, PeekMessageW, PostThreadMessageW,
    TranslateMessage, MSG, MWMO_INPUTAVAILABLE, PM_NOREMOVE, PM_REMOVE, QS_ALLINPUT, WM_APP,
    WM_USER,
};
use zeroize::Zeroizing;

/// Keeps a timed wait below `INFINITE`.
const MAX_WAIT_MILLIS: u32 = u32::MAX - 1;

/// The thread message a revoke posts. It carries nothing: the pump wakes up
/// and the offer reads its `Control`.
const REVOKE_MESSAGE: u32 = WM_APP;

// The descriptor group is laid out by hand for any number of files.
const _: () = {
    assert!(size_of::<FILEDESCRIPTORW>() == DESCRIPTOR_SIZE);
    assert!(offset_of!(FILEGROUPDESCRIPTORW, fgd) == GROUP_HEADER_SIZE);
    assert!(size_of::<FILEGROUPDESCRIPTORW>() == descriptor_offset(1));
};

/// Spawns the offer's OLE thread and waits, bounded, until it either owns
/// the offer or gives up. Only a `true` result is followed by callbacks.
/// The thread holds `registration` until its very end.
pub(super) fn start(seed: OfferSeed, registration: Registration<'static>) -> bool {
    let handshake = Arc::new(Handshake::new());
    let (ready, waiting) = mpsc::sync_channel(1);
    let worker = Arc::clone(&handshake);
    let spawned = thread::Builder::new()
        .name("keyguard-virtual-file".to_owned())
        .spawn(move || {
            if contained(|| run(seed, &worker, &ready)).is_none() {
                // A panic before ownership is a failed start. After it, the
                // drag loop's own boundary has already let `run` retire.
                worker.fail();
                let _ = ready.try_send(());
            }
            // Last: a registered offer may still post to this thread.
            drop(registration);
        });
    if spawned.is_err() {
        return false;
    }
    let _ = waiting.recv_timeout(STARTUP_TIMEOUT);
    handshake.conclude()
}

/// Wakes an offer's OLE thread after a revoke. Runs on any thread.
pub(super) fn wake(thread: u32) {
    // SAFETY: Only posts a parameterless message; `Offers::revoke` keeps the
    // thread registered, and so alive, for the call. A failed post leaves
    // the revoke to the thread's next wake-up or idle check.
    let _ = unsafe { PostThreadMessageW(thread, REVOKE_MESSAGE, WPARAM(0), LPARAM(0)) };
}

fn run(seed: OfferSeed, handshake: &Handshake, ready: &SyncSender<()>) {
    let OfferSeed {
        java,
        files,
        control,
    } = seed;
    let failed = || {
        handshake.fail();
        let _ = ready.try_send(());
    };
    if !java.attach() {
        return failed();
    }
    let mut message = MSG::default();
    // SAFETY: `message` is writable storage, and nothing is removed. The
    // call creates this thread's message queue so a revoke can post to it.
    let _ = unsafe { PeekMessageW(&mut message, None, WM_USER, WM_USER, PM_NOREMOVE) };
    // SAFETY: Takes no arguments and only reads the calling thread's id.
    control.set_thread(unsafe { GetCurrentThreadId() });
    let Ok(apartment) = OleApartment::new() else {
        return failed();
    };
    let Ok(formats) = Formats::register() else {
        return failed();
    };
    let offer = Rc::new(Offer {
        java,
        files,
        control,
        formats,
        data: Cell::new(ptr::null_mut()),
        streams: RefCell::new(Vec::new()),
        in_operation: Cell::new(false),
        last_activity: Cell::new(Instant::now()),
        retired: Cell::new(false),
    });
    let data: IDataObject = VirtualData {
        offer: Rc::clone(&offer),
        asynchronous: Cell::new(true),
    }
    .into();
    offer.data.set(data.as_raw());
    let source: IDropSource = DragSource {
        control: Arc::clone(&offer.control),
        queried: Cell::new(false),
    }
    .into();
    if !handshake.start() {
        // The caller gave up: release everything without a callback.
        return;
    }
    let _ = ready.try_send(());
    let _ = contained(|| drag(&offer, data, source));
    // Severs what is still connected: references left after a revoke, a
    // rejected drop or past the idle limit, or anything a panic left behind.
    // Then every reader is closed before `retired`.
    offer.sever();
    offer.retire();
    drop(offer);
    drop(apartment);
}

fn drag(offer: &Offer, data: IDataObject, source: IDropSource) {
    let mut effect = DROPEFFECT_NONE;
    // SAFETY: OLE is initialized on this thread, and both interfaces stay
    // referenced for the whole modal drag loop.
    let result = unsafe { DoDragDrop(&data, &source, DROPEFFECT_COPY, &mut effect) };
    drop(source);
    // From here on, the data object lives only while a target references it.
    drop(data);
    let accepted = drop_accepted(
        result == DRAGDROP_S_DROP,
        effect.0 & DROPEFFECT_COPY.0 != 0,
        offer.in_operation.get(),
    );
    offer.java.ended(accepted);
    offer.touch();
    // Retire and Sever both end the loop: `run` severs what is still connected.
    while let Lifetime::Wait(left) = lifetime(offer.state(accepted, Instant::now())) {
        pump(left);
    }
}

/// Dispatches this thread's messages, waiting at most `timeout` for the
/// first one. Runs only from the top-level loop, so no call is in flight
/// when the caller decides to sever. A revoke's message only ends the wait.
fn pump(timeout: Duration) {
    let millis = u32::try_from(timeout.as_millis())
        .unwrap_or(MAX_WAIT_MILLIS)
        .clamp(1, MAX_WAIT_MILLIS);
    // SAFETY: No handles are passed; this waits on the calling thread's queue.
    let _ = unsafe { MsgWaitForMultipleObjectsEx(None, millis, QS_ALLINPUT, MWMO_INPUTAVAILABLE) };
    let mut message = MSG::default();
    // SAFETY: `message` is writable storage that PeekMessageW fills before
    // the dispatch reads it; a null window selects this thread's messages.
    while unsafe { PeekMessageW(&mut message, None, 0, 0, PM_REMOVE) }.as_bool() {
        if message.hwnd.is_invalid() && message.message == REVOKE_MESSAGE {
            continue;
        }
        // SAFETY: The message was just retrieved for this thread.
        unsafe {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
}

/// The state of one drag, shared by its COM objects on the OLE thread.
struct Offer {
    java: Java,
    files: Vec<FileEntry>,
    control: Arc<Control>,
    formats: Formats,
    /// The data object while it is alive, cleared by its `Drop`.
    data: Cell<*mut c_void>,
    /// Streams that have not been released yet.
    streams: RefCell<Vec<Rc<StreamSlot>>>,
    in_operation: Cell<bool>,
    last_activity: Cell<Instant>,
    retired: Cell<bool>,
}

/// One stream's reader, also reachable from the offer so that retirement
/// can close it while a target still holds the stream.
struct StreamSlot {
    /// The stream while it is alive, cleared by its `Drop`.
    raw: Cell<*mut c_void>,
    reader: RefCell<Option<JavaReader>>,
}

impl StreamSlot {
    fn close(&self, java: &Java) {
        let reader = self
            .reader
            .try_borrow_mut()
            .ok()
            .and_then(|mut reader| reader.take());
        if let Some(reader) = reader {
            java.close(reader);
        }
    }
}

impl Offer {
    fn touch(&self) {
        self.last_activity.set(Instant::now());
    }

    fn state(&self, accepted: bool, now: Instant) -> OfferState {
        OfferState {
            accepted,
            revoked: self.control.is_revoked(),
            data_referenced: !self.data.get().is_null(),
            streams: self
                .streams
                .try_borrow()
                .map_or(usize::MAX, |streams| streams.len()),
            in_operation: self.in_operation.get(),
            idle: now.saturating_duration_since(self.last_activity.get()),
        }
    }

    /// Opens a stream of file `index` at `position` with its own JVM reader.
    fn open_stream(self: &Rc<Self>, index: usize, position: u64) -> Result<IStream> {
        if self.retired.get() {
            return Err(E_UNEXPECTED.into());
        }
        if self.control.is_revoked() {
            return Err(E_ABORT.into());
        }
        let file = self
            .files
            .get(index)
            .ok_or_else(|| Error::from(DV_E_LINDEX))?;
        let reader = self
            .java
            .open_reader(index, buffer_capacity(file.size))
            .ok_or_else(|| Error::from(E_FAIL))?;
        let slot = Rc::new(StreamSlot {
            raw: Cell::new(ptr::null_mut()),
            reader: RefCell::new(Some(reader)),
        });
        let stream: IStream = VirtualStream {
            offer: Rc::clone(self),
            slot: Rc::clone(&slot),
            index,
            position: Cell::new(position),
        }
        .into();
        slot.raw.set(stream.as_raw());
        // On failure, dropping the stream closes its reader.
        self.streams
            .try_borrow_mut()
            .map_err(|_| Error::from(E_UNEXPECTED))?
            .push(slot);
        self.touch();
        Ok(stream)
    }

    fn release_stream(&self, slot: &Rc<StreamSlot>) {
        slot.raw.set(ptr::null_mut());
        if let Ok(mut streams) = self.streams.try_borrow_mut() {
            streams.retain(|other| !Rc::ptr_eq(other, slot));
        }
        slot.close(&self.java);
    }

    /// Disconnects every external reference to the data object and the live
    /// streams. Called only from the top-level loop, never inside a call.
    fn sever(&self) {
        let mut targets: Vec<IUnknown> = Vec::new();
        let data = self.data.get();
        // SAFETY: A non-null pointer is cleared by the data object's `Drop`
        // on this thread, so it still points at a live object here.
        if let Some(data) = unsafe { IDataObject::from_raw_borrowed(&data) } {
            targets.push(data.clone().into());
        }
        if let Ok(streams) = self.streams.try_borrow() {
            for slot in streams.iter() {
                let raw = slot.raw.get();
                // SAFETY: As for the data object, `Drop` clears the pointer.
                if let Some(stream) = unsafe { IStream::from_raw_borrowed(&raw) } {
                    targets.push(stream.clone().into());
                }
            }
        }
        for target in &targets {
            // SAFETY: Runs on the apartment's own thread. The owned
            // references keep each object alive across the disconnect.
            let _ = unsafe { CoDisconnectObject(target, None) };
        }
        // The final releases may run `Drop`, which updates the stream list.
        drop(targets);
    }

    /// Closes every remaining reader, then reports retirement once.
    fn retire(&self) {
        if self.retired.replace(true) {
            return;
        }
        let slots = self
            .streams
            .try_borrow_mut()
            .map(|mut streams| std::mem::take(&mut *streams))
            .unwrap_or_default();
        for slot in slots {
            slot.close(&self.java);
        }
        self.java.retired();
    }
}

struct OleApartment;

impl OleApartment {
    fn new() -> Result<Self> {
        // SAFETY: Runs once on the offer's own thread; the guard balances it.
        unsafe { OleInitialize(None)? };
        Ok(Self)
    }
}

impl Drop for OleApartment {
    fn drop(&mut self) {
        // SAFETY: The guard never leaves the thread that initialized OLE.
        unsafe { OleUninitialize() };
    }
}

/// What a valid `FORMATETC` asks for.
#[derive(Clone, Copy)]
enum Request {
    Descriptor,
    Contents(usize),
}

#[derive(Clone, Copy)]
struct Formats {
    descriptor: u16,
    contents: u16,
}

impl Formats {
    fn register() -> Result<Self> {
        // SAFETY: Both constants are static, NUL-terminated UTF-16 strings.
        let (descriptor, contents) = unsafe {
            (
                RegisterClipboardFormatW(CFSTR_FILEDESCRIPTORW),
                RegisterClipboardFormatW(CFSTR_FILECONTENTS),
            )
        };
        // Registered formats are in 0xC000..=0xFFFF.
        match (u16::try_from(descriptor), u16::try_from(contents)) {
            (Ok(descriptor), Ok(contents)) if descriptor != 0 && contents != 0 => Ok(Self {
                descriptor,
                contents,
            }),
            _ => Err(E_FAIL.into()),
        }
    }

    fn entries(self) -> [FORMATETC; 2] {
        [
            Self::format(self.descriptor, DESCRIPTOR_LINDEX, TYMED_HGLOBAL),
            Self::format(self.contents, 0, TYMED_ISTREAM),
        ]
    }

    fn format(id: u16, index: i32, medium: TYMED) -> FORMATETC {
        FORMATETC {
            cfFormat: id,
            dwAspect: DVASPECT_CONTENT.0,
            lindex: index,
            tymed: medium.0 as u32,
            ..Default::default()
        }
    }

    /// Checks a request against an offer of `count` files.
    fn validate(self, input: &FORMATETC, count: usize) -> Result<Request> {
        if input.dwAspect != DVASPECT_CONTENT.0 {
            return Err(DV_E_DVASPECT.into());
        }
        if !input.ptd.is_null() {
            return Err(DV_E_DVTARGETDEVICE.into());
        }
        let (request, medium) = if input.cfFormat == self.descriptor {
            let whole = input.lindex == DESCRIPTOR_LINDEX;
            (whole.then_some(Request::Descriptor), TYMED_HGLOBAL)
        } else if input.cfFormat == self.contents {
            let index = contents_index(input.lindex, count);
            (index.map(Request::Contents), TYMED_ISTREAM)
        } else {
            return Err(DV_E_FORMATETC.into());
        };
        let request = request.ok_or_else(|| Error::from(DV_E_LINDEX))?;
        if input.tymed & medium.0 as u32 == 0 {
            return Err(DV_E_TYMED.into());
        }
        Ok(request)
    }
}

fn com<T>(body: impl FnOnce() -> Result<T>) -> Result<T> {
    contained(body).unwrap_or_else(|| Err(E_UNEXPECTED.into()))
}

fn com_status(body: impl FnOnce() -> HRESULT) -> HRESULT {
    contained(body).unwrap_or(E_UNEXPECTED)
}

fn file_descriptor(file: &FileEntry) -> FILEDESCRIPTORW {
    let (high, low) = split_size(file.size);
    FILEDESCRIPTORW {
        dwFlags: (FD_ATTRIBUTES.0 | FD_FILESIZE.0 | FD_PROGRESSUI.0) as u32,
        dwFileAttributes: FILE_ATTRIBUTE_NORMAL.0,
        nFileSizeHigh: high,
        nFileSizeLow: low,
        cFileName: file.name,
        ..Default::default()
    }
}

#[implement(IDataObject, IDataObjectAsyncCapability, Agile = false)]
struct VirtualData {
    offer: Rc<Offer>,
    asynchronous: Cell<bool>,
}

impl VirtualData {
    fn descriptor(&self) -> Result<STGMEDIUM> {
        let files = &self.offer.files;
        let count = u32::try_from(files.len()).map_err(|_| Error::from(E_UNEXPECTED))?;
        // SAFETY: Allocates a movable block that the returned medium owns.
        let handle = unsafe {
            GlobalAlloc(
                GMEM_MOVEABLE | GMEM_ZEROINIT,
                descriptor_offset(files.len()),
            )?
        };
        // SAFETY: The handle was just allocated and is still owned here.
        let pointer = unsafe { GlobalLock(handle) };
        if pointer.is_null() {
            // SAFETY: Ownership has not escaped, so the handle is freed here.
            let _ = unsafe { GlobalFree(Some(handle)) };
            return Err(E_OUTOFMEMORY.into());
        }
        let base = pointer.cast::<u8>();
        // SAFETY: The block holds `descriptor_offset(files.len())` bytes:
        // the count, then each descriptor at its offset, so every write is in
        // bounds. The shell structures are packed, so they are written
        // unaligned.
        unsafe {
            base.cast::<u32>().write_unaligned(count);
            for (index, file) in files.iter().enumerate() {
                base.add(descriptor_offset(index))
                    .cast::<FILEDESCRIPTORW>()
                    .write_unaligned(file_descriptor(file));
            }
            let _ = GlobalUnlock(handle);
        }
        Ok(STGMEDIUM {
            tymed: TYMED_HGLOBAL.0 as u32,
            u: STGMEDIUM_0 { hGlobal: handle },
            ..Default::default()
        })
    }
}

impl Drop for VirtualData {
    fn drop(&mut self) {
        self.offer.data.set(ptr::null_mut());
    }
}

impl IDataObject_Impl for VirtualData_Impl {
    fn GetData(&self, input: *const FORMATETC) -> Result<STGMEDIUM> {
        com(|| {
            // SAFETY: COM passes a readable FORMATETC or null.
            let input = unsafe { input.as_ref() }.ok_or_else(|| Error::from(E_POINTER))?;
            let index = match self.offer.formats.validate(input, self.offer.files.len())? {
                Request::Descriptor => return self.descriptor(),
                Request::Contents(index) => index,
            };
            let stream = self.offer.open_stream(index, 0)?;
            Ok(STGMEDIUM {
                tymed: TYMED_ISTREAM.0 as u32,
                u: STGMEDIUM_0 {
                    pstm: ManuallyDrop::new(Some(stream)),
                },
                ..Default::default()
            })
        })
    }

    fn GetDataHere(&self, _format: *const FORMATETC, _medium: *mut STGMEDIUM) -> Result<()> {
        Err(E_NOTIMPL.into())
    }

    fn QueryGetData(&self, input: *const FORMATETC) -> HRESULT {
        // SAFETY: COM passes a readable FORMATETC or null.
        match unsafe { input.as_ref() } {
            Some(input) => self
                .offer
                .formats
                .validate(input, self.offer.files.len())
                .map(|_| ())
                .into(),
            None => E_POINTER,
        }
    }

    fn GetCanonicalFormatEtc(&self, _input: *const FORMATETC, output: *mut FORMATETC) -> HRESULT {
        if output.is_null() {
            return E_POINTER;
        }
        // SAFETY: COM passes writable storage; no target device is returned.
        unsafe { output.write(FORMATETC::default()) };
        DATA_S_SAMEFORMATETC
    }

    fn SetData(
        &self,
        _format: *const FORMATETC,
        _medium: *const STGMEDIUM,
        _release: BOOL,
    ) -> Result<()> {
        Err(E_NOTIMPL.into())
    }

    fn EnumFormatEtc(&self, direction: u32) -> Result<IEnumFORMATETC> {
        if direction != DATADIR_GET.0 as u32 {
            return Err(E_NOTIMPL.into());
        }
        // SAFETY: The shell copies the slice into its own enumerator.
        unsafe { SHCreateStdEnumFmtEtc(&self.offer.formats.entries()) }
    }

    fn DAdvise(
        &self,
        _format: *const FORMATETC,
        _flags: u32,
        _sink: Ref<IAdviseSink>,
    ) -> Result<u32> {
        Err(OLE_E_ADVISENOTSUPPORTED.into())
    }

    fn DUnadvise(&self, _connection: u32) -> Result<()> {
        Err(OLE_E_ADVISENOTSUPPORTED.into())
    }

    fn EnumDAdvise(&self) -> Result<IEnumSTATDATA> {
        Err(OLE_E_ADVISENOTSUPPORTED.into())
    }
}

impl IDataObjectAsyncCapability_Impl for VirtualData_Impl {
    fn SetAsyncMode(&self, value: BOOL) -> Result<()> {
        self.asynchronous.set(value.as_bool());
        Ok(())
    }

    fn GetAsyncMode(&self) -> Result<BOOL> {
        Ok(self.asynchronous.get().into())
    }

    fn StartOperation(&self, _context: Ref<IBindCtx>) -> Result<()> {
        self.offer.in_operation.set(true);
        self.offer.touch();
        Ok(())
    }

    fn InOperation(&self) -> Result<BOOL> {
        Ok(self.offer.in_operation.get().into())
    }

    fn EndOperation(&self, _result: HRESULT, _context: Ref<IBindCtx>, _effects: u32) -> Result<()> {
        self.offer.in_operation.set(false);
        self.offer.touch();
        Ok(())
    }
}

#[implement(IStream, Agile = false)]
struct VirtualStream {
    offer: Rc<Offer>,
    slot: Rc<StreamSlot>,
    /// The offered file, checked when the stream opened.
    index: usize,
    position: Cell<u64>,
}

impl VirtualStream {
    fn file(&self) -> &FileEntry {
        &self.offer.files[self.index]
    }

    fn read(&self, output: &mut [u8]) -> (usize, ReadStatus) {
        let Ok(reader) = self.slot.reader.try_borrow() else {
            return (0, ReadStatus::Fault);
        };
        let Some(reader) = reader.as_ref() else {
            return (0, ReadStatus::Fault);
        };
        let position = self.position.get();
        let (count, status) = self
            .offer
            .java
            .read(reader, position, self.file().size, output);
        self.position.set(position.saturating_add(count as u64));
        self.offer.touch();
        (count, status)
    }
}

impl Drop for VirtualStream {
    fn drop(&mut self) {
        let _ = contained(|| self.offer.release_stream(&self.slot));
    }
}

impl ISequentialStream_Impl for VirtualStream_Impl {
    fn Read(&self, pv: *mut c_void, cb: u32, pcbread: *mut u32) -> HRESULT {
        com_status(|| {
            if !pcbread.is_null() {
                // SAFETY: COM passes writable optional output storage.
                unsafe { pcbread.write(0) };
            }
            if cb == 0 {
                return S_OK;
            }
            if pv.is_null() {
                return STG_E_INVALIDPOINTER;
            }
            let length = cb as usize;
            // SAFETY: The caller guarantees `cb` writable bytes at `pv`. They
            // are zeroed first, so the slice never views uninitialized memory.
            let output = unsafe {
                ptr::write_bytes(pv.cast::<u8>(), 0, length);
                std::slice::from_raw_parts_mut(pv.cast::<u8>(), length)
            };
            let (count, status) = self.read(output);
            if !pcbread.is_null() {
                // SAFETY: As above; `count` never exceeds `cb`.
                unsafe { pcbread.write(count as u32) };
            }
            match status {
                ReadStatus::Filled => S_OK,
                ReadStatus::Short => S_FALSE,
                ReadStatus::Fault => STG_E_READFAULT,
            }
        })
    }

    fn Write(&self, _pv: *const c_void, _cb: u32, pcbwritten: *mut u32) -> HRESULT {
        if !pcbwritten.is_null() {
            // SAFETY: COM passes writable optional output storage.
            unsafe { pcbwritten.write(0) };
        }
        STG_E_ACCESSDENIED
    }
}

impl IStream_Impl for VirtualStream_Impl {
    fn Seek(&self, delta: i64, origin: STREAM_SEEK, output: *mut u64) -> Result<()> {
        let next = seek_position(self.position.get(), self.file().size, delta, origin.0)
            .ok_or_else(|| Error::from(STG_E_INVALIDFUNCTION))?;
        self.position.set(next);
        if !output.is_null() {
            // SAFETY: COM passes writable optional output storage.
            unsafe { output.write(next) };
        }
        Ok(())
    }

    fn SetSize(&self, _size: u64) -> Result<()> {
        Err(STG_E_ACCESSDENIED.into())
    }

    fn CopyTo(
        &self,
        target: Ref<IStream>,
        requested: u64,
        read: *mut u64,
        written: *mut u64,
    ) -> Result<()> {
        com(|| {
            let mut total_read = 0_u64;
            let mut total_written = 0_u64;
            let result = (|| {
                let target = target.ok()?;
                let mut buffer = Zeroizing::new(vec![0_u8; buffer_capacity(self.file().size)]);
                while total_read < requested {
                    let wanted = usize::try_from(requested - total_read)
                        .map_or(buffer.len(), |left| left.min(buffer.len()));
                    let (count, status) = self.read(&mut buffer[..wanted]);
                    if status == ReadStatus::Fault {
                        return Err(STG_E_READFAULT.into());
                    }
                    if count == 0 {
                        break;
                    }
                    total_read += count as u64;
                    let mut sent = 0;
                    // SAFETY: `count` initialized bytes are readable from the
                    // buffer, and `sent` is writable for the call. No borrow
                    // of this stream is held if the call re-enters it.
                    let status = unsafe {
                        target.Write(buffer.as_ptr().cast(), count as u32, Some(&mut sent))
                    };
                    buffer[..count].fill(0);
                    total_written += u64::from(sent);
                    status.ok()?;
                    if sent as usize != count {
                        return Err(STG_E_MEDIUMFULL.into());
                    }
                }
                Ok(())
            })();
            // SAFETY: COM passes writable optional output storage.
            unsafe {
                if !read.is_null() {
                    read.write(total_read);
                }
                if !written.is_null() {
                    written.write(total_written);
                }
            }
            result
        })
    }

    fn Commit(&self, _flags: &STGC) -> Result<()> {
        Ok(())
    }

    fn Revert(&self) -> Result<()> {
        Err(STG_E_INVALIDFUNCTION.into())
    }

    fn LockRegion(&self, _offset: u64, _size: u64, _kind: &LOCKTYPE) -> Result<()> {
        Err(STG_E_INVALIDFUNCTION.into())
    }

    fn UnlockRegion(&self, _offset: u64, _size: u64, _kind: u32) -> Result<()> {
        Err(STG_E_INVALIDFUNCTION.into())
    }

    fn Stat(&self, output: *mut STATSTG, flags: &STATFLAG) -> Result<()> {
        if output.is_null() {
            return Err(STG_E_INVALIDPOINTER.into());
        }
        let name = if flags.0 & STATFLAG_NONAME.0 == 0 {
            let units = terminated_units(&self.file().name);
            // SAFETY: Allocates a block that the caller frees with CoTaskMemFree.
            let name = unsafe { CoTaskMemAlloc(size_of_val(units)) }.cast::<u16>();
            if name.is_null() {
                return Err(E_OUTOFMEMORY.into());
            }
            // SAFETY: The new block holds exactly `units.len()` UTF-16 units.
            unsafe { ptr::copy_nonoverlapping(units.as_ptr(), name, units.len()) };
            name
        } else {
            ptr::null_mut()
        };
        // SAFETY: COM passes writable storage for one STATSTG.
        unsafe {
            output.write(STATSTG {
                pwcsName: PWSTR(name),
                r#type: STGTY_STREAM.0 as u32,
                cbSize: self.file().size,
                grfMode: STGM_READ,
                ..Default::default()
            });
        }
        Ok(())
    }

    fn Clone(&self) -> Result<IStream> {
        com(|| self.offer.open_stream(self.index, self.position.get()))
    }
}

#[implement(IDropSource, Agile = false)]
struct DragSource {
    control: Arc<Control>,
    queried: Cell<bool>,
}

impl IDropSource_Impl for DragSource_Impl {
    fn QueryContinueDrag(&self, escape: BOOL, keys: MODIFIERKEYS_FLAGS) -> HRESULT {
        let first = !self.queried.replace(true);
        // A revoke cancels the drag like Escape.
        let escape = escape.as_bool() || self.control.is_revoked();
        match drag_step(first, escape, keys.0 & MK_LBUTTON.0 != 0) {
            DragStep::Continue => S_OK,
            DragStep::Drop => DRAGDROP_S_DROP,
            DragStep::Cancel => DRAGDROP_S_CANCEL,
        }
    }

    fn GiveFeedback(&self, _effect: DROPEFFECT) -> HRESULT {
        DRAGDROP_S_USEDEFAULTCURSORS
    }
}
