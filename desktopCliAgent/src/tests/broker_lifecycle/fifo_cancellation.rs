use super::*;

#[tokio::test]
async fn closing_the_last_fifo_reader_cancels_pending_approval() {
    let app = App::new().await;
    app.mode.store(1, Ordering::SeqCst);
    let mut child = app.launch().await;
    let reader = open_reader(&app.fifo);
    timeout(app.pending.notified()).await;
    assert_eq!(app.resolves.load(Ordering::SeqCst), 1);

    // The app never sends a response. Closing the reader must close the
    // private request instead of leaving its approval alive until timeout.
    drop(reader);
    tokio::time::timeout(Duration::from_secs(2), app.canceled.notified())
        .await
        .expect("pending FIFO approval was not canceled");

    app.mode.store(0, Ordering::SeqCst);
    assert_eq!(
        timeout(tokio::fs::read_to_string(&app.fifo)).await.unwrap(),
        CONTENT
    );
    assert_eq!(app.resolves.load(Ordering::SeqCst), 2);
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn pending_fifo_approval_stays_open_until_all_readers_close() {
    let app = App::new().await;
    app.mode.store(1, Ordering::SeqCst);
    let mut child = app.launch().await;
    let first = open_reader(&app.fifo);
    // Retain two read handles to the same accepted FIFO even if the broker
    // publishes the next reader session between these operations.
    let second = first.try_clone().unwrap();
    timeout(app.pending.notified()).await;

    drop(first);
    assert!(
        tokio::time::timeout(Duration::from_millis(500), app.canceled.notified())
            .await
            .is_err(),
        "approval was canceled while another FIFO reader remained"
    );
    assert_eq!(app.resolves.load(Ordering::SeqCst), 1);

    drop(second);
    tokio::time::timeout(Duration::from_secs(2), app.canceled.notified())
        .await
        .expect("approval was not canceled after the last reader closed");
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}

#[tokio::test]
async fn a_new_fifo_reader_cannot_keep_an_old_approval_alive() {
    let app = App::new().await;
    app.mode.store(1, Ordering::SeqCst);
    let mut child = app.launch().await;
    let original = open_reader(&app.fifo);
    timeout(app.pending.notified()).await;

    // This reader opens while the first approval is still pending. It must
    // belong to a new session, so it cannot retain the old private request.
    let mut next = open_reader(&app.fifo);
    app.mode.store(0, Ordering::SeqCst);
    drop(original);
    tokio::time::timeout(Duration::from_secs(2), app.canceled.notified())
        .await
        .expect("a new FIFO reader retained the old approval");

    // Wait for the new session's writer before interpreting a nonblocking
    // read as EOF. The old request never receives an approval response.
    timeout(async {
        while app.resolves.load(Ordering::SeqCst) < 2 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await;
    assert_eq!(timeout(read_to_eof(&mut next)).await, CONTENT);
    assert_eq!(app.resolves.load(Ordering::SeqCst), 2);
    drop(child.stdin.take());
    assert!(timeout(child.wait()).await.unwrap().success());
}
