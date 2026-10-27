//! The local `.env` files: FIFOs that the agent serves with the values of
//! an environment, after approval in the app.
use crate::{
    app_client::AppClient,
    protocol::{self, MountStatus, MountsRequest, WatchMountsRequest, request, response},
};
#[cfg(unix)]
use anyhow::ensure;
use anyhow::{Result, bail};
#[cfg(unix)]
use keyguard_cli_protocol::bounds::MAX_CONTENT;
#[cfg(unix)]
use std::collections::BTreeMap;
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::Mutex;
#[cfg(unix)]
use tokio::task::JoinHandle;
#[cfg(unix)]
use zeroize::Zeroizing;

#[derive(Clone)]
pub(crate) struct MountConfig {
    id: String,
    #[cfg(unix)]
    path: PathBuf,
    #[cfg(unix)]
    generation: String,
    #[cfg(unix)]
    bookmark: Option<Zeroizing<Vec<u8>>>,
}

impl MountConfig {
    fn new(mount: &mut protocol::Mount) -> Self {
        Self {
            id: std::mem::take(&mut mount.id),
            #[cfg(unix)]
            path: PathBuf::from(std::mem::take(&mut mount.path)),
            #[cfg(unix)]
            generation: std::mem::take(&mut mount.generation),
            #[cfg(unix)]
            bookmark: mount.bookmark.take().map(Zeroizing::new),
        }
    }
}

pub(crate) struct Snapshot {
    revision: String,
    mounts: Vec<MountConfig>,
}

#[cfg(unix)]
struct RunningMount {
    config: MountConfig,
    fifo: Arc<crate::fifo::Fifo>,
    task: JoinHandle<()>,
}
#[cfg(unix)]
impl RunningMount {
    /// Stops serving, and closes the FIFO once the task has let go of it.
    async fn stop(mut self) {
        self.task.abort();
        let _ = (&mut self.task).await;
        // Closing the FIFO syncs its record.
        blocking(move || drop(self));
    }
}
#[cfg(unix)]
impl Drop for RunningMount {
    fn drop(&mut self) {
        self.task.abort();
    }
}

/// Runs file system work that may wait on the disk, such as syncing a FIFO
/// record, without stalling the other tasks. It runs in place rather than on
/// another thread, so a task that is stopped never leaves it running.
#[cfg(unix)]
fn blocking<T>(work: impl FnOnce() -> T) -> T {
    tokio::task::block_in_place(work)
}

/// The start of the schedule that every mount polls on.
#[cfg(unix)]
static POLL_EPOCH: std::sync::OnceLock<tokio::time::Instant> = std::sync::OnceLock::new();
#[cfg(unix)]
const POLL: Duration = Duration::from_millis(100);

/// Ticks on one schedule shared by every mount, so that their polls wake
/// the agent together.
#[cfg(unix)]
fn poll_interval() -> tokio::time::Interval {
    let epoch = *POLL_EPOCH.get_or_init(tokio::time::Instant::now);
    let ticks = epoch.elapsed().as_nanos() / POLL.as_nanos() + 1;
    let start = epoch + POLL * u32::try_from(ticks).unwrap_or(u32::MAX);
    let mut interval = tokio::time::interval_at(start, POLL);
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    interval
}

pub(crate) struct Mounts {
    app: AppClient,
    /// Opaque app revision of the last successfully reconciled snapshot.
    revision: String,
    #[cfg(unix)]
    state_dir: PathBuf,
    #[cfg(unix)]
    running: BTreeMap<String, RunningMount>,
}

impl Mounts {
    pub(crate) fn new(app: AppClient, state_dir: PathBuf) -> Self {
        #[cfg(windows)]
        let _ = state_dir;
        Self {
            app,
            revision: String::new(),
            #[cfg(unix)]
            state_dir,
            #[cfg(unix)]
            running: BTreeMap::new(),
        }
    }

    /// The caller holds the mount lock across both the immediate request and
    /// reconciliation, so another refresh cannot apply a newer snapshot first.
    pub(crate) async fn refresh(&mut self) -> Result<()> {
        let current = snapshot(&self.app, None).await?;
        self.apply_snapshot(current).await
    }

    pub(crate) async fn apply_snapshot(&mut self, snapshot: Snapshot) -> Result<()> {
        self.reconcile(snapshot.mounts).await?;
        self.revision = snapshot.revision;
        Ok(())
    }

    #[cfg(unix)]
    pub(crate) async fn reconcile(&mut self, configs: Vec<MountConfig>) -> Result<()> {
        ensure!(configs.len() <= 256, "too_many_mounts");
        let wanted: BTreeMap<_, _> = configs
            .into_iter()
            .map(|config| (config.id.clone(), config))
            .collect();
        let removed: Vec<_> = self
            .running
            .keys()
            .filter(|id| !wanted.contains_key(*id))
            .cloned()
            .collect();
        for id in removed {
            if let Some(running) = self.running.remove(&id) {
                running.stop().await;
            }
        }
        let keep: Vec<_> = wanted.keys().cloned().collect();
        let mut created = Vec::new();
        for (id, mut config) in wanted {
            if self.running.get(&id).is_some_and(|m| {
                m.config.path == config.path
                    && m.config.generation == config.generation
                    && !m.task.is_finished()
            }) {
                continue;
            }
            if let Some(previous) = self.running.remove(&id) {
                // A new generation needs a fresh FIFO inode. Reopening the old
                // inode before its reader observes EOF could attach a new writer
                // to a canceled read and keep that read alive across a vault lock.
                previous.stop().await;
            }
            // Only the FIFO needs the bookmark; do not keep copies of it.
            let grant = blocking(|| {
                config
                    .bookmark
                    .take()
                    .map(|bookmark| crate::fifo::open_grant(&config.path, &bookmark))
                    .transpose()
            });
            created.push((id, config, grant));
        }
        // A mount removed while the agent was not running left its FIFO, which
        // a new mount may meet at its path. The grants of the new mounts let
        // the sandboxed agent reach their directories.
        blocking(|| crate::fifo::sweep(&self.state_dir, keep.iter().map(String::as_str)));
        for (id, config, grant) in created {
            let fifo = blocking(|| {
                grant.and_then(|grant| {
                    crate::fifo::Fifo::create(&id, &config.path, &self.state_dir, grant)
                })
            })
            .map(Arc::new);
            match fifo {
                Ok(fifo) => {
                    let task =
                        tokio::spawn(serve_fifo(self.app.clone(), config.clone(), fifo.clone()));
                    self.running
                        .insert(id.clone(), RunningMount { config, fifo, task });
                    self.app.mount_status(&id, MountStatus::Ready).await;
                }
                Err(_) => self.app.mount_status(&id, MountStatus::Error).await,
            }
        }
        Ok(())
    }

    #[cfg(windows)]
    async fn reconcile(&mut self, configs: Vec<MountConfig>) -> Result<()> {
        for config in configs {
            self.app.mount_status(&config.id, MountStatus::Error).await;
        }
        Ok(())
    }

    pub(crate) fn is_ready(&self, id: &str) -> bool {
        #[cfg(unix)]
        return self.running.get(id).is_some_and(|mount| {
            !mount.task.is_finished() && blocking(|| mount.fifo.revalidate()).is_ok()
        });
        #[cfg(windows)]
        {
            let _ = id;
            false
        }
    }
}

#[cfg(unix)]
async fn serve_fifo(app: AppClient, config: MountConfig, fifo: Arc<crate::fifo::Fifo>) {
    // Between reads, only the full check notices a project directory that
    // moved, or that others may now write to.
    const CHECK: Duration = Duration::from_secs(5);
    let mut poll = poll_interval();
    let mut checked = tokio::time::Instant::now();
    loop {
        poll.tick().await;
        let writer = match fifo.probe() {
            Ok(Some(writer)) => writer,
            Ok(None) if checked.elapsed() < CHECK => continue,
            Ok(None) => {
                checked = tokio::time::Instant::now();
                match blocking(|| fifo.revalidate()) {
                    Ok(()) => continue,
                    Err(_) => break,
                }
            }
            Err(_) => break,
        };
        let Ok(writer) = blocking(|| fifo.accept(writer)) else {
            break;
        };
        deliver(&app, &config, &fifo, writer).await;
    }
    app.mount_status(&config.id, MountStatus::Error).await;
}

/// Writes the environment to the readers of `writer`. On any refusal, simply
/// close it and produce EOF; never write an error into an environment file.
#[cfg(unix)]
async fn deliver(
    app: &AppClient,
    config: &MountConfig,
    fifo: &crate::fifo::Fifo,
    writer: std::fs::File,
) {
    let request = request::Request::ResolveMount(protocol::ResolveMountRequest {
        mount_id: config.id.clone(),
        generation: config.generation.clone(),
    });
    let response = tokio::select! {
        biased;
        // Dropping the request closes its private IPC connection and cancels
        // the approval. A failed reader check also cancels approval.
        _ = crate::fifo::reader_closed(&writer) => return,
        response = app.request(request) => response,
    };
    let Ok(mut response) = response else {
        return;
    };
    // Approval may arrive between reader checks. Confirm the original reader
    // session still exists before accepting any content from the app.
    let response::Response::ResolveMount(resolved) = &mut response.0 else {
        return;
    };
    if !matches!(crate::fifo::reader_present(&writer), Ok(true))
        || resolved.generation != config.generation
    {
        return;
    }
    let content = Zeroizing::new(std::mem::take(&mut resolved.content));
    if content.len() > MAX_CONTENT || content.contains(&0) || fifo.revalidate().is_err() {
        return;
    }
    let mut delivery = crate::fifo::Delivery::new(writer, &content);
    // Only a reader that stops reading is cut off.
    let stall = Duration::from_secs(10);
    let mut deadline = tokio::time::Instant::now() + stall;
    while !delivery.is_complete()
        && tokio::time::Instant::now() < deadline
        && fifo.revalidate().is_ok()
    {
        match delivery.write() {
            Ok(()) => deadline = tokio::time::Instant::now() + stall,
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                tokio::time::sleep(Duration::from_millis(10)).await
            }
            Err(_) => break,
        }
    }
    // Dropping a complete delivery delimits one read with EOF.
    // A partial one is parked until its reader closes.
}

/// The mounts the app serves; with the revision the agent applied, once they
/// change from it.
pub(crate) async fn snapshot(app: &AppClient, watch: Option<&str>) -> Result<Snapshot> {
    let request = match watch {
        Some(revision) => request::Request::WatchMounts(WatchMountsRequest {
            revision: revision.to_owned(),
        }),
        None => request::Request::Mounts(MountsRequest {}),
    };
    let mut response = app.request(request).await?;
    let response::Response::Mounts(snapshot) = &mut response.0 else {
        bail!("mounts_unavailable");
    };
    Ok(Snapshot {
        revision: std::mem::take(&mut snapshot.revision),
        mounts: snapshot.mounts.iter_mut().map(MountConfig::new).collect(),
    })
}

/// Follows the app's mounts. A failed request, for example while the app
/// refuses connections over its limit, is retried and keeps the running mounts.
pub(crate) async fn watch_mounts(app: AppClient, mounts: Arc<Mutex<Mounts>>) {
    const RETRY: Duration = Duration::from_millis(250);
    let mut retry = RETRY;
    loop {
        let revision = mounts.lock().await.revision.clone();
        let result: Result<bool> = async {
            // Long polls must not hold the lock: creation can need an immediate
            // refresh while the app is still waiting for a revision change.
            let snapshot = snapshot(&app, Some(&revision)).await?;
            let unchanged = snapshot.revision == revision;
            let mut mounts = mounts.lock().await;
            if mounts.revision != revision {
                // An immediate refresh won the race. Revisions are opaque, so
                // we cannot order this response against the applied snapshot.
                // Discard it and poll again from the applied revision.
                return Ok(false);
            }
            mounts.apply_snapshot(snapshot).await?;
            Ok(unchanged)
        }
        .await;
        match result {
            Ok(unchanged) => {
                retry = RETRY;
                if unchanged {
                    tokio::time::sleep(RETRY).await;
                }
            }
            Err(_) => {
                tokio::time::sleep(retry).await;
                retry = (retry * 2).min(Duration::from_secs(5));
            }
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[tokio::test]
    async fn every_mount_polls_on_one_schedule() {
        let mut first = poll_interval();
        tokio::time::sleep(Duration::from_millis(30)).await;
        let mut second = poll_interval();
        let epoch = *POLL_EPOCH.get().unwrap();
        for tick in [first.tick().await, second.tick().await] {
            assert_eq!((tick - epoch).as_nanos() % POLL.as_nanos(), 0);
        }
    }
}
