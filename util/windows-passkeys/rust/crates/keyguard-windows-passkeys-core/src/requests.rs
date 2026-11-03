//! Process-wide ceremony ownership. No COM/JNI pointers survive their call frame.
use crate::{
    error::Error,
    protocol::{Event, Request},
};
use std::{
    sync::{
        Arc, Condvar, Mutex, PoisonError,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

pub(crate) const TIMEOUT: Duration = Duration::from_secs(120);

/// The request id is assigned by [`Requests::begin`].
pub(crate) struct Context {
    pub transaction: u128,
    pub hash: [u8; 32],
    pub request: Request,
}

struct Pending {
    /// Shared, so each ceremony step's liveness check does not copy the request.
    context: Arc<Context>,
    deadline: Instant,
    response: Option<Result<Vec<u8>, Error>>,
    /// The user was verified for this request; see [`Requests::verified`].
    verified: bool,
}
impl Pending {
    fn id(&self) -> u64 {
        self.context.request.id
    }
    fn is_live(&self) -> bool {
        self.response.is_none() && self.deadline > Instant::now()
    }
}
#[derive(Default)]
struct State {
    next: u64,
    pending: Option<Pending>,
    /// COM operation calls that have yet to return, including those not yet begun.
    calls: usize,
    closed: bool,
    status_changed: bool,
}
impl State {
    fn live_pending(&mut self) -> Option<&mut Pending> {
        self.pending.as_mut().filter(|pending| pending.is_live())
    }

    fn live(&mut self, id: u64) -> Result<&mut Pending, Error> {
        self.live_pending()
            .filter(|pending| pending.id() == id)
            .ok_or(Error::Cancelled)
    }
}

/// One condition variable serves the COM waiter and the host's event waiter: always notify all.
#[derive(Default)]
pub(crate) struct Requests {
    state: Mutex<State>,
    changed: Condvar,
}

/// A COM operation call from its entry until it returns. Retirement waits for it.
pub(crate) struct Call<'a>(&'a Requests);
impl Drop for Call<'_> {
    fn drop(&mut self) {
        // Count the call out even on a poisoned lock, or the process could never retire.
        let mut state = self.0.state.lock().unwrap_or_else(PoisonError::into_inner);
        state.calls = state.calls.saturating_sub(1);
    }
}

impl Requests {
    /// Counts a COM operation call from its entry, while Windows' signature and payload are
    /// still being checked, so [`Requests::close_if_idle`] cannot close the requests under it.
    pub(crate) fn enter(&self) -> Result<Call<'_>, Error> {
        let mut state = self.state.lock()?;
        if state.closed {
            return Err(Error::Cancelled);
        }
        state.calls = state.calls.checked_add(1).ok_or(Error::Internal)?;
        Ok(Call(self))
    }

    /// Whether no new request can begin. A broken lock reads as closed.
    pub(crate) fn is_closed(&self) -> bool {
        self.state.lock().map_or(true, |state| state.closed)
    }

    pub(crate) fn begin(&self, mut context: Context) -> Result<u64, Error> {
        let mut state = self.state.lock()?;
        if state.closed {
            return Err(Error::Cancelled);
        }
        if state.pending.is_some() {
            return Err(Error::Busy);
        }
        state.next = state.next.checked_add(1).ok_or(Error::Internal)?;
        context.request.id = state.next;
        state.pending = Some(Pending {
            context: Arc::new(context),
            deadline: Instant::now() + TIMEOUT,
            response: None,
            verified: false,
        });
        self.changed.notify_all();
        Ok(state.next)
    }

    /// Blocks until the live request is not the `known` one, the provider status changed, the
    /// requests close, the `timeout` passes, or the operation is `canceled`; without a
    /// timeout, the wait has no bound. A live request that is not `known` is delivered, so a
    /// host that lost a delivery receives it again instead of only its id. The provider sets
    /// the event's `watching`.
    pub(crate) fn next(
        &self,
        known: Option<u64>,
        timeout: Option<Duration>,
        canceled: &AtomicBool,
    ) -> Result<Event, Error> {
        // A timeout too long to represent never passes.
        let until = timeout.and_then(|timeout| Instant::now().checked_add(timeout));
        let mut state = self.state.lock()?;
        loop {
            // Checked under the lock that `wake` takes, so a cancellation cannot be missed.
            if canceled.load(Ordering::Acquire) {
                return Err(Error::Cancelled);
            }
            if state.closed {
                return Ok(Event {
                    closed: true,
                    ..Event::default()
                });
            }
            let status_changed = std::mem::take(&mut state.status_changed);
            let now = Instant::now();
            let mut event = Event::default();
            if let Some(pending) = state.live_pending() {
                event.active_id = Some(pending.id());
                if known != event.active_id {
                    let mut request = pending.context.request.clone();
                    // Monotonic deadlines do not cross the bridge; the time left does.
                    let left = pending.deadline.saturating_duration_since(now);
                    request.expires_in_ms = u64::try_from(left.as_millis()).unwrap_or(u64::MAX);
                    event.request = Some(request);
                }
            }
            if event.active_id != known || status_changed || until.is_some_and(|until| now >= until)
            {
                return Ok(event);
            }
            // Expiry has no notification: also wake at the live request's deadline.
            let deadline = state.live_pending().map(|pending| pending.deadline);
            state = match deadline.into_iter().chain(until).min() {
                Some(wake) => {
                    self.changed
                        .wait_timeout(state, wake.saturating_duration_since(now))?
                        .0
                }
                None => self.changed.wait(state)?,
            };
        }
    }

    /// Wakes every [`Requests::next`] to check whether its operation was canceled.
    pub(crate) fn wake(&self) {
        // Take the lock even if poisoned: a waiter checks its cancellation under it.
        let _state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        self.changed.notify_all();
    }

    /// Wakes [`Requests::next`]; Windows reported a provider enablement change.
    pub(crate) fn status_changed(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.status_changed = true;
            self.changed.notify_all();
        }
    }

    pub(crate) fn context(&self, id: u64) -> Result<Arc<Context>, Error> {
        let mut state = self.state.lock()?;
        state.live(id).map(|pending| pending.context.clone())
    }

    pub(crate) fn current(&self) -> Option<Arc<Context>> {
        let mut state = self.state.lock().ok()?;
        Some(state.live_pending()?.context.clone())
    }

    /// Records that the user was verified for the live request `id`: by Windows Hello, or by
    /// unlocking the vault during it.
    pub(crate) fn verified(&self, id: u64) -> Result<(), Error> {
        let mut state = self.state.lock()?;
        state.live(id)?.verified = true;
        Ok(())
    }

    /// Answers the live request `id`. Every response tells the site that the user was
    /// verified, so a request that was not ends instead, and the caller learns why.
    pub(crate) fn complete(&self, id: u64, response: Result<Vec<u8>, Error>) -> Result<(), Error> {
        let mut state = self.state.lock()?;
        let pending = state.live(id)?;
        let refused = response.is_ok() && !pending.verified;
        pending.response = Some(if refused {
            Err(Error::Cancelled)
        } else {
            response
        });
        self.changed.notify_all();
        if refused {
            return Err(Error::Unverified);
        }
        Ok(())
    }

    pub(crate) fn wait(&self, id: u64) -> Result<Vec<u8>, Error> {
        let mut state = self.state.lock()?;
        loop {
            let pending = state.pending.as_mut().ok_or(Error::Cancelled)?;
            if pending.id() != id {
                return Err(Error::Cancelled);
            }
            if let Some(response) = pending.response.take() {
                state.pending = None;
                return response;
            }
            let remaining = pending.deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                state.pending = None;
                return Err(Error::Cancelled);
            }
            state = self.changed.wait_timeout(state, remaining)?.0;
        }
    }

    pub(crate) fn cancel(&self) {
        if let Ok(mut state) = self.state.lock()
            && let Some(pending) = state.live_pending()
        {
            pending.response = Some(Err(Error::Cancelled));
            self.changed.notify_all();
        }
    }

    pub(crate) fn close(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.closed = true;
            if let Some(pending) = state.pending.as_mut() {
                pending.response = Some(Err(Error::Cancelled));
            }
            self.changed.notify_all();
        }
    }

    /// Closes the requests unless a COM call has yet to return: one still checking its request,
    /// a live one, or one whose response is ready. Atomic with [`Requests::enter`] and
    /// [`Requests::begin`], so no request can slip in before closing.
    pub(crate) fn close_if_idle(&self) -> Result<bool, Error> {
        let mut state = self.state.lock()?;
        if state.pending.is_some() || state.calls > 0 {
            return Ok(false);
        }
        state.closed = true;
        self.changed.notify_all();
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::UserVerification;

    fn context() -> Context {
        Context {
            transaction: 1,
            hash: [0; 32],
            request: Request {
                rp_id: "example.com".into(),
                user_verification: UserVerification::Required.into(),
                discoverable: true,
                ..Request::default()
            },
        }
    }

    impl Requests {
        /// The wait of an operation that nobody cancels.
        fn poll(&self, known: Option<u64>, timeout: Duration) -> Result<Event, Error> {
            self.next(known, Some(timeout), &AtomicBool::new(false))
        }
    }

    fn set_deadline(requests: &Requests, deadline: Instant) {
        requests
            .state
            .lock()
            .unwrap()
            .pending
            .as_mut()
            .unwrap()
            .deadline = deadline;
    }

    /// (delivered request id, active id, closed)
    type Summary = (Option<u64>, Option<u64>, bool);

    fn summary(event: Event) -> Summary {
        (
            event.request.map(|request| request.id),
            event.active_id,
            event.closed,
        )
    }

    /// Starts a blocking `next`; the returned function awaits its result.
    fn next_in_background(
        requests: &Arc<Requests>,
        known: Option<u64>,
    ) -> impl FnOnce() -> Summary {
        let (sent, received) = std::sync::mpsc::channel();
        let waiter = requests.clone();
        let thread = std::thread::spawn(move || {
            let event = waiter.poll(known, Duration::from_secs(30)).unwrap();
            sent.send(summary(event)).unwrap();
        });
        move || {
            let result = received.recv_timeout(Duration::from_secs(2)).unwrap();
            thread.join().unwrap();
            result
        }
    }

    #[test]
    fn only_one_live_request_and_no_late_completion() {
        let requests = Requests::default();
        let first = requests.begin(context()).unwrap();
        assert_eq!(requests.begin(context()), Err(Error::Busy));
        let event = requests.poll(None, Duration::ZERO).unwrap();
        assert_eq!(summary(event), (Some(first), Some(first), false));
        let event = requests.poll(Some(first), Duration::ZERO).unwrap();
        assert_eq!(summary(event), (None, Some(first), false));
        requests.complete(first, Err(Error::Cancelled)).unwrap();
        assert_eq!(requests.wait(first), Err(Error::Cancelled));
        let second = requests.begin(context()).unwrap();
        assert_ne!(first, second);
        assert_eq!(requests.complete(first, Ok(vec![0])), Err(Error::Cancelled));
        assert!(requests.context(second).is_ok());
        requests.close();
        assert_eq!(requests.wait(second), Err(Error::Cancelled));
        assert_eq!(requests.begin(context()), Err(Error::Cancelled));
    }

    #[test]
    fn a_response_answers_only_a_verified_request() {
        let requests = Requests::default();
        let id = requests.begin(context()).unwrap();
        // A response tells the site that the user was verified: without that, the request ends.
        assert_eq!(requests.complete(id, Ok(vec![0])), Err(Error::Unverified));
        assert_eq!(requests.wait(id), Err(Error::Cancelled));

        let id = requests.begin(context()).unwrap();
        requests.verified(id).unwrap();
        requests.complete(id, Ok(vec![0])).unwrap();
        assert_eq!(requests.wait(id), Ok(vec![0]));
        // A request that has ended cannot be verified.
        assert_eq!(requests.verified(id), Err(Error::Cancelled));
    }

    #[test]
    fn a_lost_delivery_is_delivered_again() {
        let requests = Requests::default();
        let id = requests.begin(context()).unwrap();
        let event = requests.poll(None, Duration::ZERO).unwrap();
        assert_eq!(summary(event), (Some(id), Some(id), false));
        // The host failed to read the reply, so it still knows no request.
        let event = requests.poll(None, Duration::ZERO).unwrap();
        assert_eq!(summary(event), (Some(id), Some(id), false));
        let event = requests.poll(Some(id), Duration::ZERO).unwrap();
        assert_eq!(summary(event), (None, Some(id), false));
    }

    #[test]
    fn expiration_rejects_verification_and_completion() {
        let requests = Requests::default();
        let id = requests.begin(context()).unwrap();
        set_deadline(&requests, Instant::now());
        assert!(requests.context(id).is_err());
        let event = requests.poll(None, Duration::ZERO).unwrap();
        assert_eq!(summary(event), (None, None, false));
        assert_eq!(requests.complete(id, Ok(vec![0])), Err(Error::Cancelled));
        assert_eq!(requests.wait(id), Err(Error::Cancelled));
    }

    #[test]
    fn a_delivered_request_reports_the_time_left() {
        let requests = Requests::default();
        requests.begin(context()).unwrap();
        // As if the host picked the request up 110 seconds into its 120-second deadline.
        set_deadline(&requests, Instant::now() + Duration::from_secs(10));
        let request = requests
            .poll(None, Duration::ZERO)
            .unwrap()
            .request
            .unwrap();
        assert!(
            (9_000..=10_000).contains(&request.expires_in_ms),
            "{}",
            request.expires_in_ms
        );
    }

    #[test]
    fn a_new_request_wakes_the_event_waiter() {
        let requests = Arc::new(Requests::default());
        let event = next_in_background(&requests, None);
        let id = requests.begin(context()).unwrap();
        assert_eq!(event(), (Some(id), Some(id), false));
    }

    #[test]
    fn ending_the_live_request_wakes_the_event_waiter() {
        let requests = Arc::new(Requests::default());
        let ends: [fn(&Requests, u64); 2] = [
            |requests, _| requests.cancel(),
            |requests, id| {
                requests.verified(id).unwrap();
                requests.complete(id, Ok(vec![0])).unwrap();
            },
        ];
        for end in ends {
            let id = requests.begin(context()).unwrap();
            requests.poll(None, Duration::ZERO).unwrap();
            let event = next_in_background(&requests, Some(id));
            end(&requests, id);
            assert_eq!(event(), (None, None, false));
            let _ = requests.wait(id);
        }

        // Expiry sends no notification; the waiter wakes at the deadline.
        let id = requests.begin(context()).unwrap();
        requests.poll(None, Duration::ZERO).unwrap();
        set_deadline(&requests, Instant::now() + Duration::from_millis(50));
        let event = next_in_background(&requests, Some(id));
        assert_eq!(event(), (None, None, false));
    }

    #[test]
    fn status_change_and_close_wake_the_event_waiter() {
        let requests = Arc::new(Requests::default());
        let event = next_in_background(&requests, None);
        requests.status_changed();
        assert_eq!(event(), (None, None, false));
        // The change is consumed: an idle wait now times out.
        let event = requests.poll(None, Duration::from_millis(10)).unwrap();
        assert_eq!(summary(event), (None, None, false));

        let event = next_in_background(&requests, None);
        requests.close();
        assert_eq!(event(), (None, None, true));
        let event = requests.poll(None, Duration::from_secs(30)).unwrap();
        assert!(event.closed);
    }

    #[test]
    fn cancellation_ends_an_unbounded_wait_at_once() {
        let requests = Arc::new(Requests::default());
        let canceled = Arc::new(AtomicBool::new(false));
        let (sent, received) = std::sync::mpsc::channel();
        let (waiter, flag) = (requests.clone(), canceled.clone());
        let thread = std::thread::spawn(move || sent.send(waiter.next(None, None, &flag)).unwrap());
        // Nothing else would end this wait.
        assert!(received.recv_timeout(Duration::from_millis(50)).is_err());
        canceled.store(true, Ordering::Release);
        requests.wake();
        assert_eq!(
            received.recv_timeout(Duration::from_secs(2)).unwrap(),
            Err(Error::Cancelled)
        );
        thread.join().unwrap();
        // A canceled operation does not wait at all.
        assert_eq!(requests.next(None, None, &canceled), Err(Error::Cancelled));
    }

    #[test]
    fn a_timeout_too_long_to_represent_still_returns_events() {
        let requests = Requests::default();
        let id = requests.begin(context()).unwrap();
        let event = requests
            .next(
                None,
                Some(Duration::from_millis(u64::MAX)),
                &AtomicBool::new(false),
            )
            .unwrap();
        assert_eq!(summary(event), (Some(id), Some(id), false));
    }

    #[test]
    fn closing_when_idle_waits_until_the_com_call_returns() {
        let requests = Requests::default();
        let id = requests.begin(context()).unwrap();
        assert_eq!(requests.close_if_idle(), Ok(false));
        requests.verified(id).unwrap();
        requests.complete(id, Ok(vec![0])).unwrap();
        // The response is ready, but Windows has not received it yet.
        assert_eq!(requests.close_if_idle(), Ok(false));
        assert_eq!(requests.wait(id), Ok(vec![0]));

        assert_eq!(requests.close_if_idle(), Ok(true));
        assert_eq!(requests.begin(context()), Err(Error::Cancelled));
        assert!(requests.poll(None, Duration::from_secs(30)).unwrap().closed);
    }

    #[test]
    fn closing_when_idle_waits_for_a_call_that_has_yet_to_begin() {
        let requests = Requests::default();
        // Windows routed a call here; its signature and payload are still being checked.
        let call = requests.enter().unwrap();
        assert_eq!(requests.close_if_idle(), Ok(false));
        assert!(!requests.is_closed());
        let id = requests.begin(context()).unwrap();
        requests.verified(id).unwrap();
        requests.complete(id, Ok(vec![0])).unwrap();
        assert_eq!(requests.wait(id), Ok(vec![0]));
        // The response is taken, but the call has yet to return it to Windows.
        assert_eq!(requests.close_if_idle(), Ok(false));

        drop(call);
        assert_eq!(requests.close_if_idle(), Ok(true));
        assert!(requests.is_closed());
        assert!(matches!(requests.enter(), Err(Error::Cancelled)));
    }

    #[test]
    fn closing_when_idle_wakes_the_event_waiter() {
        let requests = Arc::new(Requests::default());
        let event = next_in_background(&requests, None);
        assert_eq!(requests.close_if_idle(), Ok(true));
        assert_eq!(event(), (None, None, true));
    }

    #[test]
    fn cancellation_wakes_the_waiting_com_call() {
        let requests = Arc::new(Requests::default());
        let id = requests.begin(context()).unwrap();
        let (sent, received) = std::sync::mpsc::channel();
        let waiter = requests.clone();
        let thread = std::thread::spawn(move || sent.send(waiter.wait(id)).unwrap());
        requests.cancel();
        assert_eq!(
            received.recv_timeout(Duration::from_secs(2)).unwrap(),
            Err(Error::Cancelled)
        );
        thread.join().unwrap();
        assert!(requests.current().is_none());
    }
}
