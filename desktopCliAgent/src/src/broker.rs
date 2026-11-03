//! The agent's lifetime: started by the app, it serves the public socket and
//! the local `.env` files until the app closes its standard input.
use crate::{
    app_client::AppClient,
    mounts::{Mounts, snapshot, watch_mounts},
    public, transport,
};
use anyhow::{Context, Result, ensure};
use keyguard_agent_ipc::launch::{self, Launch};
use keyguard_cli_protocol::{
    errors::failure,
    frame::{read_frame, write_frame},
};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::{
    sync::{Mutex, OwnedSemaphorePermit, Semaphore},
    task::JoinSet,
};

/// Options passed exclusively by the app's helper lifecycle.
pub struct Options {
    pub ipc_socket: PathBuf,
    pub parent_pid: u32,
    pub public_socket: PathBuf,
    pub state_dir: PathBuf,
}

/// Run until the owning app closes stdin or a termination signal arrives.
pub async fn run(options: Options) -> Result<()> {
    ensure!(options.parent_pid > 0, "invalid_parent_pid");
    let mut termination = Termination::register()?;
    let Launch {
        token,
        parent_gone: mut death_rx,
    } = launch::read_launch()
        .await
        .context("invalid_launch_token")?;
    #[cfg(unix)]
    {
        let parent = options
            .public_socket
            .parent()
            .context("invalid_socket_path")?;
        crate::fifo::private_directory(parent)?;
        crate::fifo::private_directory(&options.state_dir)?;
    }
    // The socket's lock admits one agent. Take it before the mounts, so that
    // an agent that loses leaves the FIFOs and statuses of the running one alone.
    let mut listener =
        transport::Listener::bind(&options.public_socket, &options.state_dir).await?;
    let app = AppClient {
        socket: options.ipc_socket,
        pid: options.parent_pid,
        token,
    };
    let initial = snapshot(&app, None).await?;
    let mounts = Arc::new(Mutex::new(Mounts::new(
        app.clone(),
        options.state_dir.clone(),
    )));
    mounts.lock().await.apply_snapshot(initial).await?;
    if let Err(error) = launch::write_ready_record() {
        // `main` exits without dropping the mounts, so remove their FIFOs here.
        #[cfg(unix)]
        mounts.lock().await.reconcile(Vec::new()).await?;
        return Err(error.into());
    }
    let mut tasks = JoinSet::new();
    // Each idle MCP client holds a connection.
    let limit = Arc::new(Semaphore::new(64));
    let refusals = Arc::new(Semaphore::new(4));
    let mut watcher = tokio::spawn(watch_mounts(app.clone(), mounts.clone()));
    let result = loop {
        tokio::select! {
            _ = &mut death_rx => break Ok(()),
            _ = termination.recv() => break Ok(()),
            _ = &mut watcher => break Err(anyhow::anyhow!("mount_watcher_failed")),
            incoming = listener.accept() => {
                let Ok(incoming) = incoming else {
                    // A lasting failure, such as running out of descriptors, must not spin.
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                };
                let Ok(permit) = limit.clone().try_acquire_owned() else {
                    if let Ok(permit) = refusals.clone().try_acquire_owned() {
                        tasks.spawn(refuse(incoming, permit));
                    }
                    continue;
                };
                let app = app.clone();
                let mounts = mounts.clone();
                tasks.spawn(async move {
                    let _permit = permit;
                    if let Ok(accepted) = incoming.identify().await {
                        let _ = public::serve(accepted, app, mounts).await;
                    }
                });
            }
            _ = tasks.join_next(), if !tasks.is_empty() => {},
        }
    };
    if !watcher.is_finished() {
        watcher.abort();
        let _ = watcher.await;
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    #[cfg(unix)]
    mounts.lock().await.reconcile(Vec::new()).await?;
    result
}

/// Answers a connection over the limit after reading its request, so that
/// the client can read the answer.
async fn refuse(incoming: transport::Incoming, _permit: OwnedSemaphorePermit) {
    let mut stream = incoming.into_stream();
    let _ = tokio::time::timeout(Duration::from_secs(5), async {
        read_frame(&mut *stream).await?;
        let response = failure("too_many_connections");
        write_frame(&mut *stream, &serde_json::to_vec(&response)?).await
    })
    .await;
}

/// Termination signals, registered once so that none is missed between waits.
struct Termination {
    #[cfg(unix)]
    terminate: tokio::signal::unix::Signal,
    #[cfg(unix)]
    interrupt: tokio::signal::unix::Signal,
    #[cfg(windows)]
    interrupt: tokio::signal::windows::CtrlC,
}

impl Termination {
    fn register() -> Result<Self> {
        #[cfg(unix)]
        {
            use tokio::signal::unix::{SignalKind, signal};
            Ok(Self {
                terminate: signal(SignalKind::terminate())?,
                interrupt: signal(SignalKind::interrupt())?,
            })
        }
        #[cfg(windows)]
        Ok(Self {
            interrupt: tokio::signal::windows::ctrl_c()?,
        })
    }

    async fn recv(&mut self) {
        #[cfg(unix)]
        tokio::select! {
            _ = self.terminate.recv() => {},
            _ = self.interrupt.recv() => {},
        }
        #[cfg(windows)]
        self.interrupt.recv().await;
    }
}
