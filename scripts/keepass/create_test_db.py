#!/usr/bin/env python3

import argparse
import os
import pathlib
import random
import sys
import tempfile
import uuid
from dataclasses import dataclass
from datetime import datetime
from datetime import timedelta
from datetime import timezone


KEYGUARD_ENTRY_TYPE_KEY = "Keyguard: Entry Type"
URI_FIELD_PREFIX = "KP2A_URL_"
DEFAULT_PASSWORD = "test-password"
BASE_TIME = datetime(2024, 1, 1, tzinfo=timezone.utc)


@dataclass(frozen=True)
class Preset:
    name: str
    entry_count: int
    login_count: int
    secure_note_count: int
    custom_field_min: int
    custom_field_max: int
    default_seed: int
    low_field_target_count: int | None = None
    low_field_upper_bound_exclusive: int | None = None


PRESETS = {
    "10k-small-fields": Preset(
        name="10k-small-fields",
        entry_count=10_000,
        login_count=8_000,
        secure_note_count=2_000,
        custom_field_min=0,
        custom_field_max=4,
        default_seed=10_003,
    ),
    "5k-wide-fields": Preset(
        name="5k-wide-fields",
        entry_count=5_000,
        login_count=4_000,
        secure_note_count=1_000,
        custom_field_min=10,
        custom_field_max=600,
        default_seed=5_003,
        low_field_target_count=4_500,
        low_field_upper_bound_exclusive=30,
    ),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create deterministic KeePass test databases for Keyguard.",
    )
    parser.add_argument(
        "--preset",
        required=True,
        choices=sorted(PRESETS),
        help="Dataset preset to generate.",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Path to the output .kdbx file.",
    )
    parser.add_argument(
        "--password",
        default=DEFAULT_PASSWORD,
        help=f"Master password for the database. Defaults to {DEFAULT_PASSWORD!r}.",
    )
    parser.add_argument(
        "--seed",
        type=int,
        help="Override the preset's deterministic seed.",
    )
    parser.add_argument(
        "--overwrite",
        action="store_true",
        help="Overwrite the output file if it already exists.",
    )
    return parser.parse_args()


def load_pykeepass():
    try:
        from pykeepass import PyKeePass, create_database
    except ModuleNotFoundError as exc:
        raise SystemExit(
            "pykeepass is not installed. Install it with "
            "`python3 -m pip install pykeepass`."
        ) from exc

    return PyKeePass, create_database


def build_entry_types(preset: Preset, rng: random.Random) -> list[str]:
    entry_types = (["login"] * preset.login_count) + (
        ["secure_note"] * preset.secure_note_count
    )
    rng.shuffle(entry_types)
    return entry_types


def build_title(preset: Preset, entry_type: str, index: int) -> str:
    kind = "login" if entry_type == "login" else "note"
    return f"{preset.name}-{kind}-{index:05d}"


def build_username(rng: random.Random, index: int) -> str:
    domains = (
        "example.test",
        "mail.test",
        "acme.test",
        "demo.test",
        "internal.test",
    )
    domain = domains[(index + rng.randrange(len(domains))) % len(domains)]
    return f"user{index:05d}@{domain}"


def build_password(rng: random.Random, index: int) -> str:
    alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    prefix = "".join(rng.choice(alphabet) for _ in range(12))
    suffix = f"{index:05d}"
    return f"{prefix}!{suffix}"


def build_notes(entry_type: str, index: int, rng: random.Random, force: bool) -> str | None:
    if entry_type == "secure_note":
        return (
            f"Secure note payload for entry {index:05d}.\n"
            f"Reference code: NOTE-{(index * 17) % 10000:04d}."
        )

    if force or rng.random() < 0.35:
        return (
            f"Login notes for entry {index:05d}.\n"
            f"Generated hint: recovery-{(index * 19) % 1000:03d}."
        )

    return None


def build_uri_count(rng: random.Random, force_three: bool) -> int:
    if force_three:
        return 3
    return rng.randint(0, 3)


def build_uri(index: int, ordinal: int, rng: random.Random) -> str:
    hosts = (
        "vault.example.test",
        "accounts.demo.test",
        "services.acme.test",
        "portal.internal.test",
        "login.mail.test",
    )
    host = hosts[(index + ordinal + rng.randrange(len(hosts))) % len(hosts)]
    path = f"/app/{index % 97}/{ordinal}"
    query = f"?view={(index + ordinal) % 11}"
    return f"https://{host}{path}{query}"


def build_custom_field_counts(preset: Preset, rng: random.Random) -> list[int]:
    if preset.low_field_target_count is None:
        counts = [
            rng.randint(preset.custom_field_min, preset.custom_field_max)
            for _ in range(preset.entry_count)
        ]
        counts[0] = preset.custom_field_max
        return counts

    low_field_upper_bound_exclusive = preset.low_field_upper_bound_exclusive
    if low_field_upper_bound_exclusive is None:
        raise ValueError("Preset is missing low-field bounds.")

    low_bucket = [
        rng.randint(preset.custom_field_min, low_field_upper_bound_exclusive - 1)
        for _ in range(preset.low_field_target_count)
    ]
    high_bucket = [
        rng.randint(low_field_upper_bound_exclusive, preset.custom_field_max)
        for _ in range(preset.entry_count - preset.low_field_target_count)
    ]
    high_bucket[0] = max(200, min(321, preset.custom_field_max))

    counts = low_bucket + high_bucket
    rng.shuffle(counts)
    return counts


def build_custom_field_value(index: int, field_index: int, rng: random.Random) -> str:
    words = (
        "alpha",
        "bravo",
        "charlie",
        "delta",
        "echo",
        "foxtrot",
        "gamma",
        "hotel",
    )
    word = words[(index + field_index + rng.randrange(len(words))) % len(words)]
    return f"{word}-{index:05d}-{field_index:03d}"


def remove_string_field(entry, key: str) -> None:
    field = entry._xpath(f'String/Key[text()="{key}"]/..', first=True)  # noqa: SLF001
    if field is not None:
        entry._element.remove(field)  # noqa: SLF001


def append_string_field(entry, key: str, value: str, protected: bool | None = None) -> None:
    from lxml.builder import E

    if protected is None:
        entry._element.append(E.String(E.Key(key), E.Value(value)))  # noqa: SLF001
        return

    entry._element.append(  # noqa: SLF001
        E.String(E.Key(key), E.Value(value, Protected=str(protected))),
    )


def apply_stable_metadata(element, stable_uuid: uuid.UUID, timestamp: datetime) -> None:
    element.uuid = stable_uuid
    element.ctime = timestamp
    element.mtime = timestamp
    element.atime = timestamp
    element.expiry_time = timestamp
    element._set_times_property("LocationChanged", timestamp)  # noqa: SLF001


def add_entry_type_marker(entry, entry_type: str) -> None:
    value = "Login" if entry_type == "login" else "Note"
    append_string_field(entry, KEYGUARD_ENTRY_TYPE_KEY, value)


def add_uris(entry, uri_count: int, index: int, rng: random.Random) -> None:
    if uri_count <= 0:
        return

    append_string_field(entry, "URL", build_uri(index=index, ordinal=0, rng=rng))
    for ordinal in range(1, uri_count):
        append_string_field(
            entry,
            f"{URI_FIELD_PREFIX}{ordinal}",
            build_uri(index=index, ordinal=ordinal, rng=rng),
        )


def add_custom_fields(entry, custom_field_count: int, index: int, rng: random.Random) -> None:
    for field_index in range(custom_field_count):
        key = f"field_{field_index:03d}"
        value = build_custom_field_value(index=index, field_index=field_index, rng=rng)
        append_string_field(entry, key, value)


def build_group_uuid(preset: Preset) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, f"keyguard/{preset.name}/group")


def build_entry_uuid(preset: Preset, entry_type: str, index: int) -> uuid.UUID:
    return uuid.uuid5(
        uuid.NAMESPACE_URL,
        f"keyguard/{preset.name}/{entry_type}/{index}",
    )


def build_timestamp(index: int) -> datetime:
    return BASE_TIME + timedelta(minutes=index)


def create_entries(kp, preset: Preset, rng: random.Random) -> None:
    group = kp.add_group(kp.root_group, preset.name)
    apply_stable_metadata(
        element=group,
        stable_uuid=build_group_uuid(preset),
        timestamp=build_timestamp(0),
    )
    entry_types = build_entry_types(preset, rng)
    custom_field_counts = build_custom_field_counts(preset, rng)
    forced_login_profile_applied = False

    for index, entry_type in enumerate(entry_types):
        force_login_profile = entry_type == "login" and not forced_login_profile_applied
        title = build_title(preset=preset, entry_type=entry_type, index=index)
        notes = build_notes(
            entry_type=entry_type,
            index=index,
            rng=rng,
            force=index == 0 or force_login_profile,
        )
        custom_field_count = custom_field_counts[index]

        if entry_type == "login":
            entry = kp.add_entry(
                group,
                title=title,
                username=build_username(rng=rng, index=index),
                password=build_password(rng=rng, index=index),
                notes=notes,
            )
            add_uris(
                entry=entry,
                uri_count=build_uri_count(rng=rng, force_three=force_login_profile),
                index=index,
                rng=rng,
            )
            forced_login_profile_applied = True
        else:
            entry = kp.add_entry(
                group,
                title=title,
                username="",
                password="",
                notes=notes,
            )
            remove_string_field(entry, "UserName")
            remove_string_field(entry, "Password")

        add_entry_type_marker(entry, entry_type=entry_type)
        add_custom_fields(
            entry=entry,
            custom_field_count=custom_field_count,
            index=index,
            rng=rng,
        )
        apply_stable_metadata(
            element=entry,
            stable_uuid=build_entry_uuid(
                preset=preset,
                entry_type=entry_type,
                index=index,
            ),
            timestamp=build_timestamp(index + 1),
        )


def get_string_fields(entry) -> dict[str, str]:
    fields: dict[str, str] = {}
    for string_element in entry._element.findall("String"):  # noqa: SLF001
        key_element = string_element.find("Key")
        value_element = string_element.find("Value")
        if key_element is None or value_element is None or key_element.text is None:
            continue
        fields[key_element.text] = value_element.text or ""
    return fields


def count_entry_uris(fields: dict[str, str]) -> int:
    uri_count = 1 if fields.get("URL") else 0
    for key in fields:
        if key.startswith(URI_FIELD_PREFIX):
            ordinal = key.removeprefix(URI_FIELD_PREFIX)
            if ordinal.isdigit():
                uri_count += 1
    return uri_count


def count_generated_custom_fields(fields: dict[str, str]) -> int:
    return sum(
        1
        for key in fields
        if key != KEYGUARD_ENTRY_TYPE_KEY and key.startswith("field_")
    )


def validate_database(path: pathlib.Path, password: str, preset: Preset) -> None:
    PyKeePass, _ = load_pykeepass()
    kp = PyKeePass(str(path), password=password)
    group = kp.find_groups(name=preset.name, first=True)
    if group is None:
        raise ValueError(f"Could not find top-level group {preset.name!r}.")

    entries = kp.find_entries(group=group, recursive=False)
    if len(entries) != preset.entry_count:
        raise ValueError(
            f"Expected {preset.entry_count} entries, found {len(entries)}."
        )

    login_count = 0
    secure_note_count = 0
    entries_with_notes = 0
    login_with_three_uris = False
    wide_entry_seen = False
    low_field_entry_count = 0

    for entry in entries:
        fields = get_string_fields(entry)
        entry_type = fields.get(KEYGUARD_ENTRY_TYPE_KEY)
        notes = fields.get("Notes", "")
        if notes:
            entries_with_notes += 1

        generated_custom_fields = count_generated_custom_fields(fields)
        if not (preset.custom_field_min <= generated_custom_fields <= preset.custom_field_max):
            raise ValueError(
                f"Entry {fields.get('Title')!r} has {generated_custom_fields} generated custom "
                f"fields; expected between {preset.custom_field_min} and "
                f"{preset.custom_field_max}."
            )

        if preset.name == "5k-wide-fields" and generated_custom_fields >= 200:
            wide_entry_seen = True
        if (
            preset.low_field_upper_bound_exclusive is not None and
            generated_custom_fields < preset.low_field_upper_bound_exclusive
        ):
            low_field_entry_count += 1

        if entry_type == "Login":
            login_count += 1
            uri_count = count_entry_uris(fields)
            if not (0 <= uri_count <= 3):
                raise ValueError(
                    f"Entry {fields.get('Title')!r} has {uri_count} URIs; expected 0 to 3."
                )
            if uri_count == 3:
                login_with_three_uris = True
        elif entry_type == "Note":
            secure_note_count += 1
            if "UserName" in fields:
                raise ValueError(
                    f"Secure note {fields.get('Title')!r} should not have a username."
                )
            if "Password" in fields:
                raise ValueError(
                    f"Secure note {fields.get('Title')!r} should not have a password."
                )
            if "URL" in fields:
                raise ValueError(
                    f"Secure note {fields.get('Title')!r} should not have a URL."
                )
            if not notes:
                raise ValueError(f"Secure note {fields.get('Title')!r} must have notes.")
        else:
            raise ValueError(
                f"Entry {fields.get('Title')!r} has unsupported type marker {entry_type!r}."
            )

    if login_count != preset.login_count:
        raise ValueError(
            f"Expected {preset.login_count} login entries, found {login_count}."
        )
    if secure_note_count != preset.secure_note_count:
        raise ValueError(
            f"Expected {preset.secure_note_count} secure notes, found {secure_note_count}."
        )
    if entries_with_notes <= 0:
        raise ValueError("Expected at least one entry with notes.")
    if not login_with_three_uris:
        raise ValueError("Expected at least one login entry with 3 URIs.")
    if preset.name == "5k-wide-fields" and not wide_entry_seen:
        raise ValueError(
            "Expected at least one wide entry with hundreds of custom fields."
        )
    if (
        preset.low_field_target_count is not None and
        low_field_entry_count != preset.low_field_target_count
    ):
        raise ValueError(
            f"Expected {preset.low_field_target_count} entries below "
            f"{preset.low_field_upper_bound_exclusive} custom fields, found "
            f"{low_field_entry_count}."
        )


def generate_database(
    output_path: pathlib.Path,
    password: str,
    preset: Preset,
    seed: int,
    overwrite: bool,
) -> None:
    PyKeePass, create_database = load_pykeepass()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if output_path.exists() and not overwrite:
        raise FileExistsError(
            f"Output file already exists: {output_path}. Pass --overwrite to replace it."
        )

    temp_file = tempfile.NamedTemporaryFile(
        prefix=f"{preset.name}-",
        suffix=".kdbx",
        dir=output_path.parent,
        delete=False,
    )
    temp_file.close()
    temp_path = pathlib.Path(temp_file.name)

    try:
        rng = random.Random(seed)
        kp = create_database(str(temp_path), password=password)
        kp.database_name = f"Keyguard test dataset: {preset.name}"
        kp.database_description = (
            f"Preset {preset.name} generated by scripts/keepass/create_test_db.py."
        )
        kp.default_username = "test-user"
        create_entries(kp=kp, preset=preset, rng=rng)
        kp.save()

        # Re-open the database to verify the resulting file shape, not just the
        # in-memory entry objects.
        _ = PyKeePass(str(temp_path), password=password)
        validate_database(path=temp_path, password=password, preset=preset)

        if output_path.exists():
            output_path.unlink()
        os.replace(temp_path, output_path)
    except Exception:
        if temp_path.exists():
            temp_path.unlink()
        raise


def main() -> None:
    args = parse_args()
    preset = PRESETS[args.preset]
    output_path = pathlib.Path(args.output).expanduser().resolve()
    seed = preset.default_seed if args.seed is None else args.seed

    generate_database(
        output_path=output_path,
        password=args.password,
        preset=preset,
        seed=seed,
        overwrite=args.overwrite,
    )

    print(
        f"Created {preset.name} at {output_path} "
        f"with password {args.password!r} and seed {seed}."
    )


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
