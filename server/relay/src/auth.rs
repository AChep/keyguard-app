//! Optional shared-token access control.

use std::net::IpAddr;

use axum::http::header::{AUTHORIZATION, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::Response;
use tokio::time::Instant;

use crate::limits::{AUTH_RETRY_AFTER_SECS, AuthOutcome};
use crate::protocol::ErrorCode;
use crate::{AppState, http_error, with_retry_after};

/// Checks the token of a request from the client key `key`. A key that failed too often
/// must wait before another wrong token is checked. A valid token is always accepted
/// by this guard; HTTP admission still applies. Without a configured token,
/// every request is allowed.
pub(crate) fn check(
    state: &AppState,
    key: IpAddr,
    headers: &HeaderMap,
    query: Option<&str>,
) -> AuthOutcome {
    let Some(expected) = state.config.token.as_deref() else {
        return AuthOutcome::Allowed;
    };
    let authorized = is_authorized(expected, headers, query);
    let outcome = state.auth_guard.check(key, authorized, Instant::now());
    match outcome {
        AuthOutcome::Allowed => {}
        AuthOutcome::Unauthorized => state.metrics.auth_rejections[0].inc(),
        AuthOutcome::Locked => state.metrics.auth_rejections[1].inc(),
    }
    outcome
}

/// The answer to a wrong or missing token: `401 {"error":"unauthorized"}`.
pub(crate) fn unauthorized() -> Response {
    let mut response = http_error(StatusCode::UNAUTHORIZED, ErrorCode::Unauthorized);
    response
        .headers_mut()
        .insert(WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
    response
}

/// The answer to a locked-out key: `429 {"error":"rate_limited"}` with `Retry-After`.
pub(crate) fn locked() -> Response {
    let response = http_error(StatusCode::TOO_MANY_REQUESTS, ErrorCode::RateLimited);
    with_retry_after(response, AUTH_RETRY_AFTER_SECS)
}

/// Whether the request carries `expected` as `Authorization: Bearer <token>` or as
/// the `token` query parameter.
fn is_authorized(expected: &str, headers: &HeaderMap, query: Option<&str>) -> bool {
    let bearer = bearer_token(headers).is_some_and(|token| constant_time_eq(token, expected));
    let query = query_token(query).is_some_and(|token| constant_time_eq(token, expected));
    bearer | query
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let value = headers.get(AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = value.split_once(' ')?;
    scheme.eq_ignore_ascii_case("bearer").then(|| token.trim())
}

/// The configured token only has URL-safe characters, so the raw value is compared
/// without percent-decoding.
fn query_token(query: Option<&str>) -> Option<&str> {
    query?
        .split('&')
        .find_map(|pair| pair.strip_prefix("token="))
}

/// Compares without an early exit on the first differing byte.
fn constant_time_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let diff = a
        .bytes()
        .zip(b.bytes())
        .fold(0u8, |acc, (x, y)| acc | (x ^ y));
    std::hint::black_box(diff) == 0
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    const TOKEN: &str = "abcdefghijklmnop";

    #[test]
    fn accepts_bearer_header_or_query() {
        let mut headers = HeaderMap::new();
        assert!(!is_authorized(TOKEN, &headers, None));
        assert!(is_authorized(
            TOKEN,
            &headers,
            Some("a=1&token=abcdefghijklmnop")
        ));
        assert!(!is_authorized(
            TOKEN,
            &headers,
            Some("token=abcdefghijklmnoq")
        ));
        assert!(!is_authorized(
            TOKEN,
            &headers,
            Some("xtoken=abcdefghijklmnop")
        ));

        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_static("Bearer abcdefghijklmnop"),
        );
        assert!(is_authorized(TOKEN, &headers, None));
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_static("bearer abcdefghijklmnop"),
        );
        assert!(is_authorized(TOKEN, &headers, None));
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_static("Basic abcdefghijklmnop"),
        );
        assert!(!is_authorized(TOKEN, &headers, None));
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_static("Bearer abcdefghijklmno"),
        );
        assert!(!is_authorized(TOKEN, &headers, None));
    }
}
