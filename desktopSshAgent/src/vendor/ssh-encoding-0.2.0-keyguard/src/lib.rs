#![no_std]
#![cfg_attr(docsrs, feature(doc_auto_cfg))]
#![doc = include_str!("../README.md")]
#![doc(
    html_logo_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg",
    html_favicon_url = "https://raw.githubusercontent.com/RustCrypto/media/6ee8e381/logo.svg"
)]
#![forbid(unsafe_code)]
#![warn(
    clippy::arithmetic_side_effects,
    clippy::panic,
    clippy::panic_in_result_fn,
    clippy::unwrap_used,
    missing_docs,
    rust_2018_idioms,
    unused_lifetimes,
    unused_qualifications
)]

#[cfg(feature = "alloc")]
#[macro_use]
extern crate alloc;
#[cfg(feature = "std")]
extern crate std;

mod checked;
mod decode;
mod encode;
mod error;
mod label;
mod reader;
mod writer;

pub use crate::{
    checked::CheckedSum,
    decode::Decode,
    encode::Encode,
    error::{Error, Result},
    label::{Label, LabelError},
    reader::{NestedReader, Reader},
    writer::Writer,
};

#[cfg(feature = "base64")]
pub use {
    crate::{reader::Base64Reader, writer::Base64Writer},
    base64,
};

#[cfg(feature = "pem")]
pub use {
    crate::{decode::DecodePem, encode::EncodePem},
    pem::{self, LineEnding},
};

/// Line width used by the PEM encoding of OpenSSH documents.
#[cfg(feature = "pem")]
const PEM_LINE_WIDTH: usize = 70;
