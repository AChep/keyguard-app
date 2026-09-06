//! Protobuf mapping for one-shot gpg-agent operations.

use crate::{
    openpgp::agent::{
        self as workflow, AgentDecryptInput, AgentOperationFailure, AgentOperationOutcome,
        AgentSignInput, OpenPgpAgentError,
    },
    primitives::PrimitiveError,
};

use super::wire::{
    Message as _, OpenPgpAgentDecryptRequest, OpenPgpAgentDecryptResult,
    OpenPgpAgentDecryptSuccess, OpenPgpAgentError as ProtocolAgentError, OpenPgpAgentErrorReason,
    OpenPgpAgentSignRequest, OpenPgpAgentSignResult, OpenPgpAgentSignSuccess,
    open_pgp_agent_decrypt_result, open_pgp_agent_sign_result,
};

pub(crate) fn sign(mut request: OpenPgpAgentSignRequest) -> Result<Vec<u8>, PrimitiveError> {
    let mut outcome = workflow::sign(AgentSignInput {
        private_key: std::mem::take(&mut request.private_key),
        preferred_fingerprint: std::mem::take(&mut request.preferred_fingerprint),
        hash_algorithm: std::mem::take(&mut request.hash_algorithm),
        hash: std::mem::take(&mut request.hash),
        candidate_revocation_keys: std::mem::take(&mut request.candidate_revocation_keys),
    })
    .map_err(agent_error)?;
    Ok(match &mut outcome {
        AgentOperationOutcome::Success(canonical_sexp) => OpenPgpAgentSignResult {
            result: Some(open_pgp_agent_sign_result::Result::Success(
                OpenPgpAgentSignSuccess {
                    canonical_sexp: std::mem::take(canonical_sexp),
                },
            )),
        }
        .encode_to_vec(),
        AgentOperationOutcome::Failure(reason) => OpenPgpAgentSignResult {
            result: Some(open_pgp_agent_sign_result::Result::Error(
                ProtocolAgentError {
                    reason: agent_reason(*reason) as i32,
                },
            )),
        }
        .encode_to_vec(),
    })
}

pub(crate) fn decrypt(mut request: OpenPgpAgentDecryptRequest) -> Result<Vec<u8>, PrimitiveError> {
    let mut outcome = workflow::decrypt(AgentDecryptInput {
        private_key: std::mem::take(&mut request.private_key),
        preferred_fingerprint: std::mem::take(&mut request.preferred_fingerprint),
        ciphertext: std::mem::take(&mut request.ciphertext),
        unwrap_ecdh: request.unwrap_ecdh,
    })
    .map_err(agent_error)?;
    Ok(match &mut outcome {
        AgentOperationOutcome::Success(canonical_sexp) => OpenPgpAgentDecryptResult {
            result: Some(open_pgp_agent_decrypt_result::Result::Success(
                OpenPgpAgentDecryptSuccess {
                    canonical_sexp: std::mem::take(canonical_sexp),
                },
            )),
        }
        .encode_to_vec(),
        AgentOperationOutcome::Failure(reason) => OpenPgpAgentDecryptResult {
            result: Some(open_pgp_agent_decrypt_result::Result::Error(
                ProtocolAgentError {
                    reason: agent_reason(*reason) as i32,
                },
            )),
        }
        .encode_to_vec(),
    })
}

fn agent_reason(reason: AgentOperationFailure) -> OpenPgpAgentErrorReason {
    match reason {
        AgentOperationFailure::KeyNotFound => OpenPgpAgentErrorReason::KeyNotFound,
        AgentOperationFailure::UnsupportedAlgorithm => {
            OpenPgpAgentErrorReason::UnsupportedAlgorithm
        }
    }
}

fn agent_error(error: OpenPgpAgentError) -> PrimitiveError {
    match error {
        OpenPgpAgentError::InvalidArgument => PrimitiveError::InvalidArgument,
        OpenPgpAgentError::ResourceLimit => PrimitiveError::ResourceLimit,
        OpenPgpAgentError::CryptoFailure => PrimitiveError::CryptoFailure,
        OpenPgpAgentError::Internal => PrimitiveError::Internal,
    }
}
