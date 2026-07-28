//! Parser for `AuthorizedKeysFile`-formatted data.

use crate::{Error, PublicKey, Result};
use core::str;

#[cfg(feature = "alloc")]
use {
    alloc::string::{String, ToString},
    core::fmt,
};

#[cfg(feature = "std")]
use std::{fs, path::Path, vec::Vec};

/// Character that begins a comment
const COMMENT_DELIMITER: char = '#';

/// Parser for `AuthorizedKeysFile`-formatted data, typically found in
/// `~/.ssh/authorized_keys`.
///
/// For a full description of the format, see:
/// <https://man7.org/linux/man-pages/man8/sshd.8.html#AUTHORIZED_KEYS_FILE_FORMAT>
///
/// Each line of the file consists of a single public key. Blank lines are ignored.
///
/// Public keys consist of the following space-separated fields:
///
/// ```text
/// options, keytype, base64-encoded key, comment
/// ```
///
/// - The options field is optional.
/// - The keytype is `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521`,
///   `ssh-ed25519`, `ssh-dss` or `ssh-rsa`
/// - The comment field is not used for anything (but may be convenient for the user to identify
///   the key).
pub struct AuthorizedKeys<'a> {
    /// Lines of the file being iterated over
    lines: str::Lines<'a>,
}

impl<'a> AuthorizedKeys<'a> {
    /// Create a new parser for the given input buffer.
    pub fn new(input: &'a str) -> Self {
        Self {
            lines: input.lines(),
        }
    }

    /// Read an [`AuthorizedKeys`] file from the filesystem, returning an
    /// [`Entry`] vector on success.
    #[cfg(feature = "std")]
    pub fn read_file(path: impl AsRef<Path>) -> Result<Vec<Entry>> {
        // TODO(tarcieri): permissions checks
        let input = fs::read_to_string(path)?;
        AuthorizedKeys::new(&input).collect()
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

impl Iterator for AuthorizedKeys<'_> {
    type Item = Result<Entry>;

    fn next(&mut self) -> Option<Result<Entry>> {
        self.next_line_trimmed().map(|line| line.parse())
    }
}

/// Individual entry in an `authorized_keys` file containing a single public key.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Entry {
    /// Configuration options field, if present.
    #[cfg(feature = "alloc")]
    config_opts: ConfigOpts,

    /// Public key
    public_key: PublicKey,
}

impl Entry {
    /// Get configuration options for this entry.
    #[cfg(feature = "alloc")]
    pub fn config_opts(&self) -> &ConfigOpts {
        &self.config_opts
    }

    /// Get public key for this entry.
    pub fn public_key(&self) -> &PublicKey {
        &self.public_key
    }
}

#[cfg(feature = "alloc")]
impl From<Entry> for ConfigOpts {
    fn from(entry: Entry) -> ConfigOpts {
        entry.config_opts
    }
}

impl From<Entry> for PublicKey {
    fn from(entry: Entry) -> PublicKey {
        entry.public_key
    }
}

impl From<PublicKey> for Entry {
    fn from(public_key: PublicKey) -> Entry {
        Entry {
            #[cfg(feature = "alloc")]
            config_opts: ConfigOpts::default(),
            public_key,
        }
    }
}

impl str::FromStr for Entry {
    type Err = Error;

    fn from_str(line: &str) -> Result<Self> {
        match line.matches(' ').count() {
            1..=2 => Ok(Self {
                #[cfg(feature = "alloc")]
                config_opts: Default::default(),
                public_key: line.parse()?,
            }),
            3.. => {
                // Having >= 3 spaces is ambiguous: it's either a key preceded
                // by options, or a key with spaces in its comment.  We'll try
                // parsing as a single key first, then fall back to parsing as
                // option + key.
                match line.parse() {
                    Ok(public_key) => Ok(Self {
                        #[cfg(feature = "alloc")]
                        config_opts: Default::default(),
                        public_key,
                    }),
                    Err(_) => line
                        .split_once(' ')
                        .map(|(config_opts_str, public_key_str)| {
                            ConfigOptsIter(config_opts_str).validate()?;

                            Ok(Self {
                                #[cfg(feature = "alloc")]
                                config_opts: ConfigOpts(config_opts_str.to_string()),
                                public_key: public_key_str.parse()?,
                            })
                        })
                        .ok_or(Error::FormatEncoding)?,
                }
            }
            _ => Err(Error::FormatEncoding),
        }
    }
}

#[cfg(feature = "alloc")]
impl ToString for Entry {
    fn to_string(&self) -> String {
        let mut s = String::new();

        if !self.config_opts.is_empty() {
            s.push_str(self.config_opts.as_str());
            s.push(' ');
        }

        s.push_str(&self.public_key.to_string());
        s
    }
}

/// Configuration options associated with a particular public key.
///
/// These options are a comma-separated list preceding each public key
/// in the `authorized_keys` file.
///
/// The [`ConfigOpts::iter`] method can be used to iterate over each
/// comma-separated value.
#[cfg(feature = "alloc")]
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ConfigOpts(String);

#[cfg(feature = "alloc")]
impl ConfigOpts {
    /// Parse an options string.
    pub fn new(string: impl Into<String>) -> Result<Self> {
        let ret = Self(string.into());
        ret.iter().validate()?;
        Ok(ret)
    }

    /// Borrow the configuration options as a `str`.
    pub fn as_str(&self) -> &str {
        self.0.as_str()
    }

    /// Are there no configuration options?
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Iterate over the comma-delimited configuration options.
    pub fn iter(&self) -> ConfigOptsIter<'_> {
        ConfigOptsIter(self.as_str())
    }
}

#[cfg(feature = "alloc")]
impl AsRef<str> for ConfigOpts {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

#[cfg(feature = "alloc")]
impl str::FromStr for ConfigOpts {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        Self::new(s)
    }
}

#[cfg(feature = "alloc")]
impl fmt::Display for ConfigOpts {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Iterator over configuration options.
#[derive(Clone, Debug)]
pub struct ConfigOptsIter<'a>(&'a str);

impl<'a> ConfigOptsIter<'a> {
    /// Create new configuration options iterator.
    ///
    /// Validates that the options are well-formed.
    pub fn new(s: &'a str) -> Result<Self> {
        let ret = Self(s);
        ret.clone().validate()?;
        Ok(ret)
    }

    /// Validate that config options are well-formed.
    fn validate(&mut self) -> Result<()> {
        while self.try_next()?.is_some() {}
        Ok(())
    }

    /// Attempt to parse the next comma-delimited option string.
    fn try_next(&mut self) -> Result<Option<&'a str>> {
        if self.0.is_empty() {
            return Ok(None);
        }

        let mut quoted = false;
        let mut index = 0;

        while let Some(byte) = self.0.as_bytes().get(index).cloned() {
            match byte {
                b',' => {
                    // Commas inside quoted text are ignored
                    if !quoted {
                        let (next, rest) = self.0.split_at(index);
                        self.0 = &rest[1..]; // Strip comma
                        return Ok(Some(next));
                    }
                }
                // TODO(tarcieri): stricter handling of quotes
                b'"' => {
                    // Toggle quoted mode on-off
                    quoted = !quoted;
                }
                // Valid characters
                b'A'..=b'Z'
                | b'a'..=b'z'
                | b'0'..=b'9'
                | b'!'..=b'/'
                | b':'..=b'@'
                | b'['..=b'_'
                | b'{'
                | b'}'
                | b'|'
                | b'~' => (),
                _ => return Err(encoding::Error::CharacterEncoding.into()),
            }

            index = index.checked_add(1).ok_or(encoding::Error::Length)?;
        }

        let remaining = self.0;
        self.0 = "";
        Ok(Some(remaining))
    }
}

impl<'a> Iterator for ConfigOptsIter<'a> {
    type Item = &'a str;

    fn next(&mut self) -> Option<&'a str> {
        // Ensured valid by constructor
        self.try_next().expect("malformed options string")
    }
}

#[cfg(all(test, feature = "alloc"))]
mod tests {
    use super::ConfigOptsIter;

    #[test]
    fn options_empty() {
        assert_eq!(ConfigOptsIter("").try_next(), Ok(None));
    }

    #[test]
    fn options_no_comma() {
        let mut opts = ConfigOptsIter("foo");
        assert_eq!(opts.try_next(), Ok(Some("foo")));
        assert_eq!(opts.try_next(), Ok(None));
    }

    #[test]
    fn options_no_comma_quoted() {
        let mut opts = ConfigOptsIter("foo=\"bar\"");
        assert_eq!(opts.try_next(), Ok(Some("foo=\"bar\"")));
        assert_eq!(opts.try_next(), Ok(None));

        // Comma inside quoted section
        let mut opts = ConfigOptsIter("foo=\"bar,baz\"");
        assert_eq!(opts.try_next(), Ok(Some("foo=\"bar,baz\"")));
        assert_eq!(opts.try_next(), Ok(None));
    }

    #[test]
    fn options_comma_delimited() {
        let mut opts = ConfigOptsIter("foo,bar");
        assert_eq!(opts.try_next(), Ok(Some("foo")));
        assert_eq!(opts.try_next(), Ok(Some("bar")));
        assert_eq!(opts.try_next(), Ok(None));
    }

    #[test]
    fn options_comma_delimited_quoted() {
        let mut opts = ConfigOptsIter("foo=\"bar\",baz");
        assert_eq!(opts.try_next(), Ok(Some("foo=\"bar\"")));
        assert_eq!(opts.try_next(), Ok(Some("baz")));
        assert_eq!(opts.try_next(), Ok(None));
    }

    #[test]
    fn options_invalid_character() {
        let mut opts = ConfigOptsIter("❌");
        assert_eq!(
            opts.try_next(),
            Err(encoding::Error::CharacterEncoding.into())
        );

        let mut opts = ConfigOptsIter("x,❌");
        assert_eq!(opts.try_next(), Ok(Some("x")));
        assert_eq!(
            opts.try_next(),
            Err(encoding::Error::CharacterEncoding.into())
        );
    }
}
