//! Parser for `KnownHostsFile`-formatted data.

use crate::{Error, PublicKey, Result};
use core::str;
use encoding::base64::{Base64, Encoding};

use {
    alloc::string::{String, ToString},
    alloc::vec::Vec,
    core::fmt,
};

#[cfg(feature = "std")]
use std::{fs, path::Path};

/// Character that begins a comment
const COMMENT_DELIMITER: char = '#';
/// The magic string prefix of a hashed hostname
const MAGIC_HASH_PREFIX: &str = "|1|";

/// Parser for `KnownHostsFile`-formatted data, typically found in
/// `~/.ssh/known_hosts`.
///
/// For a full description of the format, see:
/// <https://man7.org/linux/man-pages/man8/sshd.8.html#SSH_KNOWN_HOSTS_FILE_FORMAT>
///
/// Each line of the file consists of a single public key tied to one or more hosts.
/// Blank lines are ignored.
///
/// Public keys consist of the following space-separated fields:
///
/// ```text
/// marker, hostnames, keytype, base64-encoded key, comment
/// ```
///
/// - The marker field is optional, but if present begins with an `@`. Known markers are `@cert-authority`
///   and `@revoked`.
/// - The hostnames is a comma-separated list of patterns (with `*` and '?' as glob-style wildcards)
///   against which hosts are matched. If it begins with a `!` it is a negation of the pattern. If the
///   pattern starts with `[` and ends with `]`, it contains a hostname pattern and a port number separated
///   by a `:`. If it begins with `|1|`, the hostname is hashed. In that case, there can only be one exact
///   hostname and it can't also be negated (ie. `!|1|x|y` is not legal and you can't hash `*.example.org`).
/// - The keytype is `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521`,
///   `ssh-ed25519`, `ssh-dss` or `ssh-rsa`
/// - The comment field is not used for anything (but may be convenient for the user to identify
///   the key).
pub struct KnownHosts<'a> {
    /// Lines of the file being iterated over
    lines: str::Lines<'a>,
}

impl<'a> KnownHosts<'a> {
    /// Create a new parser for the given input buffer.
    pub fn new(input: &'a str) -> Self {
        Self {
            lines: input.lines(),
        }
    }

    /// Read a [`KnownHosts`] file from the filesystem, returning an
    /// [`Entry`] vector on success.
    #[cfg(feature = "std")]
    pub fn read_file(path: impl AsRef<Path>) -> Result<Vec<Entry>> {
        // TODO(tarcieri): permissions checks
        let input = fs::read_to_string(path)?;
        KnownHosts::new(&input).collect()
    }

    /// Get the next line, trimming any comments and trailing whitespace.
    ///
    /// Ignores empty lines.
    fn next_line_trimmed(&mut self) -> Option<&'a str> {
        loop {
            let mut line = self.lines.next()?;

            // Strip comment if present
            if let Some((l, _)) = line.split_once(COMMENT_DELIMITER) {
                line = l;
            }

            // Trim trailing whitespace
            line = line.trim_end();

            if !line.is_empty() {
                return Some(line);
            }
        }
    }
}

impl Iterator for KnownHosts<'_> {
    type Item = Result<Entry>;

    fn next(&mut self) -> Option<Result<Entry>> {
        self.next_line_trimmed().map(|line| line.parse())
    }
}

/// Individual entry in an `known_hosts` file containing a single public key.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Entry {
    /// Marker field, if present.
    marker: Option<Marker>,

    /// Host patterns
    host_patterns: HostPatterns,

    /// Public key
    public_key: PublicKey,
}

impl Entry {
    /// Get the marker for this entry, if present.
    pub fn marker(&self) -> Option<&Marker> {
        self.marker.as_ref()
    }

    /// Get the host pattern enumerator for this entry
    pub fn host_patterns(&self) -> &HostPatterns {
        &self.host_patterns
    }

    /// Get public key for this entry.
    pub fn public_key(&self) -> &PublicKey {
        &self.public_key
    }
}
impl From<Entry> for Option<Marker> {
    fn from(entry: Entry) -> Option<Marker> {
        entry.marker
    }
}
impl From<Entry> for HostPatterns {
    fn from(entry: Entry) -> HostPatterns {
        entry.host_patterns
    }
}
impl From<Entry> for PublicKey {
    fn from(entry: Entry) -> PublicKey {
        entry.public_key
    }
}

impl str::FromStr for Entry {
    type Err = Error;

    fn from_str(line: &str) -> Result<Self> {
        // Unlike authorized_keys, in known_hosts it's pretty common
        // to not include a key comment, so the number of spaces is
        // not a reliable indicator of the fields in the line. Instead,
        // the optional marker field starts with an @, so look for that
        // and act accordingly.
        let (marker, line) = if line.starts_with('@') {
            let (marker_str, line) = line.split_once(' ').ok_or(Error::FormatEncoding)?;
            (Some(marker_str.parse()?), line)
        } else {
            (None, line)
        };
        let (hosts_str, public_key_str) = line.split_once(' ').ok_or(Error::FormatEncoding)?;

        let host_patterns = hosts_str.parse()?;
        let public_key = public_key_str.parse()?;

        Ok(Self {
            marker,
            host_patterns,
            public_key,
        })
    }
}

impl ToString for Entry {
    fn to_string(&self) -> String {
        let mut s = String::new();

        if let Some(marker) = &self.marker {
            s.push_str(marker.as_str());
            s.push(' ');
        }

        s.push_str(&self.host_patterns.to_string());
        s.push(' ');

        s.push_str(&self.public_key.to_string());
        s
    }
}

/// Markers associated with this host key entry.
///
/// There can only be one of these per host key entry.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Marker {
    /// This host entry's public key is for a certificate authority's private key
    CertAuthority,
    /// This host entry's public key has been revoked, and should not be allowed to connect
    /// regardless of any other entry.
    Revoked,
}

impl Marker {
    /// Get the string form of the marker
    pub fn as_str(&self) -> &str {
        match self {
            Self::CertAuthority => "@cert-authority",
            Self::Revoked => "@revoked",
        }
    }
}

impl AsRef<str> for Marker {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

impl str::FromStr for Marker {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        Ok(match s {
            "@cert-authority" => Marker::CertAuthority,
            "@revoked" => Marker::Revoked,
            _ => return Err(Error::FormatEncoding),
        })
    }
}

impl fmt::Display for Marker {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// The host pattern(s) for this host entry.
///
/// The host patterns can either be a comma separated list of host patterns
/// (which may include glob patterns (`*` and `?`), negations (a `!` prefix),
/// or `pattern:port` pairs inside square brackets), or a single hashed
/// hostname prefixed with `|1|`.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum HostPatterns {
    /// A comma separated list of hostname patterns.
    Patterns(Vec<String>),
    /// A single hashed hostname
    HashedName {
        /// The salt used for the hash
        salt: Vec<u8>,
        /// An SHA-1 hash of the hostname along with the salt
        hash: [u8; 20],
    },
}

impl str::FromStr for HostPatterns {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        if let Some(s) = s.strip_prefix(MAGIC_HASH_PREFIX) {
            let mut hash = [0; 20];
            let (salt, hash_str) = s.split_once('|').ok_or(Error::FormatEncoding)?;

            let salt = Base64::decode_vec(salt)?;
            Base64::decode(hash_str, &mut hash)?;

            Ok(HostPatterns::HashedName { salt, hash })
        } else if !s.is_empty() {
            Ok(HostPatterns::Patterns(
                s.split_terminator(',').map(str::to_string).collect(),
            ))
        } else {
            Err(Error::FormatEncoding)
        }
    }
}

impl ToString for HostPatterns {
    fn to_string(&self) -> String {
        match &self {
            HostPatterns::Patterns(patterns) => patterns.join(","),
            HostPatterns::HashedName { salt, hash } => {
                let salt = Base64::encode_string(salt);
                let hash = Base64::encode_string(hash);
                format!("|1|{salt}|{hash}")
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use alloc::string::ToString;
    use core::str::FromStr;

    use super::Entry;
    use super::HostPatterns;
    use super::Marker;

    #[test]
    fn simple_markers() {
        assert_eq!(Ok(Marker::CertAuthority), "@cert-authority".parse());
        assert_eq!(Ok(Marker::Revoked), "@revoked".parse());
        assert!(Marker::from_str("@gibberish").is_err());
    }

    #[test]
    fn empty_host_patterns() {
        assert!(HostPatterns::from_str("").is_err());
    }

    // Note: The sshd man page has this completely incomprehensible 'example known_hosts entry':
    // closenet,...,192.0.2.53 1024 37 159...93 closenet.example.net
    // I'm not sure how this one is supposed to work or what it means.

    #[test]
    fn single_host_pattern() {
        assert_eq!(
            Ok(HostPatterns::Patterns(vec!["cvs.example.net".to_string()])),
            "cvs.example.net".parse()
        );
    }
    #[test]
    fn multiple_host_patterns() {
        assert_eq!(
            Ok(HostPatterns::Patterns(vec![
                "cvs.example.net".to_string(),
                "!test.example.???".to_string(),
                "[*.example.net]:999".to_string(),
            ])),
            "cvs.example.net,!test.example.???,[*.example.net]:999".parse()
        );
    }
    #[test]
    fn single_hashed_host() {
        assert_eq!(
            Ok(HostPatterns::HashedName {
                salt: vec![
                    37, 242, 147, 116, 24, 123, 172, 214, 215, 145, 80, 16, 9, 26, 120, 57, 10, 15,
                    126, 98
                ],
                hash: [
                    81, 33, 2, 175, 116, 150, 127, 82, 84, 62, 201, 172, 228, 10, 159, 15, 148, 31,
                    198, 67
                ],
            }),
            "|1|JfKTdBh7rNbXkVAQCRp4OQoPfmI=|USECr3SWf1JUPsms5AqfD5QfxkM=".parse()
        );
    }

    #[test]
    fn full_line_hashed() {
        let line = "@revoked |1|lcY/In3lsGnkJikLENb0DM70B/I=|Qs4e9Nr7mM6avuEv02fw2uFnwQo= ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIB9dG4kjRhQTtWTVzd2t27+t0DEHBPW7iOD23TUiYLio comment";
        let entry = Entry::from_str(line).expect("Valid entry");
        assert_eq!(entry.marker(), Some(&Marker::Revoked));
        assert_eq!(
            entry.host_patterns(),
            &HostPatterns::HashedName {
                salt: vec![
                    149, 198, 63, 34, 125, 229, 176, 105, 228, 38, 41, 11, 16, 214, 244, 12, 206,
                    244, 7, 242
                ],
                hash: [
                    66, 206, 30, 244, 218, 251, 152, 206, 154, 190, 225, 47, 211, 103, 240, 218,
                    225, 103, 193, 10
                ],
            }
        );
        // key parsing is tested elsewhere
    }
}
