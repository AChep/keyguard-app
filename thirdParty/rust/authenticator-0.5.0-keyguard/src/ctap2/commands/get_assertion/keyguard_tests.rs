/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

use super::*;
use crate::ctap2::preflight::do_credential_list_filtering_ctap2;
use crate::transport::device_selector::Device;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

fn credential(id: u8) -> PublicKeyCredentialDescriptor {
    PublicKeyCredentialDescriptor {
        id: vec![id],
        transports: vec![],
    }
}

fn request(credentials: Vec<PublicKeyCredentialDescriptor>) -> GetAssertion {
    GetAssertion::new(
        ClientDataHash([7; 32]),
        RelyingParty::from("example.com"),
        credentials,
        GetAssertionOptions::default(),
        GetAssertionExtensions::default(),
    )
}

fn response(credential: Option<&PublicKeyCredentialDescriptor>) -> Vec<u8> {
    let mut auth_data = RelyingParty::from("example.com").hash().0.to_vec();
    auth_data.extend_from_slice(&[1, 0, 0, 0, 1]);
    let mut fields = BTreeMap::from([
        (Value::Integer(2), Value::Bytes(auth_data)),
        (Value::Integer(3), Value::Bytes(vec![0x30, 1, 2])),
    ]);
    if let Some(credential) = credential {
        fields.insert(
            Value::Integer(1),
            serde_cbor::value::to_value(credential).unwrap(),
        );
    }
    let mut bytes = vec![0]; // CTAP2 success status.
    bytes.extend(serde_cbor::to_vec(&fields).unwrap());
    bytes
}

#[test]
fn recovers_omitted_id_after_filtering_multiple_credentials() {
    let mut device = Device::new_skipping_serialization("credential recovery").unwrap();
    device.cid = [1, 2, 3, 4];
    device.set_authenticator_info(AuthenticatorInfo::default());
    let selected = credential(2);
    let mut assertion = request(vec![credential(1), selected.clone()]);
    let wire = response(None);

    // CTAP2.0 devices need not advertise maxCredentialCountInList, so the
    // library probes each ID individually before asking for user presence.
    for candidate in &assertion.allow_list {
        let mut probe = request(vec![candidate.clone()]);
        probe.client_data_hash = ClientDataHash(Sha256::digest("").into());
        probe.options.user_presence = Some(false);
        device.add_upcoming_ctap2_request(&probe);
    }
    device
        .add_upcoming_ctap_error(CommandError::StatusCode(StatusCode::NoCredentials, None).into());
    let probe_result = request(vec![selected.clone()])
        .handle_response_ctap2(&mut device, &wire)
        .unwrap();
    device.add_upcoming_ctap_response(probe_result);
    assertion.allow_list =
        do_credential_list_filtering_ctap2(&mut device, &assertion.allow_list, &assertion.rp, None)
            .unwrap();
    assert_eq!(assertion.allow_list, vec![selected.clone()]);
    assert!(device.upcoming_requests.is_empty());
    assert!(device.upcoming_responses.is_empty());

    let result = assertion.handle_response_ctap2(&mut device, &wire).unwrap();
    assert_eq!(result[0].assertion.credentials, Some(selected));
    assert_eq!(result[0].assertion.signature, vec![0x30, 1, 2]);
}

#[test]
fn preserves_explicit_id_for_caller_validation() {
    let mut device = Device::new_skipping_serialization("explicit credential").unwrap();
    let explicit = credential(9);
    let result = request(vec![credential(1)])
        .handle_response_ctap2(&mut device, &response(Some(&explicit)))
        .unwrap();
    assert_eq!(result[0].assertion.credentials, Some(explicit));
}

#[test]
fn does_not_guess_an_id_for_ambiguous_or_discoverable_requests() {
    let mut device = Device::new_skipping_serialization("ambiguous credential").unwrap();
    for credentials in [vec![], vec![credential(1), credential(2)]] {
        let result = request(credentials)
            .handle_response_ctap2(&mut device, &response(None))
            .unwrap();
        assert_eq!(result[0].assertion.credentials, None);
    }
}
