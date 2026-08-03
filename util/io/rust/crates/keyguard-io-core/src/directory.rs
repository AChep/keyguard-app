//! Retained directory capabilities and strict relative destinations.
//!
//! An [`AtomicDirectory`] opens and retains a caller-selected absolute
//! directory, following links while selecting that trust boundary.
//! Transactions opened beneath it accept only [`RelativeDestination`] values
//! and reject links or mount crossings in every descendant parent component.

use std::{
    path::{Component, Path, PathBuf},
    sync::Arc,
};

use crate::{
    error::{FileSystemFailure, Operation, TxnError},
    fsops::FsOps,
};

/// A retained, caller-selected directory trust boundary.
///
/// Clones share the same native handle. Removing an FFI registry handle
/// therefore cannot invalidate transactions that already cloned the
/// capability.
pub struct AtomicDirectory<D> {
    directory: Arc<D>,
}

impl<D> Clone for AtomicDirectory<D> {
    fn clone(&self) -> Self {
        Self {
            directory: Arc::clone(&self.directory),
        }
    }
}

impl<D> AtomicDirectory<D> {
    /// Resolves and pins an existing absolute directory.
    ///
    /// Links in `path` are followed while selecting the caller-trusted root.
    /// The selected directory handle is retained for the capability's
    /// lifetime; its absolute ancestors are not opened individually.
    ///
    /// # Errors
    ///
    /// Returns an error when `path` is not a strict absolute path, contains
    /// dot or non-UTF-8 components, or cannot be opened as a directory.
    pub fn open<F>(fs: &F, path: &Path) -> Result<Self, TxnError>
    where
        F: FsOps<Dir = D>,
    {
        split_absolute_path(path)?;
        let directory = fs
            .open_root(path)
            .map_err(|error| TxnError::from_io_error(Operation::PrepareParent, &error))?;
        Ok(Self {
            directory: Arc::new(directory),
        })
    }

    pub(crate) fn dir(&self) -> Result<&D, TxnError> {
        Ok(&self.directory)
    }
}

/// A validated path to one file beneath an [`AtomicDirectory`].
///
/// The wire spelling uses `/` separators on every platform. Absolute paths,
/// empty components, dot components, Windows separators/prefixes, alternate
/// data-stream colons, and embedded NUL bytes are rejected before filesystem
/// access.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RelativeDestination {
    parent_components: Vec<String>,
    file_name: String,
}

impl RelativeDestination {
    /// Parses a strict portable relative file path.
    ///
    /// # Errors
    ///
    /// Returns an invalid-argument transaction error for an empty or absolute
    /// path, an empty/dot/dot-dot component, a trailing separator, `\`, `:`,
    /// or an embedded NUL byte.
    pub fn parse(value: &str) -> Result<Self, TxnError> {
        if value.is_empty()
            || value.starts_with('/')
            || value.starts_with('\\')
            || value.ends_with('/')
            || value.ends_with('\\')
            || value.contains('\\')
            || value.contains(':')
            || value.contains('\0')
        {
            return Err(invalid_relative_path());
        }
        let mut components = value.split('/').map(str::to_owned).collect::<Vec<_>>();
        if components
            .iter()
            .any(|component| component.is_empty() || component == "." || component == "..")
        {
            return Err(invalid_relative_path());
        }
        let file_name = components.pop().ok_or_else(invalid_relative_path)?;
        Ok(Self {
            parent_components: components,
            file_name,
        })
    }

    pub(crate) fn parent_components(&self) -> &[String] {
        &self.parent_components
    }

    pub(crate) fn file_name(&self) -> &str {
        &self.file_name
    }
}

pub(crate) fn split_absolute_path(path: &Path) -> Result<(PathBuf, Vec<String>), TxnError> {
    if !path.is_absolute() {
        return Err(invalid_absolute_path());
    }

    let mut root = PathBuf::new();
    let mut names = Vec::new();
    let mut saw_root = false;
    for component in path.components() {
        match component {
            Component::Prefix(prefix) => root.push(prefix.as_os_str()),
            Component::RootDir => {
                root.push(component.as_os_str());
                saw_root = true;
            }
            Component::Normal(name) => {
                let name = name
                    .to_str()
                    .filter(|name| !name.is_empty())
                    .ok_or_else(invalid_absolute_path)?;
                names.push(name.to_owned());
            }
            Component::CurDir | Component::ParentDir => return Err(invalid_absolute_path()),
        }
    }
    if !saw_root {
        return Err(invalid_absolute_path());
    }
    Ok((root, names))
}

fn invalid_absolute_path() -> TxnError {
    TxnError::new(
        Operation::PrepareParent,
        FileSystemFailure::bridge_invalid_argument(),
    )
}

fn invalid_relative_path() -> TxnError {
    TxnError::new(
        Operation::Begin,
        FileSystemFailure::bridge_invalid_argument(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn retained_directory_opens_through_search_only_ancestor() {
        use std::os::unix::fs::PermissionsExt;

        let mut nonce = [0_u8; 8];
        getrandom::fill(&mut nonce).expect("test nonce generation must succeed");
        let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
        let base = std::env::temp_dir().join(format!("keyguard-directory-{nonce}"));
        let ancestor = base.join("search-only");
        let selected = ancestor.join("selected");
        std::fs::create_dir_all(&selected).expect("test directory must be created");
        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o111))
            .expect("ancestor must become search-only");

        let opened = AtomicDirectory::open(&crate::fsops::RealFs, &selected);

        std::fs::set_permissions(&ancestor, std::fs::Permissions::from_mode(0o700))
            .expect("ancestor permissions must be restored");
        std::fs::remove_dir_all(&base).expect("test directory must be removed");
        opened.expect("the selected directory must open through a search-only ancestor");
    }

    #[test]
    fn relative_destination_accepts_portable_descendants() {
        let destination =
            RelativeDestination::parse("year/month/object.bin").expect("path must parse");
        assert_eq!(destination.parent_components(), ["year", "month"]);
        assert_eq!(destination.file_name(), "object.bin");
    }

    #[test]
    fn relative_destination_rejects_ambiguous_or_escaping_spellings() {
        for value in [
            "",
            "/object",
            "\\\\server\\share\\object",
            "C:/object",
            "dir/",
            "dir\\object",
            "dir//object",
            "./object",
            "dir/../object",
            "dir/./object",
            "object:stream",
            "object\0tail",
        ] {
            assert!(
                RelativeDestination::parse(value).is_err(),
                "{value:?} must be rejected"
            );
        }
    }
}
