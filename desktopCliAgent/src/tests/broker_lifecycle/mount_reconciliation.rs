use super::*;
use tokio::sync::{mpsc, oneshot};

/// Hold snapshot replies to force overlapping immediate refreshes and long polls.
pub(super) struct SnapshotRequest {
    pub request: Op,
    pub reply: oneshot::Sender<Reply>,
}

impl SnapshotRequest {
    fn kind(&self) -> &'static str {
        match self.request {
            Op::Mounts(_) => "mounts",
            Op::WatchMounts(_) => "watchMounts",
            _ => "other",
        }
    }

    /// The revision a long poll waits to change from.
    fn revision(&self) -> &str {
        match &self.request {
            Op::WatchMounts(watch) => &watch.revision,
            _ => panic!("not a long poll"),
        }
    }

    fn respond(self, revision: &str, mounts: Vec<protocol::Mount>) {
        let response = protocol::MountsResponse {
            revision: revision.into(),
            mounts,
        };
        self.reply.send(Reply::Mounts(response)).unwrap();
    }
}

struct Fixture {
    app: App,
    broker: Child,
    snapshots: mpsc::UnboundedReceiver<SnapshotRequest>,
}

impl Fixture {
    async fn new() -> Self {
        let (requests, mut snapshots) = mpsc::unbounded_channel();
        let app = App::with_snapshots(Some(requests)).await;
        let (broker, ()) = tokio::join!(app.launch(), async {
            let initial = timeout(snapshots.recv()).await.unwrap();
            assert_eq!(initial.kind(), "mounts");
            initial.respond("initial", vec![]);
        });
        Self {
            app,
            broker,
            snapshots,
        }
    }

    async fn next(&mut self, kind: &str) -> SnapshotRequest {
        // The first public request also validates this test executable's code
        // signature on macOS, which can be slow immediately after a rebuild.
        let request = tokio::time::timeout(Duration::from_secs(60), self.snapshots.recv())
            .await
            .unwrap_or_else(|_| panic!("timed out waiting for {kind}"))
            .unwrap();
        assert_eq!(request.kind(), kind);
        request
    }

    fn mount(&self, id: &str, path: &std::path::Path) -> protocol::Mount {
        protocol::Mount {
            id: id.into(),
            path: path.to_string_lossy().into_owned(),
            generation: id.into(),
            bookmark: None,
        }
    }

    async fn create(&self) -> UnixStream {
        let mut stream = UnixStream::connect(&self.app.public).await.unwrap();
        write_json(
            &mut stream,
            json!({
                "type":"manage", "tool":"create_local_env_file",
                "arguments":{"environment_id":"env","path":self.app.fifo},
            }),
        )
        .await;
        stream
    }

    async fn stop(mut self) {
        drop(self.broker.stdin.take());
        assert!(timeout(self.broker.wait()).await.unwrap().success());
    }
}

#[tokio::test]
async fn fifo_refresh_cannot_apply_an_old_snapshot_after_a_newer_watch() {
    let mut fixture = Fixture::new().await;
    let watch = fixture.next("watchMounts").await;
    let mut client = fixture.create().await;
    let refresh = fixture.next("mounts").await;
    let replacement = fixture.app.directory.path().join("replacement.env");
    watch.respond("latest", vec![fixture.mount("replacement", &replacement)]);

    // The immediate refresh owns reconciliation from before its snapshot is
    // requested. The watch cannot apply its reply and start another poll yet.
    assert!(
        tokio::time::timeout(Duration::from_millis(500), fixture.snapshots.recv())
            .await
            .is_err(),
        "the watch applied a newer snapshot while an older refresh was still in flight",
    );
    refresh.respond("created", vec![fixture.mount("mount", &fixture.app.fifo)]);
    assert_eq!(
        timeout(read_json(&mut client)).await["result"]["status"],
        "ready"
    );

    // The overlapping watch's ordering is unknown. Retry from the revision
    // actually applied by the refresh, rather than trusting the delayed reply.
    let watch = fixture.next("watchMounts").await;
    assert_eq!(watch.revision(), "created");
    watch.respond("latest", vec![fixture.mount("replacement", &replacement)]);
    let pending = fixture.next("watchMounts").await;
    assert_eq!(pending.revision(), "latest");
    assert!(!fixture.app.fifo.exists(), "disabled mount was recreated");
    assert!(replacement.exists(), "enabled mount was removed");
    fixture.stop().await;
}

#[tokio::test]
async fn fifo_watch_cannot_roll_back_a_completed_refresh() {
    let mut fixture = Fixture::new().await;
    let watch = fixture.next("watchMounts").await;
    let mut client = fixture.create().await;
    fixture
        .next("mounts")
        .await
        .respond("created", vec![fixture.mount("mount", &fixture.app.fifo)]);
    assert_eq!(
        timeout(read_json(&mut client)).await["result"]["status"],
        "ready"
    );

    // A long poll captured the initial empty state before creation, but its
    // reply reaches the broker only after the creation refresh has completed.
    watch.respond("initial", vec![]);
    let pending = fixture.next("watchMounts").await;
    assert_eq!(pending.revision(), "created");
    assert!(
        fixture.app.fifo.exists(),
        "the stale watch removed the new mount"
    );
    fixture.stop().await;
}

#[tokio::test]
async fn fifo_rejected_snapshot_keeps_the_applied_revision() {
    let mut fixture = Fixture::new().await;
    let watch = fixture.next("watchMounts").await;
    let mount = fixture.mount("mount", &fixture.app.fifo);
    watch.respond("rejected", vec![mount.clone(); 257]);

    let retry = fixture.next("watchMounts").await;
    assert_eq!(retry.revision(), "initial");
    assert!(!fixture.app.fifo.exists());
    retry.respond("accepted", vec![mount]);
    let pending = fixture.next("watchMounts").await;
    assert_eq!(pending.revision(), "accepted");
    assert!(fixture.app.fifo.exists());
    fixture.stop().await;
}

#[tokio::test]
async fn a_restart_removes_the_fifo_of_a_mount_removed_while_the_agent_was_down() {
    let (requests, mut snapshots) = mpsc::unbounded_channel();
    let app = App::with_snapshots(Some(requests)).await;
    let mount = |id: &str| protocol::Mount {
        id: id.into(),
        path: app.fifo.to_string_lossy().into_owned(),
        generation: id.into(),
        bookmark: None,
    };
    let (mut first, ()) = tokio::join!(app.launch(), async {
        let initial = timeout(snapshots.recv()).await.unwrap();
        assert_eq!(initial.kind(), "mounts");
        initial.respond("old", vec![mount("old")]);
    });
    first.kill().await.unwrap();
    assert!(
        std::fs::symlink_metadata(&app.fifo)
            .unwrap()
            .file_type()
            .is_fifo()
    );

    // Meanwhile, the app removed the mount and added another at its path.
    let (mut second, ()) = tokio::join!(app.launch(), async {
        // Skip the long poll of the killed agent.
        let initial = loop {
            let request = timeout(snapshots.recv()).await.unwrap();
            if request.kind() == "mounts" {
                break request;
            }
        };
        initial.respond("new", vec![mount("new")]);
    });
    let statuses: Vec<_> = app
        .statuses
        .lock()
        .unwrap()
        .iter()
        .filter(|status| status.mount_id == "new")
        .map(|status| status.status)
        .collect();
    assert_eq!(statuses, [protocol::MountStatus::Ready as i32]);
    drop(second.stdin.take());
    assert!(timeout(second.wait()).await.unwrap().success());
    assert!(std::fs::symlink_metadata(&app.fifo).is_err());
}
