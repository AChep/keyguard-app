//! Structured native-crypto exception policy validation.

use std::{
    collections::{BTreeMap, BTreeSet},
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail, ensure};
use serde::Deserialize;
use time::{Date, Month, OffsetDateTime};

const EXCEPTIONS_PATH: &str = ".github/native-crypto-exceptions.toml";
const DENY_PATH: &str = ".github/native-crypto-deny.toml";

#[derive(Debug, Deserialize)]
struct ExceptionFile {
    version: u32,
    exceptions: Vec<Exception>,
}

#[derive(Debug, Deserialize)]
struct Exception {
    advisory: String,
    owner: String,
    expires: String,
    scope: String,
    rationale: String,
    #[serde(rename = "tracking-issue")]
    tracking_issue: Option<String>,
}

#[derive(Debug, Deserialize)]
struct DenyFile {
    advisories: AdvisoryPolicy,
}

#[derive(Debug, Deserialize)]
struct AdvisoryPolicy {
    ignore: Vec<toml::Value>,
}

#[derive(Debug)]
struct Options {
    repository: PathBuf,
    today: Date,
    require_external_tracking_issue: bool,
}

/// Validate the checked-in exception policy.
pub fn run(arguments: impl IntoIterator<Item = OsString>) -> Result<()> {
    let options = Options::parse(arguments)?;
    let exceptions = read_toml::<ExceptionFile>(
        &options.repository.join(EXCEPTIONS_PATH),
        "native crypto exception policy",
    )?;
    let deny = read_toml::<DenyFile>(&options.repository.join(DENY_PATH), "cargo-deny policy")?;
    validate(
        &exceptions,
        &deny,
        options.today,
        options.require_external_tracking_issue,
    )?;
    println!("Native crypto exception policy passed.");
    Ok(())
}

impl Options {
    fn parse(arguments: impl IntoIterator<Item = OsString>) -> Result<Self> {
        let mut repository = PathBuf::from(".");
        let mut today = OffsetDateTime::now_utc().date();
        let mut require_external_tracking_issue = false;
        let mut arguments = arguments.into_iter();

        while let Some(argument) = arguments.next() {
            match argument.to_str() {
                Some("--repository") => {
                    repository =
                        PathBuf::from(arguments.next().context("--repository requires a path")?);
                }
                Some("--today") => {
                    let value = arguments.next().context("--today requires YYYY-MM-DD")?;
                    let value = value.to_str().context("--today must be valid UTF-8")?;
                    today = parse_date(value).context("invalid --today date")?;
                }
                Some("--require-external-tracking-issue") => {
                    require_external_tracking_issue = true;
                }
                Some(value) => bail!("unknown argument: {value}"),
                None => bail!("arguments must be valid UTF-8"),
            }
        }

        Ok(Self {
            repository,
            today,
            require_external_tracking_issue,
        })
    }
}

fn read_toml<T>(path: &Path, description: &str) -> Result<T>
where
    T: for<'de> Deserialize<'de>,
{
    let source = fs::read_to_string(path)
        .with_context(|| format!("failed to read {description} at {}", path.display()))?;
    toml::from_str(&source)
        .with_context(|| format!("failed to parse {description} at {}", path.display()))
}

fn validate(
    exceptions: &ExceptionFile,
    deny: &DenyFile,
    today: Date,
    require_external_tracking_issue: bool,
) -> Result<()> {
    ensure!(
        exceptions.version == 1,
        "unsupported exception policy version"
    );
    ensure!(
        !exceptions.exceptions.is_empty(),
        "exception policy must not be empty"
    );

    let ignored = ignored_rustsec_advisories(deny)?;
    let mut configured = BTreeSet::new();
    for exception in &exceptions.exceptions {
        ensure!(
            is_rustsec_id(&exception.advisory),
            "invalid RustSec advisory id: {}",
            exception.advisory
        );
        ensure!(
            configured.insert(exception.advisory.clone()),
            "duplicate exception: {}",
            exception.advisory
        );
        ensure!(
            !exception.owner.trim().is_empty(),
            "exception owner is empty"
        );
        ensure!(
            !exception.scope.trim().is_empty(),
            "exception scope is empty"
        );
        ensure!(
            !exception.rationale.trim().is_empty(),
            "exception rationale is empty"
        );

        let expires = parse_date(&exception.expires)
            .with_context(|| format!("invalid expiry for {}", exception.advisory))?;
        ensure!(
            today < expires,
            "{} expired on {}",
            exception.advisory,
            exception.expires
        );

        match exception.tracking_issue.as_deref() {
            Some(issue) => ensure!(
                is_external_tracking_issue(issue),
                "{} has an invalid external tracking issue",
                exception.advisory
            ),
            None if require_external_tracking_issue => bail!(
                "{} is missing an external tracking issue",
                exception.advisory
            ),
            None => {}
        }
    }

    ensure!(
        configured == ignored,
        "structured exceptions and cargo-deny ignored advisories differ: configured={configured:?}, ignored={ignored:?}"
    );
    Ok(())
}

fn ignored_rustsec_advisories(deny: &DenyFile) -> Result<BTreeSet<String>> {
    let mut ignored = BTreeMap::new();
    for value in &deny.advisories.ignore {
        let Some(table) = value.as_table() else {
            if let Some(id) = value.as_str().filter(|id| is_rustsec_id(id)) {
                bail!("ignored advisory {id} must include a reason");
            }
            continue;
        };
        let Some(id) = table.get("id").and_then(toml::Value::as_str) else {
            continue;
        };
        if !is_rustsec_id(id) {
            continue;
        }
        let reason = table
            .get("reason")
            .and_then(toml::Value::as_str)
            .unwrap_or_default();
        ensure!(
            !reason.trim().is_empty(),
            "ignored advisory {id} has no reason"
        );
        ensure!(
            ignored.insert(id.to_owned(), ()).is_none(),
            "cargo-deny repeats ignored advisory {id}"
        );
    }
    Ok(ignored.into_keys().collect())
}

fn parse_date(value: &str) -> Result<Date> {
    let mut parts = value.split('-');
    let year = parts.next().context("missing year")?.parse::<i32>()?;
    let month = parts.next().context("missing month")?.parse::<u8>()?;
    let day = parts.next().context("missing day")?.parse::<u8>()?;
    ensure!(parts.next().is_none(), "date has too many components");
    Date::from_calendar_date(year, Month::try_from(month)?, day).map_err(Into::into)
}

fn is_rustsec_id(value: &str) -> bool {
    let Some((year, sequence)) = value
        .strip_prefix("RUSTSEC-")
        .and_then(|value| value.split_once('-'))
    else {
        return false;
    };
    year.len() == 4
        && sequence.len() == 4
        && year.bytes().all(|byte| byte.is_ascii_digit())
        && sequence.bytes().all(|byte| byte.is_ascii_digit())
}

fn is_external_tracking_issue(value: &str) -> bool {
    let Some(remainder) = value.strip_prefix("https://") else {
        return false;
    };
    let Some((host, path)) = remainder.split_once('/') else {
        return false;
    };
    !host.is_empty()
        && host.contains('.')
        && !host.ends_with("example.com")
        && !host.ends_with("example.org")
        && !host.ends_with("example.invalid")
        && !value.bytes().any(|byte| byte.is_ascii_whitespace())
        && path.bytes().any(|byte| byte.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn exception_file(tracking_issue: Option<&str>) -> ExceptionFile {
        ExceptionFile {
            version: 1,
            exceptions: vec![Exception {
                advisory: "RUSTSEC-2023-0071".to_owned(),
                owner: "security".to_owned(),
                expires: "2026-10-13".to_owned(),
                scope: "reviewed scope".to_owned(),
                rationale: "reviewed boundary".to_owned(),
                tracking_issue: tracking_issue.map(str::to_owned),
            }],
        }
    }

    fn deny_file(id: &str) -> DenyFile {
        let value = toml::Value::Table(toml::Table::from_iter([
            ("id".to_owned(), toml::Value::String(id.to_owned())),
            (
                "reason".to_owned(),
                toml::Value::String("reviewed".to_owned()),
            ),
        ]));
        DenyFile {
            advisories: AdvisoryPolicy {
                ignore: vec![value],
            },
        }
    }

    #[test]
    fn accepts_current_reviewed_exception() {
        let result = validate(
            &exception_file(None),
            &deny_file("RUSTSEC-2023-0071"),
            parse_date("2026-07-15").expect("valid test date"),
            false,
        );
        assert!(result.is_ok());
    }

    #[test]
    fn rejects_expired_exception() {
        let result = validate(
            &exception_file(None),
            &deny_file("RUSTSEC-2023-0071"),
            parse_date("2026-10-13").expect("valid test date"),
            false,
        );
        assert!(result.is_err());
    }

    #[test]
    fn release_requires_external_tracking_issue() {
        let result = validate(
            &exception_file(None),
            &deny_file("RUSTSEC-2023-0071"),
            parse_date("2026-07-15").expect("valid test date"),
            true,
        );
        assert!(result.is_err());
    }

    #[test]
    fn accepts_real_external_tracking_issue() {
        let result = validate(
            &exception_file(Some("https://github.com/AChep/keyguard-app/issues/123")),
            &deny_file("RUSTSEC-2023-0071"),
            parse_date("2026-07-15").expect("valid test date"),
            true,
        );
        assert!(result.is_ok());
    }

    #[test]
    fn rejects_cargo_deny_drift() {
        let result = validate(
            &exception_file(None),
            &deny_file("RUSTSEC-2025-0001"),
            parse_date("2026-07-15").expect("valid test date"),
            false,
        );
        assert!(result.is_err());
    }
}
