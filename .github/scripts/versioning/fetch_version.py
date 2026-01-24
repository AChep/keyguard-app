"""Fetch the highest version code from Google Play Console.

This script queries all release tracks (or specified tracks) and returns
the maximum version code found, along with per-track breakdown.

CLI Usage:
    python fetch_version.py --package-name com.example.app --credentials creds.json --output result.json

Requirements:
    pip install typer google-auth google-api-python-client
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Annotated, Any

import typer
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

if TYPE_CHECKING:
    from googleapiclient._apis.androidpublisher.v3 import AndroidPublisherResource


# =============================================================================
# Exception Classes
# =============================================================================


class InputValidationError(Exception):
    """Raised when input validation fails.

    Examples:
        - Required file not found
        - Invalid JSON format
        - Missing required fields
    """

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


class CredentialsError(Exception):
    """Raised when credentials are invalid or insufficient.

    Examples:
        - Invalid service account JSON
        - Expired or revoked tokens
        - Insufficient permissions
    """

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


class ApiError(Exception):
    """Raised when an external API call fails.

    Attributes:
        message: Human-readable error description
        service: Name of the service that failed (e.g., 'google_play')
        status_code: HTTP status code if applicable, None otherwise
    """

    def __init__(
        self,
        message: str,
        service: str,
        status_code: int | None = None,
    ) -> None:
        self.message = message
        self.service = service
        self.status_code = status_code
        super().__init__(message)

    def __str__(self) -> str:
        if self.status_code:
            return f"[{self.service}] {self.message} (status: {self.status_code})"
        return f"[{self.service}] {self.message}"


# =============================================================================
# Utility Functions
# =============================================================================


def create_timestamp() -> str:
    """Create an ISO 8601 timestamp in UTC.

    Returns:
        ISO 8601 formatted timestamp string (e.g., "2026-01-23T21:05:00Z")
    """
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_json_output(data: dict[str, Any], output_path: Path) -> None:
    """Write a dictionary to a JSON file.

    Creates parent directories if they don't exist.
    Uses 2-space indentation for readability.

    Args:
        data: Dictionary to serialize as JSON
        output_path: Path to the output file

    Raises:
        OSError: If the file cannot be written
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


# =============================================================================
# Constants
# =============================================================================

# Standard Google Play tracks
DEFAULT_TRACKS = ["internal", "alpha", "beta", "production"]

# Required scope for reading app information
SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]


# =============================================================================
# Typer App
# =============================================================================

app = typer.Typer(
    name="version-fetcher",
    help="Fetch the highest version code from Google Play Console.",
    no_args_is_help=True,
)


# =============================================================================
# Core Functions
# =============================================================================


def create_play_service(credentials_path: Path) -> AndroidPublisherResource:
    """Create an authenticated Google Play Developer API service.

    Args:
        credentials_path: Path to service account JSON file

    Returns:
        Authenticated Android Publisher API resource

    Raises:
        CredentialsError: If credentials file is invalid
        InputValidationError: If credentials file is not found
    """
    if not credentials_path.exists():
        raise InputValidationError(f"Credentials file not found: {credentials_path}")

    try:
        credentials = service_account.Credentials.from_service_account_file(
            str(credentials_path),
            scopes=SCOPES,
        )
        return build("androidpublisher", "v3", credentials=credentials)
    except ValueError as e:
        raise CredentialsError(f"Invalid service account credentials: {e}") from e


def get_track_version_codes(
    service: AndroidPublisherResource,
    package_name: str,
    track: str,
) -> list[int]:
    """Get all version codes for a specific track.

    Args:
        service: Authenticated Android Publisher API resource
        package_name: Android app package name
        track: Track name (internal, alpha, beta, production)

    Returns:
        List of version codes for the track (empty if no releases)

    Raises:
        ApiError: If the API call fails
    """
    try:
        # Create an edit session (read-only, we won't commit)
        edit_request = service.edits().insert(body={}, packageName=package_name)
        edit = edit_request.execute()
        edit_id = edit["id"]

        try:
            # Get track info
            track_response = (
                service.edits()
                .tracks()
                .get(
                    packageName=package_name,
                    editId=edit_id,
                    track=track,
                )
                .execute()
            )

            version_codes = []
            releases = track_response.get("releases", [])
            for release in releases:
                codes = release.get("versionCodes", [])
                version_codes.extend(int(code) for code in codes)

            return version_codes

        finally:
            # Always delete the edit (we don't need to commit)
            service.edits().delete(packageName=package_name, editId=edit_id).execute()

    except HttpError as e:
        if e.resp.status == 404:
            # Track doesn't exist or has no releases
            return []
        raise ApiError(
            f"Failed to get track info for '{track}': {e.reason}",
            service="google_play",
            status_code=e.resp.status,
        ) from e


def fetch_max_version_code(
    package_name: str,
    credentials_path: Path,
    service: AndroidPublisherResource | None = None,
) -> dict:
    """Fetch the maximum version code across all specified tracks.

    Args:
        package_name: Android app package name (e.g., 'com.example.app')
        credentials_path: Path to service account JSON credentials
        service: Optional pre-configured service (for testing)

    Returns:
        Dictionary with version information:
        {
            "max_version_code": int,
            "next_version_code": int,
            "versions_by_track": dict[str, int],
            "package_name": str,
            "timestamp": str
        }

    Raises:
        InputValidationError: If credentials file not found
        CredentialsError: If credentials are invalid
        ApiError: If API call fails
    """
    if service is None:
        service = create_play_service(credentials_path)

    tracks_to_check = DEFAULT_TRACKS
    versions_by_track: dict[str, int] = {}

    for track in tracks_to_check:
        version_codes = get_track_version_codes(service, package_name, track)
        # Use max version code for track, or 0 if no versions
        versions_by_track[track] = max(version_codes) if version_codes else 0

    max_version = max(versions_by_track.values()) if versions_by_track else 0

    return {
        "max_version_code": max_version,
        "next_version_code": max_version + 1,
        "versions_by_track": versions_by_track,
        "package_name": package_name,
        "timestamp": create_timestamp(),
    }


# =============================================================================
# CLI Command
# =============================================================================


@app.command()
def main(
    package_name: Annotated[
        str,
        typer.Option(
            "--package-name",
            "-p",
            help="Android app package name (e.g., com.example.app)",
        ),
    ],
    credentials: Annotated[
        Path,
        typer.Option(
            "--credentials",
            "-c",
            help="Path to service account JSON file",
            exists=False,  # We handle existence check ourselves for better error messages
        ),
    ],
    output: Annotated[
        Path,
        typer.Option(
            "--output",
            "-o",
            help="Path to output JSON file",
        ),
    ],
) -> None:
    """Fetch the highest version code from Google Play Console.

    Queries all release tracks (or specified tracks) and outputs a JSON file
    with the maximum version code found and per-track breakdown.
    """
    try:
        result = fetch_max_version_code(
            package_name=package_name,
            credentials_path=credentials,
        )
        write_json_output(result, output)
        typer.echo(f"Version info written to {output}")
        typer.echo(f"Max version code: {result['max_version_code']}")
        typer.echo(f"Next version code: {result['next_version_code']}")

    except (InputValidationError, CredentialsError, ApiError) as e:
        typer.echo(f"Error: {e}", err=True)
        raise typer.Exit(code=1) from e


if __name__ == "__main__":
    app()
