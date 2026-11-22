//! The sockets for connectors in the sandboxes of Flatpak browsers. Such a
//! sandbox sees only the browser's own folder of the user's files, so
//! `keyguard browsers install` puts a copy of the connector next to the
//! browser's manifest, and the agent listens next to that copy. These
//! sockets serve only browser streams. A development build of Keyguard has
//! its own names for the copy and the socket, see `browser::Channel`.
//!
//! The browser can change anything in its own folder, so the agent never
//! follows a path there: it opens the folder from the user's home one
//! folder at a time without following links, and does everything else
//! through that descriptor.
use crate::transport::Incoming;
use std::path::Path;
use tokio::sync::mpsc;

/// The agent's sockets in the folders of Flatpak browsers. A task of their
/// own looks for the folders and binds the sockets, so that a slow folder
/// never holds up the agent's own socket, and passes on the connections.
pub(crate) struct FlatpakBrowsers {
    incoming: mpsc::Receiver<Incoming>,
    task: Option<tokio::task::JoinHandle<()>>,
}

impl FlatpakBrowsers {
    /// Starts listening in the folders of the Flatpak browsers in the user's
    /// home. `state_dir` holds the agent's lifecycle locks. Only Linux has
    /// these browsers.
    pub(crate) fn start(state_dir: &Path) -> Self {
        let (sender, incoming) = mpsc::channel(8);
        #[cfg(target_os = "linux")]
        let task = linux::Sockets::discover(state_dir)
            .map(|sockets| tokio::spawn(linux::serve(sockets, sender)));
        #[cfg(not(target_os = "linux"))]
        let task = {
            let _ = (state_dir, sender);
            None
        };
        Self { incoming, task }
    }

    /// The next connection on any of the sockets. Without sockets, it waits
    /// until dropped.
    pub(crate) async fn accept(&mut self) -> Incoming {
        match self.incoming.recv().await {
            Some(incoming) => incoming,
            None => std::future::pending().await,
        }
    }

    /// Stops listening, and removes the socket files.
    pub(crate) async fn stop(mut self) {
        if let Some(task) = self.task.take() {
            task.abort();
            let _ = task.await;
        }
    }
}

impl Drop for FlatpakBrowsers {
    fn drop(&mut self) {
        if let Some(task) = &self.task {
            task.abort();
        }
    }
}

#[cfg(target_os = "linux")]
mod linux {
    use crate::transport::{Incoming, Listener};
    use anyhow::Result;
    use keyguard_agent_identity::{
        linux_identity::{LinuxExecutableMetadata, executable_metadata_from_fd},
        socket_lifecycle::{EntryIdentity, current_euid, entry_identity, fd_path},
    };
    use keyguard_cli_protocol::{
        browser::{Channel, FlatpakBrowser, HOST_EXECUTABLE, flatpak},
        executable::Contents,
    };
    use std::{
        collections::HashMap,
        ffi::{CString, OsStr},
        fs::{File, OpenOptions},
        io,
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::{
                ffi::OsStrExt,
                fs::{MetadataExt, OpenOptionsExt},
            },
        },
        path::{Path, PathBuf},
        task::Poll,
        time::{Duration, Instant},
    };
    use tokio::sync::mpsc;

    /// How often the agent looks for copies of the connector:
    /// `keyguard browsers` adds and removes them while the agent runs. A few
    /// file checks each time; the connector waits about as long.
    const RESCAN: Duration = Duration::from_secs(2);

    /// How long the agent waits before it tries a folder where it could not
    /// bind a socket again, if nothing changed there: an agent that was
    /// killed leaves its socket file as it was.
    const RETRY: Duration = Duration::from_secs(30);

    /// Where Flatpak keeps the folders of its apps in the home, as
    /// [`FlatpakBrowser::folder`] has it.
    const APPS: [&str; 2] = [".var", "app"];

    /// Rescans every [`RESCAN`], and passes on each connection.
    pub(super) async fn serve(mut sockets: Sockets, sender: mpsc::Sender<Incoming>) {
        // The first tick is at once.
        let mut rescan = tokio::time::interval(RESCAN);
        rescan.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                _ = rescan.tick() => sockets.rescan().await,
                incoming = sockets.accept() => match incoming {
                    Ok(incoming) => {
                        if sender.send(incoming).await.is_err() {
                            return;
                        }
                    }
                    // A lasting failure, such as running out of descriptors,
                    // must not spin.
                    Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
                },
            }
        }
    }

    pub(super) struct Sockets {
        home: PathBuf,
        state_dir: PathBuf,
        /// The connector bundled with this agent. Only a copy of it gets a
        /// socket: another build of Keyguard would refuse this agent's app.
        bundled_host: PathBuf,
        /// The build's channel, which names the copies to look for and the
        /// sockets. A development build's connector can be the same bytes
        /// as the release one, so only the names keep their agents apart.
        channel: Channel,
        bound: Vec<Bound>,
        /// The folders where a socket could not be bound, such as one where
        /// another agent listens, with the folder and its socket file then,
        /// and when. The agent tries again once either changes, as when an
        /// agent that exits removes its socket, or after [`RETRY`].
        failed: HashMap<&'static str, (Attempt, Instant)>,
        copies: Copies,
    }

    /// A folder, and the socket file in it, as a bind found them.
    type Attempt = (EntryIdentity, Option<EntryIdentity>);

    /// A socket in one browser's folder.
    struct Bound {
        app_id: &'static str,
        listener: Listener,
        // After `listener`, which removes its socket file through it.
        folder: Folder,
    }

    /// A browser's folder that holds a copy of this agent's connector.
    struct Found {
        app_id: &'static str,
        folder: Folder,
        /// The socket file in the folder, if any.
        socket: Option<EntryIdentity>,
    }

    impl Sockets {
        pub(super) fn discover(state_dir: &Path) -> Option<Self> {
            let home = dirs::home_dir().filter(|home| home.is_absolute())?;
            let agent = std::env::current_exe().ok()?;
            let bundle = agent.parent()?;
            let bundled_host = bundle.join(HOST_EXECUTABLE);
            let channel = Channel::of_build(bundle);
            Some(Self::new(home, state_dir.to_owned(), bundled_host, channel))
        }

        fn new(home: PathBuf, state_dir: PathBuf, bundled_host: PathBuf, channel: Channel) -> Self {
            Self {
                home,
                state_dir,
                bundled_host,
                channel,
                bound: Vec::new(),
                failed: HashMap::new(),
                copies: Copies::default(),
            }
        }

        /// Listens next to each copy of this agent's connector, and stops
        /// listening where the copy, its folder or the socket file changed.
        pub(super) async fn rescan(&mut self) {
            self.rescan_at(Instant::now()).await;
        }

        /// [`Self::rescan`] at `now`.
        async fn rescan_at(&mut self, now: Instant) {
            let home = self.home.clone();
            let bundled_host = self.bundled_host.clone();
            let channel = self.channel;
            let mut copies = std::mem::take(&mut self.copies);
            let scan = tokio::task::spawn_blocking(move || {
                let found = scan(&home, &bundled_host, channel, &mut copies);
                (found, copies)
            });
            let Ok((found, copies)) = scan.await else {
                return;
            };
            self.copies = copies;
            // Dropping a listener removes its socket file, unless someone
            // removed or replaced it. A folder that was replaced, even by
            // one that has a copy, gets a socket of its own.
            self.bound.retain(|bound| {
                found.iter().any(|it| {
                    it.app_id == bound.app_id
                        && it.folder.identity == bound.folder.identity
                        && it.socket == Some(bound.listener.identity())
                })
            });
            self.failed
                .retain(|app_id, _| found.iter().any(|it| it.app_id == *app_id));
            for found in found {
                if self.bound.iter().any(|bound| bound.app_id == found.app_id) {
                    continue;
                }
                let attempt = (found.folder.identity, found.socket);
                if self.failed.get(found.app_id).is_some_and(|&(failed, at)| {
                    failed == attempt && now.duration_since(at) < RETRY
                }) {
                    continue;
                }
                // Another agent may listen there, such as one of another
                // build of Keyguard.
                let app_id = found.app_id;
                match bind(found, self.channel, &self.state_dir).await {
                    Ok(bound) => {
                        self.failed.remove(app_id);
                        self.bound.push(bound);
                    }
                    Err(_) => {
                        self.failed.insert(app_id, (attempt, now));
                    }
                }
            }
        }

        pub(super) async fn accept(&self) -> Result<Incoming> {
            std::future::poll_fn(|context| {
                self.bound
                    .iter()
                    .map(|bound| bound.listener.poll_accept(context))
                    .find(Poll::is_ready)
                    .unwrap_or(Poll::Pending)
            })
            .await
        }

        #[cfg(test)]
        fn app_ids(&self) -> Vec<&str> {
            self.bound.iter().map(|bound| bound.app_id).collect()
        }
    }

    /// The browsers' folders that hold a copy of the bundled connector under
    /// the name of `channel`.
    fn scan(home: &Path, bundled_host: &Path, channel: Channel, copies: &mut Copies) -> Vec<Found> {
        let Ok(bundled) = File::open(bundled_host) else {
            return Vec::new();
        };
        let (Some(bundled_stamp), Ok(apps)) = (stamp(&bundled), open_apps(home)) else {
            return Vec::new();
        };
        flatpak::ALL
            .into_iter()
            .filter_map(|browser| {
                let folder = Folder::open(&apps, browser).ok()?;
                let copy =
                    open_at(&folder.file, channel.host_copy().as_ref(), libc::O_RDONLY).ok()?;
                copies
                    .matches(browser.app_id, &copy, &bundled, bundled_stamp)
                    .then(|| Found {
                        app_id: browser.app_id,
                        socket: folder.socket(channel),
                        folder,
                    })
            })
            .collect()
    }

    /// Binds an owner-only socket in the folder, as the agent's own socket,
    /// through the folder's descriptor.
    async fn bind(found: Found, channel: Channel, state_dir: &Path) -> Result<Bound> {
        let path = fd_path(&found.folder.file).join(channel.host_socket());
        let listener = Listener::bind(&path, state_dir).await?;
        Ok(Bound {
            app_id: found.app_id,
            listener,
            folder: found.folder,
        })
    }

    /// Opens the folder of Flatpak's apps in the home.
    fn open_apps(home: &Path) -> io::Result<File> {
        // The home is the user's: links on the way to it, such as /home
        // to /var/home, are followed.
        let home = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NONBLOCK)
            .open(home)?;
        APPS.iter()
            .try_fold(home, |folder, name| open_folder(&folder, name.as_ref()))
    }

    /// A browser's folder of manifests, opened from the folder of Flatpak's
    /// apps one folder at a time without following links.
    struct Folder {
        file: File,
        identity: EntryIdentity,
    }

    impl Folder {
        fn open(apps: &File, browser: FlatpakBrowser) -> io::Result<Self> {
            let mut folder = open_folder(apps, browser.app_id.as_ref())?;
            for name in browser.manifests {
                folder = open_folder(&folder, name.as_ref())?;
            }
            let identity = entry_identity(&folder.metadata()?);
            Ok(Self {
                file: folder,
                identity,
            })
        }

        /// The socket file of `channel`, if any.
        fn socket(&self, channel: Channel) -> Option<EntryIdentity> {
            let stat = stat_at(&self.file, channel.host_socket().as_ref())?;
            (stat.st_mode & libc::S_IFMT == libc::S_IFSOCK).then_some(EntryIdentity {
                device: stat.st_dev,
                inode: stat.st_ino,
            })
        }
    }

    /// Opens the folder `name` in `folder`, but not a link to one. It must be
    /// the user's, or root's, as the folders that Flatpak makes in a sandbox
    /// may be.
    fn open_folder(folder: &File, name: &OsStr) -> io::Result<File> {
        let folder = open_at(folder, name, libc::O_RDONLY | libc::O_DIRECTORY)?;
        let owner = folder.metadata()?.uid();
        if owner != current_euid() && owner != 0 {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "the folder belongs to another user",
            ));
        }
        Ok(folder)
    }

    /// Opens `name` in `folder` with `flags`, but not a link, nor a FIFO's
    /// writer.
    fn open_at(folder: &File, name: &OsStr, flags: libc::c_int) -> io::Result<File> {
        let name = CString::new(name.as_bytes())
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
        let flags = flags | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC;
        // SAFETY: the folder's descriptor is open and the name is a
        // NUL-terminated string, both for the whole call.
        let fd = unsafe { libc::openat(folder.as_raw_fd(), name.as_ptr(), flags) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // SAFETY: `openat` returned a new descriptor that nothing else owns.
        Ok(unsafe { File::from_raw_fd(fd) })
    }

    /// The entry `name` in `folder` itself, even a link; `None` if missing.
    fn stat_at(folder: &File, name: &OsStr) -> Option<libc::stat> {
        let name = CString::new(name.as_bytes()).ok()?;
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        // SAFETY: the folder's descriptor is open, the name is a
        // NUL-terminated string, and `stat` is writable for a whole
        // `struct stat`.
        let result = unsafe {
            libc::fstatat(
                folder.as_raw_fd(),
                name.as_ptr(),
                stat.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        };
        // SAFETY: a successful fstatat filled in `stat`.
        (result == 0).then(|| unsafe { stat.assume_init() })
    }

    /// A regular file as it is now. Writing to it changes its change time,
    /// which no one can set.
    type Stamp = LinuxExecutableMetadata;

    fn stamp(file: &File) -> Option<Stamp> {
        executable_metadata_from_fd(file).ok()
    }

    /// Whether each browser's copy matches the bundled connector. A copy is
    /// compared again only once it, or the bundled connector, changes, and
    /// the bundled connector is hashed again only once it changes.
    #[derive(Default)]
    struct Copies {
        known: HashMap<&'static str, ([Stamp; 2], bool)>,
        bundled: Option<(Stamp, Option<Contents>)>,
        /// How many copies were compared.
        #[cfg(test)]
        compared: usize,
    }

    impl Copies {
        fn matches(
            &mut self,
            app_id: &'static str,
            copy: &File,
            bundled: &File,
            bundled_stamp: Stamp,
        ) -> bool {
            let Some(copy_stamp) = stamp(copy) else {
                return false;
            };
            let stamps = [copy_stamp, bundled_stamp];
            if let Some((known, matches)) = self.known.get(app_id)
                && *known == stamps
            {
                return *matches;
            }
            let same_file = (copy_stamp.device, copy_stamp.inode)
                == (bundled_stamp.device, bundled_stamp.inode);
            let matches = copy_stamp.size == bundled_stamp.size
                && (same_file
                    || self
                        .bundled_contents(bundled, bundled_stamp)
                        .is_some_and(|contents| contents.matches(copy)));
            #[cfg(test)]
            {
                self.compared += 1;
            }
            self.known.insert(app_id, (stamps, matches));
            matches
        }

        /// What the bundled connector holds, hashed once for each `stamp`.
        fn bundled_contents(&mut self, bundled: &File, stamp: Stamp) -> Option<Contents> {
            if let Some((known, contents)) = self.bundled
                && known == stamp
            {
                return contents;
            }
            let contents = Contents::of_file(bundled, stamp.size);
            self.bundled = Some((stamp, contents));
            contents
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::os::unix::fs::{FileTypeExt, PermissionsExt};

        const CONNECTOR: &[u8] = b"connector";

        /// The release channel's copy, and its socket.
        const COPY: &str = Channel::Production.host_copy();
        const HOST_SOCKET: &str = Channel::Production.host_socket();

        /// The agent's sockets in a fresh home, with a private state
        /// directory as the agent has, and a bundled connector.
        fn sockets(home: &str) -> (tempfile::TempDir, Sockets) {
            let temp = tempfile::tempdir().unwrap();
            let state = temp.path().join("state");
            std::fs::create_dir(&state).unwrap();
            std::fs::set_permissions(&state, std::fs::Permissions::from_mode(0o700)).unwrap();
            let home = temp.path().join(home);
            std::fs::create_dir(&home).unwrap();
            let bundled_host = temp.path().join(HOST_EXECUTABLE);
            std::fs::write(&bundled_host, CONNECTOR).unwrap();
            let sockets = Sockets::new(home, state, bundled_host, Channel::Production);
            (temp, sockets)
        }

        /// Leaves a copy of the connector in `folder`, as `install` does.
        fn install(folder: &Path) {
            install_contents(folder, CONNECTOR);
        }

        fn install_contents(folder: &Path, contents: &[u8]) {
            std::fs::create_dir_all(folder).unwrap();
            std::fs::write(folder.join(COPY), contents).unwrap();
        }

        fn is_socket(path: &Path) -> bool {
            std::fs::symlink_metadata(path).is_ok_and(|metadata| metadata.file_type().is_socket())
        }

        async fn connects(sockets: &Sockets, socket: &Path) -> bool {
            let (accepted, connected) = tokio::join!(
                sockets.accept(),
                keyguard_cli_protocol::transport::connect(socket, None)
            );
            accepted.is_ok() && connected.is_ok()
        }

        const CHROME: &str = "com.google.Chrome";

        #[tokio::test]
        async fn sockets_follow_the_copies_of_the_connector() {
            let (_temp, mut sockets) = sockets("home");
            let chrome = flatpak::CHROME.manifests(&sockets.home);
            let firefox = flatpak::FIREFOX.manifests(&sockets.home);
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());

            // Only a folder with a copy gets a socket.
            std::fs::create_dir_all(&firefox).unwrap();
            install(&chrome);
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), [CHROME]);
            let socket = chrome.join(HOST_SOCKET);
            assert!(is_socket(&socket));
            assert_eq!(std::fs::metadata(&socket).unwrap().mode() & 0o777, 0o600);
            assert!(!firefox.join(HOST_SOCKET).exists());
            assert!(connects(&sockets, &socket).await);
            let inode = std::fs::metadata(&socket).unwrap().ino();
            sockets.rescan().await;
            assert_eq!(std::fs::metadata(&socket).unwrap().ino(), inode);

            // `uninstall` removes the copy.
            std::fs::remove_file(chrome.join(COPY)).unwrap();
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());
            assert!(!socket.exists());

            // A socket file that someone removed is bound again.
            install(&chrome);
            sockets.rescan().await;
            std::fs::remove_file(&socket).unwrap();
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), [CHROME]);
            assert!(connects(&sockets, &socket).await);

            // The agent removes its sockets when it stops.
            drop(sockets);
            assert!(!socket.exists());
        }

        #[tokio::test]
        async fn a_link_is_not_a_copy() {
            let (temp, mut sockets) = sockets("home");
            let edge = flatpak::EDGE.manifests(&sockets.home);
            std::fs::create_dir_all(&edge).unwrap();
            let elsewhere = temp.path().join("elsewhere");
            std::fs::write(&elsewhere, CONNECTOR).unwrap();
            std::os::unix::fs::symlink(&elsewhere, edge.join(COPY)).unwrap();
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());
        }

        #[tokio::test]
        async fn links_to_folders_are_never_followed() {
            let (temp, mut sockets) = sockets("home");
            // The browser links a folder in its own folder to a folder of
            // the user's that holds a copy.
            let elsewhere = temp.path().join("elsewhere");
            install(&elsewhere.join("google-chrome/NativeMessagingHosts"));
            let folder = flatpak::CHROME.folder(&sockets.home);
            std::fs::create_dir_all(&folder).unwrap();
            std::os::unix::fs::symlink(&elsewhere, folder.join("config")).unwrap();
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());
            assert!(
                !elsewhere
                    .join("google-chrome/NativeMessagingHosts")
                    .join(HOST_SOCKET)
                    .exists()
            );
        }

        #[tokio::test]
        async fn sockets_deep_in_a_long_home_are_bound_through_their_folder() {
            let (_temp, mut sockets) = sockets(&"h".repeat(40));
            let brave = flatpak::BRAVE.manifests(&sockets.home);
            let socket = brave.join(HOST_SOCKET);
            assert!(socket.as_os_str().len() > 107);
            install(&brave);
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), ["com.brave.Browser"]);
            assert!(is_socket(&socket));
            assert!(connects(&sockets, &socket).await);
            drop(sockets);
            assert!(!socket.exists());
        }

        #[tokio::test]
        async fn a_replaced_folder_gets_a_socket_of_its_own() {
            let (_temp, mut sockets) = sockets("home");
            let chrome = flatpak::CHROME.manifests(&sockets.home);
            install(&chrome);
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), [CHROME]);

            // A restored profile: the old folder stays, renamed.
            let old = chrome.with_file_name("NativeMessagingHosts.old");
            std::fs::rename(&chrome, &old).unwrap();
            assert!(is_socket(&old.join(HOST_SOCKET)));
            install(&chrome);
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), [CHROME]);
            let socket = chrome.join(HOST_SOCKET);
            assert!(is_socket(&socket));
            assert!(connects(&sockets, &socket).await);
            // The old socket went with its listener.
            assert!(!old.join(HOST_SOCKET).exists());
        }

        #[tokio::test]
        async fn only_a_copy_of_this_agents_connector_gets_a_socket() {
            let (_temp, mut sockets) = sockets("home");
            let chrome = flatpak::CHROME.manifests(&sockets.home);
            let socket = chrome.join(HOST_SOCKET);
            // Another build's connector.
            install_contents(&chrome, b"another connector");
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());
            assert!(!socket.exists());

            install(&chrome);
            sockets.rescan().await;
            assert_eq!(sockets.app_ids(), [CHROME]);
            // An unchanged copy is not compared again.
            let compared = sockets.copies.compared;
            sockets.rescan().await;
            sockets.rescan().await;
            assert_eq!(sockets.copies.compared, compared);

            // Another build replaces the copy: its agent listens instead.
            install_contents(&chrome, b"another connector");
            sockets.rescan().await;
            assert!(sockets.app_ids().is_empty());
            assert!(!socket.exists());
        }

        /// Another agent's sockets in a home with Chrome's copy, and this
        /// agent's sockets, which can't bind there.
        async fn another_agent_listens() -> (tempfile::TempDir, Sockets, Sockets, PathBuf) {
            let (temp, mut first) = sockets("home");
            let chrome = flatpak::CHROME.manifests(&first.home);
            install(&chrome);
            first.rescan().await;
            assert_eq!(first.app_ids(), [CHROME]);
            let second = Sockets::new(
                first.home.clone(),
                first.state_dir.clone(),
                first.bundled_host.clone(),
                first.channel,
            );
            (temp, first, second, chrome.join(HOST_SOCKET))
        }

        #[tokio::test]
        async fn a_folder_where_another_agent_listens_is_tried_again_once_it_stops() {
            let (_temp, first, mut second, socket) = another_agent_listens().await;
            second.rescan().await;
            assert!(second.app_ids().is_empty());
            assert!(second.failed.contains_key(CHROME));

            // The other agent removes its socket as it stops.
            drop(first);
            second.rescan().await;
            assert_eq!(second.app_ids(), [CHROME]);
            assert!(second.failed.is_empty());
            assert!(connects(&second, &socket).await);
        }

        #[tokio::test]
        async fn a_socket_that_a_killed_agent_left_is_tried_again_after_a_while() {
            let (temp, first, mut second, socket) = another_agent_listens().await;
            let start = Instant::now();
            second.rescan_at(start).await;
            assert!(second.app_ids().is_empty());

            // The other agent is killed: its socket file stays as it was,
            // with no one listening.
            let aside = temp.path().join("aside.sock");
            std::fs::hard_link(&socket, &aside).unwrap();
            drop(first);
            std::fs::hard_link(&aside, &socket).unwrap();
            std::fs::remove_file(&aside).unwrap();
            second.rescan_at(start + RETRY / 2).await;
            assert!(second.app_ids().is_empty());

            second.rescan_at(start + RETRY).await;
            assert_eq!(second.app_ids(), [CHROME]);
            assert!(second.failed.is_empty());
            assert!(connects(&second, &socket).await);
        }

        #[tokio::test]
        async fn each_channel_listens_next_to_its_own_copies() {
            let (_temp, mut release) = sockets("home");
            let mut dev = Sockets::new(
                release.home.clone(),
                release.state_dir.clone(),
                release.bundled_host.clone(),
                Channel::Dev,
            );
            let chrome = flatpak::CHROME.manifests(&release.home);
            let firefox = flatpak::FIREFOX.manifests(&release.home);
            // Both builds bundle the same bytes.
            install(&chrome);
            std::fs::create_dir_all(&firefox).unwrap();
            std::fs::write(firefox.join(Channel::Dev.host_copy()), CONNECTOR).unwrap();
            release.rescan().await;
            dev.rescan().await;
            assert_eq!(release.app_ids(), [CHROME]);
            assert_eq!(dev.app_ids(), ["org.mozilla.firefox"]);
            assert!(is_socket(&chrome.join(HOST_SOCKET)));
            assert!(!chrome.join(Channel::Dev.host_socket()).exists());
            let dev_socket = firefox.join(Channel::Dev.host_socket());
            assert!(is_socket(&dev_socket));
            assert!(!firefox.join(HOST_SOCKET).exists());
            assert!(connects(&dev, &dev_socket).await);

            // A browser with both copies gets a socket of each.
            install(&firefox);
            release.rescan().await;
            dev.rescan().await;
            assert_eq!(release.app_ids(), [CHROME, "org.mozilla.firefox"]);
            assert_eq!(dev.app_ids(), ["org.mozilla.firefox"]);
            assert!(connects(&release, &firefox.join(HOST_SOCKET)).await);
            assert!(connects(&dev, &dev_socket).await);
        }
    }
}
