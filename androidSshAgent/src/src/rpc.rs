use anyhow::{bail, Context, Result};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use prost::Message;

use common_ssh_agent_rust::messages::{
    ipc_request, ipc_response, CallerIdentity, ErrorResponse, IpcRequest, IpcResponse,
    ListKeysRequest, ListKeysResponse, SignDataRequest, SignDataResponse, SshKey,
};

use crate::caller_identity::CallerIdentity as AndroidCallerIdentity;

pub const SSH_AGENT_FAILURE: u8 = 5;
pub const SSH_AGENT_REQUEST_IDENTITIES: u8 = 11;
pub const SSH_AGENT_IDENTITIES_ANSWER: u8 = 12;
pub const SSH_AGENT_SIGN_REQUEST: u8 = 13;
pub const SSH_AGENT_SIGN_RESPONSE: u8 = 14;

#[derive(Debug)]
pub enum RequestTranslation {
    Forward(IpcRequest),
    Failure,
}

pub fn translate_client_packet(
    packet: &[u8],
    request_id: u64,
    caller: Option<&AndroidCallerIdentity>,
) -> RequestTranslation {
    let Some((&message_type, payload)) = packet.split_first() else {
        return RequestTranslation::Failure;
    };

    match message_type {
        SSH_AGENT_REQUEST_IDENTITIES => {
            if !payload.is_empty() {
                return RequestTranslation::Failure;
            }

            RequestTranslation::Forward(IpcRequest {
                id: request_id,
                request: Some(ipc_request::Request::ListKeys(ListKeysRequest {
                    caller: caller.map(to_proto_caller_identity),
                })),
            })
        }
        SSH_AGENT_SIGN_REQUEST => {
            let Some((public_key, data, flags)) = parse_sign_request(payload) else {
                return RequestTranslation::Failure;
            };

            RequestTranslation::Forward(IpcRequest {
                id: request_id,
                request: Some(ipc_request::Request::SignData(SignDataRequest {
                    public_key,
                    data,
                    flags,
                    caller: caller.map(to_proto_caller_identity),
                })),
            })
        }
        _ => RequestTranslation::Failure,
    }
}

fn to_proto_caller_identity(caller: &AndroidCallerIdentity) -> CallerIdentity {
    CallerIdentity {
        pid: caller.pid.unwrap_or_default(),
        uid: caller.uid,
        gid: caller.gid,
        process_name: caller.process_name.clone().unwrap_or_default(),
        executable_path: caller.executable_path.clone().unwrap_or_default(),
        app_pid: 0,
        app_name: String::new(),
        app_bundle_path: String::new(),
    }
}

pub fn translate_server_response(expected_id: u64, response: IpcResponse) -> Result<Vec<u8>> {
    if response.id != expected_id {
        bail!(
            "SSH agent protobuf response id mismatch: expected {} got {}",
            expected_id,
            response.id
        );
    }

    let Some(response) = response.response else {
        bail!("SSH agent protobuf response missing variant");
    };

    match response {
        ipc_response::Response::ListKeys(ListKeysResponse { keys }) => {
            encode_identities_answer(&keys)
        }
        ipc_response::Response::SignData(SignDataResponse {
            signature,
            algorithm,
        }) => encode_sign_response(&signature, &algorithm),
        ipc_response::Response::Error(ErrorResponse { .. })
        | ipc_response::Response::Authenticate(_) => Ok(build_failure_packet()),
    }
}

pub fn build_failure_packet() -> Vec<u8> {
    vec![SSH_AGENT_FAILURE]
}

pub fn encode_protobuf_request(request: &IpcRequest) -> Vec<u8> {
    request.encode_to_vec()
}

pub fn decode_protobuf_response(bytes: &[u8]) -> Result<IpcResponse> {
    Ok(IpcResponse::decode(bytes)?)
}

fn parse_sign_request(payload: &[u8]) -> Option<(String, Vec<u8>, u32)> {
    let mut cursor = payload;
    let public_key_blob = read_ssh_string(&mut cursor).ok()?;
    let data = read_ssh_string(&mut cursor).ok()?.to_vec();
    let flags = read_u32(&mut cursor).ok()?;
    if !cursor.is_empty() {
        return None;
    }

    let public_key = reconstruct_openssh_public_key(public_key_blob)?;
    Some((public_key, data, flags))
}

fn encode_identities_answer(keys: &[SshKey]) -> Result<Vec<u8>> {
    let mut packet = vec![SSH_AGENT_IDENTITIES_ANSWER];
    let count_pos = packet.len();
    packet.extend_from_slice(&0u32.to_be_bytes());

    let mut count = 0u32;
    for key in keys {
        let Some(blob) = decode_openssh_public_key_blob(&key.public_key) else {
            continue;
        };
        encode_ssh_string(&mut packet, &blob)
            .context("Failed to encode SSH agent public key blob")?;
        encode_ssh_string(&mut packet, key.name.as_bytes())
            .context("Failed to encode SSH agent key comment")?;
        count = count
            .checked_add(1)
            .context("Too many SSH agent identities to encode")?;
    }

    packet[count_pos..count_pos + 4].copy_from_slice(&count.to_be_bytes());
    Ok(packet)
}

fn encode_sign_response(signature: &[u8], algorithm: &str) -> Result<Vec<u8>> {
    let mut signature_blob = Vec::new();
    encode_ssh_string(&mut signature_blob, algorithm.as_bytes())
        .context("Failed to encode SSH agent signature algorithm")?;
    encode_ssh_string(&mut signature_blob, signature)
        .context("Failed to encode SSH agent signature")?;

    let mut packet = vec![SSH_AGENT_SIGN_RESPONSE];
    encode_ssh_string(&mut packet, &signature_blob)
        .context("Failed to encode SSH agent signature blob")?;
    Ok(packet)
}

fn encode_ssh_string(packet: &mut Vec<u8>, bytes: &[u8]) -> Result<()> {
    let len = u32::try_from(bytes.len()).context("SSH agent string exceeds u32 length")?;
    packet.extend_from_slice(&len.to_be_bytes());
    packet.extend_from_slice(bytes);
    Ok(())
}

fn read_u32(cursor: &mut &[u8]) -> Result<u32, &'static str> {
    if cursor.len() < 4 {
        return Err("missing_u32");
    }

    let mut len_bytes = [0u8; 4];
    len_bytes.copy_from_slice(&cursor[..4]);
    *cursor = &cursor[4..];
    Ok(u32::from_be_bytes(len_bytes))
}

fn read_ssh_string<'a>(cursor: &mut &'a [u8]) -> Result<&'a [u8], &'static str> {
    let len = read_u32(cursor)? as usize;
    if cursor.len() < len {
        return Err("truncated_string");
    }
    let (value, rest) = cursor.split_at(len);
    *cursor = rest;
    Ok(value)
}

fn decode_openssh_public_key_blob(public_key: &str) -> Option<Vec<u8>> {
    let mut parts = public_key.split_whitespace();
    let _key_type = parts.next()?;
    let encoded_blob = parts.next()?;
    STANDARD.decode(encoded_blob).ok()
}

fn reconstruct_openssh_public_key(blob: &[u8]) -> Option<String> {
    let mut cursor = blob;
    let key_type = read_ssh_string(&mut cursor).ok()?;
    let key_type = std::str::from_utf8(key_type).ok()?;
    Some(format!("{key_type} {}", STANDARD.encode(blob)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ssh_string(bytes: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        encode_ssh_string(&mut out, bytes).unwrap();
        out
    }

    fn public_key_blob(key_type: &str, payload: &[u8]) -> Vec<u8> {
        let mut blob = Vec::new();
        encode_ssh_string(&mut blob, key_type.as_bytes()).unwrap();
        encode_ssh_string(&mut blob, payload).unwrap();
        blob
    }

    #[test]
    fn translate_request_identities_to_forwarded_list_keys() {
        let caller = AndroidCallerIdentity {
            pid: Some(123),
            uid: 1000,
            gid: 1001,
            process_name: Some("ssh".to_string()),
            executable_path: Some("/usr/bin/ssh".to_string()),
        };

        match translate_client_packet(&[SSH_AGENT_REQUEST_IDENTITIES], 17, Some(&caller)) {
            RequestTranslation::Forward(request) => {
                assert_eq!(request.id, 17);
                match request.request {
                    Some(ipc_request::Request::ListKeys(list)) => {
                        let caller = list.caller.expect("expected caller");
                        assert_eq!(caller.pid, 123);
                        assert_eq!(caller.uid, 1000);
                        assert_eq!(caller.gid, 1001);
                        assert_eq!(caller.process_name, "ssh");
                        assert_eq!(caller.executable_path, "/usr/bin/ssh");
                        assert_eq!(caller.app_pid, 0);
                        assert_eq!(caller.app_name, "");
                        assert_eq!(caller.app_bundle_path, "");
                    }
                    other => panic!("unexpected request variant: {other:?}"),
                }
            }
            RequestTranslation::Failure => panic!("expected forwarded request"),
        }
    }

    #[test]
    fn translate_request_identities_without_caller_keeps_caller_absent() {
        match translate_client_packet(&[SSH_AGENT_REQUEST_IDENTITIES], 17, None) {
            RequestTranslation::Forward(request) => {
                assert_eq!(request.id, 17);
                match request.request {
                    Some(ipc_request::Request::ListKeys(list)) => {
                        assert!(list.caller.is_none());
                    }
                    other => panic!("unexpected request variant: {other:?}"),
                }
            }
            RequestTranslation::Failure => panic!("expected forwarded request"),
        }
    }

    #[test]
    fn translate_request_sign_request_to_forwarded_sign_data() {
        let blob = public_key_blob("ssh-ed25519", &[1, 2, 3, 4]);
        let data = b"payload";
        let mut packet = vec![SSH_AGENT_SIGN_REQUEST];
        packet.extend_from_slice(&ssh_string(&blob));
        packet.extend_from_slice(&ssh_string(data));
        packet.extend_from_slice(&0x04u32.to_be_bytes());

        match translate_client_packet(
            &packet,
            42,
            Some(&AndroidCallerIdentity {
                pid: Some(55),
                uid: 2000,
                gid: 2001,
                process_name: Some("termux".to_string()),
                executable_path: Some("/data/data/com.termux/files/usr/bin/ssh".to_string()),
            }),
        ) {
            RequestTranslation::Forward(request) => {
                assert_eq!(request.id, 42);
                match request.request {
                    Some(ipc_request::Request::SignData(sign)) => {
                        assert_eq!(
                            sign.public_key,
                            format!("ssh-ed25519 {}", STANDARD.encode(&blob))
                        );
                        assert_eq!(sign.data, data);
                        assert_eq!(sign.flags, 0x04);
                        let caller = sign.caller.expect("expected caller");
                        assert_eq!(caller.pid, 55);
                        assert_eq!(caller.uid, 2000);
                        assert_eq!(caller.gid, 2001);
                        assert_eq!(caller.process_name, "termux");
                        assert_eq!(
                            caller.executable_path,
                            "/data/data/com.termux/files/usr/bin/ssh"
                        );
                    }
                    other => panic!("unexpected request variant: {other:?}"),
                }
            }
            RequestTranslation::Failure => panic!("expected forwarded request"),
        }
    }

    #[test]
    fn translate_request_unknown_opcode_returns_failure() {
        assert!(matches!(
            translate_client_packet(&[0xFF], 1, None),
            RequestTranslation::Failure
        ));
    }

    #[test]
    fn translate_request_sign_request_with_trailing_bytes_returns_failure() {
        let blob = public_key_blob("ssh-ed25519", &[1, 2, 3, 4]);
        let data = b"payload";
        let mut packet = vec![SSH_AGENT_SIGN_REQUEST];
        packet.extend_from_slice(&ssh_string(&blob));
        packet.extend_from_slice(&ssh_string(data));
        packet.extend_from_slice(&0x04u32.to_be_bytes());
        packet.push(0xAA);

        assert!(matches!(
            translate_client_packet(&packet, 1, None),
            RequestTranslation::Failure
        ));
    }

    #[test]
    fn translate_response_list_keys_encodes_identity_answer() {
        let blob = public_key_blob("ssh-ed25519", &[1, 2, 3, 4]);
        let response = IpcResponse {
            id: 7,
            response: Some(ipc_response::Response::ListKeys(ListKeysResponse {
                keys: vec![
                    SshKey {
                        name: "valid".to_string(),
                        public_key: format!("ssh-ed25519 {}", STANDARD.encode(&blob)),
                        key_type: "ssh-ed25519".to_string(),
                        fingerprint: "SHA256:abc".to_string(),
                    },
                    SshKey {
                        name: "invalid".to_string(),
                        public_key: "broken-key".to_string(),
                        key_type: "ssh-ed25519".to_string(),
                        fingerprint: "SHA256:def".to_string(),
                    },
                ],
            })),
        };

        let packet = translate_server_response(7, response).unwrap();
        assert_eq!(packet[0], SSH_AGENT_IDENTITIES_ANSWER);

        let mut cursor = &packet[1..];
        let count = read_u32(&mut cursor).unwrap();
        assert_eq!(count, 1);
        let decoded_blob = read_ssh_string(&mut cursor).unwrap();
        assert_eq!(decoded_blob, blob.as_slice());
        let comment = read_ssh_string(&mut cursor).unwrap();
        assert_eq!(comment, b"valid");
        assert!(cursor.is_empty());
    }

    #[test]
    fn translate_response_sign_data_encodes_sign_response() {
        let response = IpcResponse {
            id: 9,
            response: Some(ipc_response::Response::SignData(SignDataResponse {
                signature: vec![0xAA, 0xBB, 0xCC],
                algorithm: "ssh-ed25519".to_string(),
            })),
        };

        let packet = translate_server_response(9, response).unwrap();
        assert_eq!(packet[0], SSH_AGENT_SIGN_RESPONSE);

        let mut cursor = &packet[1..];
        let signature_blob = read_ssh_string(&mut cursor).unwrap();
        let mut signature_cursor = signature_blob;
        assert_eq!(
            read_ssh_string(&mut signature_cursor).unwrap(),
            b"ssh-ed25519"
        );
        assert_eq!(
            read_ssh_string(&mut signature_cursor).unwrap(),
            &[0xAA, 0xBB, 0xCC]
        );
        assert!(signature_cursor.is_empty());
        assert!(cursor.is_empty());
    }

    #[test]
    fn translate_response_error_returns_failure_packet() {
        let response = IpcResponse {
            id: 3,
            response: Some(ipc_response::Response::Error(ErrorResponse {
                message: "denied".to_string(),
                code: 0,
            })),
        };

        let packet = translate_server_response(3, response).unwrap();
        assert_eq!(packet, build_failure_packet());
    }

    #[test]
    fn translate_response_rejects_mismatched_id() {
        let response = IpcResponse {
            id: 4,
            response: Some(ipc_response::Response::Error(ErrorResponse {
                message: "denied".to_string(),
                code: 0,
            })),
        };

        let err = translate_server_response(5, response).unwrap_err();
        assert!(err.to_string().contains("response id mismatch"));
    }
}
