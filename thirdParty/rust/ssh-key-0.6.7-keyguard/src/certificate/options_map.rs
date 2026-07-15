//! OpenSSH certificate options used by critical options and extensions.

use crate::{Error, Result};
use alloc::{collections::BTreeMap, string::String, vec::Vec};
use core::{
    cmp::Ordering,
    ops::{Deref, DerefMut},
};
use encoding::{CheckedSum, Decode, Encode, Reader, Writer};

/// Key/value map type used for certificate's critical options and extensions.
#[derive(Clone, Debug, Default, Eq, PartialEq, PartialOrd, Ord)]
pub struct OptionsMap(pub BTreeMap<String, String>);

impl OptionsMap {
    /// Create a new [`OptionsMap`].
    pub fn new() -> Self {
        Self::default()
    }
}

impl Deref for OptionsMap {
    type Target = BTreeMap<String, String>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl DerefMut for OptionsMap {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

impl Decode for OptionsMap {
    type Error = Error;

    fn decode(reader: &mut impl Reader) -> Result<Self> {
        reader.read_prefixed(|reader| {
            let mut entries = Vec::<(String, String)>::new();

            while !reader.is_finished() {
                let name = String::decode(reader)?;
                let data = reader.read_prefixed(|reader| {
                    if reader.remaining_len() > 0 {
                        String::decode(reader)
                    } else {
                        Ok(String::default())
                    }
                })?;

                // Options must be lexically ordered by "name" if they appear in
                // the sequence. Each named option may only appear once in a
                // certificate.
                if let Some((prev_name, _)) = entries.last() {
                    if prev_name.cmp(&name) != Ordering::Less {
                        return Err(Error::FormatEncoding);
                    }
                }

                entries.push((name, data));
            }

            Ok(OptionsMap::from_iter(entries))
        })
    }
}

impl Encode for OptionsMap {
    fn encoded_len(&self) -> encoding::Result<usize> {
        self.iter()
            .try_fold(4, |acc, (name, data)| {
                [
                    acc,
                    name.encoded_len()?,
                    if data.is_empty() {
                        4
                    } else {
                        data.encoded_len_prefixed()?
                    },
                ]
                .checked_sum()
            })
            .map_err(Into::into)
    }

    fn encode(&self, writer: &mut impl Writer) -> encoding::Result<()> {
        self.encoded_len()?
            .checked_sub(4)
            .ok_or(encoding::Error::Length)?
            .encode(writer)?;

        for (name, data) in self.iter() {
            name.encode(writer)?;
            if data.is_empty() {
                0usize.encode(writer)?;
            } else {
                data.encode_prefixed(writer)?
            }
        }

        Ok(())
    }
}

impl From<BTreeMap<String, String>> for OptionsMap {
    fn from(map: BTreeMap<String, String>) -> OptionsMap {
        OptionsMap(map)
    }
}

impl From<OptionsMap> for BTreeMap<String, String> {
    fn from(map: OptionsMap) -> BTreeMap<String, String> {
        map.0
    }
}

impl FromIterator<(String, String)> for OptionsMap {
    fn from_iter<T>(iter: T) -> Self
    where
        T: IntoIterator<Item = (String, String)>,
    {
        BTreeMap::from_iter(iter).into()
    }
}
