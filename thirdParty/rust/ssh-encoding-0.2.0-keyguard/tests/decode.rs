//! Tests for the `Decode` trait.

use hex_literal::hex;
use ssh_encoding::{Decode, Error};

#[test]
fn decode_u8() {
    let mut bytes = hex!("42").as_slice();
    let ret = u8::decode(&mut bytes).unwrap();
    assert_eq!(ret, 0x42u8);
}

#[test]
fn decode_u32() {
    let mut bytes = hex!("DEADBEEF").as_slice();
    let ret = u32::decode(&mut bytes).unwrap();
    assert_eq!(ret, 0xDEADBEEFu32);
}

#[test]
fn decode_u64() {
    let mut bytes = hex!("0000DEADBEEFCAFE").as_slice();
    let ret = u64::decode(&mut bytes).unwrap();
    assert_eq!(ret, 0xDEADBEEFCAFEu64);
}

#[test]
fn decode_usize() {
    let mut bytes = hex!("000FFFFF").as_slice();
    let ret = usize::decode(&mut bytes).unwrap();
    assert_eq!(ret, 0xFFFFFusize);
}

/// `usize` decoder has a sanity limit of 0xFFFFF.
#[test]
fn reject_oversize_usize() {
    let mut bytes = hex!("00100000").as_slice();
    let err = usize::decode(&mut bytes).err().unwrap();
    assert_eq!(err, Error::Overflow);
}

#[test]
fn decode_byte_slice() {
    let mut bytes = hex!("000000076578616d706c65").as_slice();
    let ret = <[u8; 7]>::decode(&mut bytes).unwrap();
    assert_eq!(&ret, b"example");
}

#[cfg(feature = "alloc")]
#[test]
fn decode_byte_vec() {
    let mut bytes = hex!("000000076578616d706c65").as_slice();
    let ret = Vec::<u8>::decode(&mut bytes).unwrap();
    assert_eq!(&ret, b"example");
}

#[cfg(feature = "alloc")]
#[test]
fn decode_string() {
    let mut bytes = hex!("000000076578616d706c65").as_slice();
    let ret = String::decode(&mut bytes).unwrap();
    assert_eq!(&ret, "example");
}

#[cfg(feature = "alloc")]
#[test]
fn decode_string_vec() {
    let mut bytes = hex!("0000001500000003666f6f000000036261720000000362617a").as_slice();
    let ret = Vec::<String>::decode(&mut bytes).unwrap();
    assert_eq!(ret.len(), 3);
    assert_eq!(ret[0], "foo");
    assert_eq!(ret[1], "bar");
    assert_eq!(ret[2], "baz");
}
