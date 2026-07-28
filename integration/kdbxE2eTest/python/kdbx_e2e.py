#!/usr/bin/env python3

import argparse
import base64
import difflib
import json
import pathlib
import re
import shutil
import sys
import uuid
from datetime import datetime, timedelta, timezone
from importlib.metadata import version as package_version

from lxml import etree


EXPECTED_PYKEEPASS_VERSION = "4.1.1.post1"
SEED_PASSWORD = "1"
EXTENSION_NAMESPACE = "urn:keyguard:kdbx-e2e"
ATTRIBUTE_NAMESPACE = "urn:keyguard:kdbx-e2e:attributes"
BASE_TIME = datetime(2024, 1, 2, 3, 4, 5, tzinfo=timezone.utc)
KEYFILE_BYTES = bytes(range(32))

ROOT_UUID = uuid.UUID("10000000-0000-0000-0000-000000000001")
GENERAL_UUID = uuid.UUID("10000000-0000-0000-0000-000000000002")
NESTED_UUID = uuid.UUID("10000000-0000-0000-0000-000000000003")
EMPTY_UUID = uuid.UUID("10000000-0000-0000-0000-000000000004")
RECYCLE_UUID = uuid.UUID("10000000-0000-0000-0000-000000000005")
TEMPLATES_UUID = uuid.UUID("10000000-0000-0000-0000-000000000006")
MAIN_ENTRY_UUID = uuid.UUID("20000000-0000-0000-0000-000000000001")
MINIMAL_ENTRY_UUID = uuid.UUID("20000000-0000-0000-0000-000000000002")
TEMPLATE_ENTRY_UUID = uuid.UUID("20000000-0000-0000-0000-000000000003")
CUSTOM_ICON_UUID = uuid.UUID("30000000-0000-0000-0000-000000000001")
DELETED_UUID = uuid.UUID("40000000-0000-0000-0000-000000000001")


def load_pykeepass():
    try:
        from pykeepass import PyKeePass
    except ModuleNotFoundError as error:
        raise RuntimeError("pykeepass is not installed") from error
    return PyKeePass


def child(element, tag):
    item = element.find(tag)
    if item is None:
        item = etree.SubElement(element, tag)
    return item


def set_text(element, tag, value):
    item = child(element, tag)
    item.text = None if value is None else str(value)
    return item


def remove_child(element, tag):
    item = element.find(tag)
    if item is not None:
        element.remove(item)


def replace_container(element, tag):
    old = element.find(tag)
    new = etree.Element(tag)
    if old is None:
        element.append(new)
    else:
        element.replace(old, new)
    return new


def encode_uuid(value):
    return base64.b64encode(value.bytes).decode("ascii")


def decode_uuid(value):
    if not value:
        return None
    return str(uuid.UUID(bytes=base64.b64decode(value)))


def apply_times(kp, item, offset, expires, usage_count):
    timestamp = BASE_TIME + timedelta(minutes=offset)
    item.ctime = timestamp
    item.mtime = timestamp + timedelta(seconds=1)
    item.atime = timestamp + timedelta(seconds=2)
    item.expiry_time = timestamp + timedelta(days=30)
    item.expires = expires
    item._set_times_property("LocationChanged", timestamp + timedelta(seconds=3))
    set_text(item._element.find("Times"), "UsageCount", usage_count)


def set_custom_data(element, items):
    container = replace_container(element, "CustomData")
    for key, value in items:
        node = etree.SubElement(container, "Item")
        set_text(node, "Key", key)
        set_text(node, "Value", value)


def set_group_options(
    group,
    *,
    expanded,
    default_sequence,
    enable_auto_type,
    enable_searching,
    last_visible_entry=None,
):
    element = group._element
    set_text(element, "IsExpanded", str(expanded))
    set_text(element, "DefaultAutoTypeSequence", default_sequence)
    set_text(element, "EnableAutoType", enable_auto_type)
    set_text(element, "EnableSearching", enable_searching)
    if last_visible_entry is None:
        remove_child(element, "LastTopVisibleEntry")
    else:
        set_text(element, "LastTopVisibleEntry", encode_uuid(last_visible_entry))
    remove_child(element, "PreviousParentGroup")
    remove_child(element, "Tags")


def set_custom_icon_reference(element, value):
    set_text(element, "CustomIconUUID", encode_uuid(value))


def set_auto_type(entry):
    auto_type = replace_container(entry._element, "AutoType")
    set_text(auto_type, "Enabled", "True")
    set_text(auto_type, "DataTransferObfuscation", "1")
    set_text(auto_type, "DefaultSequence", "{USERNAME}{TAB}{PASSWORD}{ENTER}")
    for window, sequence in (
        ("Login — *", "{USERNAME}{TAB}{PASSWORD}{ENTER}"),
        ("Admin & Console", "{PASSWORD}{ENTER}"),
    ):
        association = etree.SubElement(auto_type, "Association")
        set_text(association, "Window", window)
        set_text(association, "KeystrokeSequence", sequence)


def remove_string_field(entry, key):
    for node in list(entry._element.findall("String")):
        key_element = node.find("Key")
        if key_element is not None and key_element.text == key:
            entry._element.remove(node)


def build_extension(label, include_protected_value=False):
    extension = etree.Element(
        f"{{{EXTENSION_NAMESPACE}}}Envelope",
        nsmap={"e2e": EXTENSION_NAMESPACE, "attr": ATTRIBUTE_NAMESPACE},
    )
    extension.set(f"{{{ATTRIBUTE_NAMESPACE}}}label", label)
    extension.text = f"{label}-prefix"
    extension.append(etree.Comment(f"{label}-comment"))
    plain = etree.SubElement(extension, f"{{{EXTENSION_NAMESPACE}}}Child")
    plain.text = f"{label}-child"
    if include_protected_value:
        protected = etree.SubElement(extension, "Value")
        protected.set("Protected", "True")
        protected.text = f"{label}-secret"
    extension.append(etree.ProcessingInstruction("e2e", f"label='{label}'"))
    return extension


def reset_database(kp):
    for entry in list(kp.entries):
        kp.delete_entry(entry)
    for group in list(kp.root_group.subgroups):
        kp.delete_group(group)
    for binary_id in reversed(range(len(kp.binaries))):
        kp.delete_binary(binary_id)

    meta = kp._xpath("/KeePassFile/Meta", first=True)
    replace_container(meta, "CustomIcons")
    replace_container(meta, "CustomData")
    root = kp._xpath("/KeePassFile/Root", first=True)
    replace_container(root, "DeletedObjects")


def configure_meta(kp):
    meta = kp._xpath("/KeePassFile/Meta", first=True)
    values = {
        "Generator": "Keyguard pykeepass E2E",
        "SettingsChanged": kp._encode_time(BASE_TIME),
        "DatabaseName": "Interoperability & Unicode 🔐",
        "DatabaseNameChanged": kp._encode_time(BASE_TIME + timedelta(seconds=1)),
        "DatabaseDescription": "Generated by pykeepass\nwith <XML> & Unicode Ω.",
        "DatabaseDescriptionChanged": kp._encode_time(BASE_TIME + timedelta(seconds=2)),
        "DefaultUserName": "default@example.test",
        "DefaultUserNameChanged": kp._encode_time(BASE_TIME + timedelta(seconds=3)),
        "MaintenanceHistoryDays": "123",
        "Color": "#12AB34",
        "MasterKeyChanged": kp._encode_time(BASE_TIME + timedelta(seconds=4)),
        "MasterKeyChangeRec": "45",
        "MasterKeyChangeForce": "90",
        "RecycleBinEnabled": "True",
        "RecycleBinUUID": encode_uuid(RECYCLE_UUID),
        "RecycleBinChanged": kp._encode_time(BASE_TIME + timedelta(seconds=5)),
        "EntryTemplatesGroup": encode_uuid(TEMPLATES_UUID),
        "EntryTemplatesGroupChanged": kp._encode_time(BASE_TIME + timedelta(seconds=6)),
        "HistoryMaxItems": "17",
        "HistoryMaxSize": "1048576",
        "LastSelectedGroup": encode_uuid(GENERAL_UUID),
        "LastTopVisibleGroup": encode_uuid(NESTED_UUID),
    }
    for tag, value in values.items():
        if tag == "SettingsChanged" and kp.version < (4, 0):
            remove_child(meta, tag)
        else:
            set_text(meta, tag, value)

    protection = replace_container(meta, "MemoryProtection")
    for tag, enabled in (
        ("ProtectTitle", False),
        ("ProtectUserName", True),
        ("ProtectPassword", True),
        ("ProtectURL", False),
        ("ProtectNotes", True),
    ):
        set_text(protection, tag, str(enabled))

    custom_icons = replace_container(meta, "CustomIcons")
    icon = etree.SubElement(custom_icons, "Icon")
    set_text(icon, "UUID", encode_uuid(CUSTOM_ICON_UUID))
    set_text(icon, "Data", base64.b64encode(bytes(range(1, 33))).decode("ascii"))

    set_custom_data(
        meta,
        (
            ("meta-key", "meta value & <xml>"),
            ("unicode-key-🔑", "Привіт світе"),
        ),
    )


def configure_root_group(kp):
    root = kp.root_group
    root.uuid = ROOT_UUID
    root.name = "Root & Vault 🔐"
    root.notes = "Root notes\nsecond line"
    root.icon = "48"
    remove_child(root._element, "CustomIconUUID")
    apply_times(kp, root, offset=10, expires=False, usage_count=1)
    set_group_options(
        root,
        expanded=True,
        default_sequence="{USERNAME}{TAB}{PASSWORD}{ENTER}",
        enable_auto_type="True",
        enable_searching="True",
    )
    set_custom_data(root._element, (("root-key", "root-value"),))
    return root


def add_groups(kp, root):
    general = kp.add_group(root, "General & Unicode Ω", icon="1", notes="General notes\nline 2")
    general.uuid = GENERAL_UUID
    apply_times(kp, general, offset=20, expires=False, usage_count=2)
    set_custom_icon_reference(general._element, CUSTOM_ICON_UUID)
    set_group_options(
        general,
        expanded=False,
        default_sequence="{PASSWORD}{ENTER}",
        enable_auto_type="Null",
        enable_searching="True",
        last_visible_entry=MAIN_ENTRY_UUID,
    )
    set_custom_data(general._element, (("group-key", "group-value"),))

    nested = kp.add_group(general, "Nested <Group>", icon="49", notes="Nested group")
    nested.uuid = NESTED_UUID
    apply_times(kp, nested, offset=21, expires=True, usage_count=3)
    set_group_options(
        nested,
        expanded=True,
        default_sequence=None,
        enable_auto_type="False",
        enable_searching="False",
    )

    empty = kp.add_group(root, "Empty Group", icon="48", notes="")
    empty.uuid = EMPTY_UUID
    apply_times(kp, empty, offset=22, expires=False, usage_count=0)
    set_group_options(
        empty,
        expanded=False,
        default_sequence=None,
        enable_auto_type="Null",
        enable_searching="Null",
    )

    recycle = kp.add_group(root, "Recycle Bin", icon="43", notes="Deleted items")
    recycle.uuid = RECYCLE_UUID
    apply_times(kp, recycle, offset=23, expires=False, usage_count=0)
    set_group_options(
        recycle,
        expanded=False,
        default_sequence=None,
        enable_auto_type="False",
        enable_searching="False",
    )

    templates = kp.add_group(root, "Templates", icon="48", notes="Entry templates")
    templates.uuid = TEMPLATES_UUID
    apply_times(kp, templates, offset=24, expires=False, usage_count=0)
    set_group_options(
        templates,
        expanded=False,
        default_sequence=None,
        enable_auto_type="Null",
        enable_searching="False",
    )
    return general, nested, empty, recycle, templates


def add_binaries(kp):
    first = b"attachment-one\x00\xff\n" + bytes(range(16))
    second = "Unicode attachment 🔑".encode("utf-8")
    if kp.version >= (4, 0):
        first_id = kp.add_binary(first, protected=True)
        second_id = kp.add_binary(second, protected=False)
    else:
        first_id = kp.add_binary(first, compressed=True)
        second_id = kp.add_binary(second, compressed=False)
    return first_id, second_id


def add_entries(kp, general, templates, binary_ids):
    main = kp.add_entry(
        general,
        title="Historical title",
        username="user+Ω@example.test",
        password="old secret",
        url="https://example.test/login?a=1&b=2",
        notes="Old notes",
        tags=["alpha", "two words", "unicode-🔑"],
        icon="3",
    )
    main.uuid = MAIN_ENTRY_UUID
    apply_times(kp, main, offset=30, expires=True, usage_count=42)
    set_text(main._element, "ForegroundColor", "#FF1122")
    set_text(main._element, "BackgroundColor", "#001122")
    set_text(main._element, "OverrideURL", 'cmd://browser "{URL}"')
    set_custom_icon_reference(main._element, CUSTOM_ICON_UUID)
    main.set_custom_property("Custom Plain", "plain & <value>", protect=False)
    main.set_custom_property("Custom Empty", "", protect=False)
    main.set_custom_property("Custom Whitespace", "  leading and trailing  ", protect=False)
    main.set_custom_property("Custom Protected", "protected Ω value", protect=True)
    set_custom_data(main._element, (("entry-key", "entry-value"),))
    set_auto_type(main)
    main.add_attachment(binary_ids[0], "binary-<&>.dat")
    main.add_attachment(binary_ids[1], "unicode-🔑.txt")
    main.save_history()

    main.title = "Current & <Title> 🔑"
    main.password = "current secret <&> 🗝"
    main.notes = "Line 1\nLine 2\tTabbed"
    apply_times(kp, main, offset=31, expires=True, usage_count=43)

    minimal = kp.add_entry(general, title="Minimal", username="", password="")
    minimal.uuid = MINIMAL_ENTRY_UUID
    apply_times(kp, minimal, offset=32, expires=False, usage_count=0)
    for key in ("UserName", "Password", "URL", "Notes"):
        remove_string_field(minimal, key)
    minimal.set_custom_property("Only Custom", "value", protect=False)
    set_auto_type(minimal)
    minimal.autotype_enabled = False

    template = kp.add_entry(
        templates,
        title="Template Entry",
        username="template-user",
        password="template-secret",
        notes="Template notes",
        icon="0",
    )
    template.uuid = TEMPLATE_ENTRY_UUID
    apply_times(kp, template, offset=33, expires=False, usage_count=4)
    set_auto_type(template)
    template.autotype_enabled = False
    return main, minimal, template


def add_deleted_object(kp):
    root = kp._xpath("/KeePassFile/Root", first=True)
    deleted = replace_container(root, "DeletedObjects")
    item = etree.SubElement(deleted, "DeletedObject")
    set_text(item, "UUID", encode_uuid(DELETED_UUID))
    set_text(item, "DeletionTime", kp._encode_time(BASE_TIME + timedelta(days=1)))


def add_extensions(kp, main_entry):
    document = kp.tree.getroot()
    meta = kp._xpath("/KeePassFile/Meta", first=True)
    root = kp._xpath("/KeePassFile/Root", first=True)
    document.append(build_extension("document", include_protected_value=True))
    meta.append(build_extension("meta"))
    root.append(build_extension("root"))
    kp.root_group._element.append(build_extension("group"))
    main_entry._element.append(build_extension("entry", include_protected_value=True))


def build_corpus(kp):
    reset_database(kp)
    configure_meta(kp)
    root = configure_root_group(kp)
    general, _, _, _, templates = add_groups(kp, root)
    binary_ids = add_binaries(kp)
    main, _, _ = add_entries(kp, general, templates, binary_ids)
    add_deleted_object(kp)
    add_extensions(kp, main)


def element_text(element, tag, default=None):
    item = element.find(tag)
    if item is None or item.text is None:
        return default
    return item.text


def bool_text(element, tag, default=False):
    value = element_text(element, tag)
    return default if value is None else value.strip().lower() == "true"


def int_text(element, tag, default=0):
    value = element_text(element, tag)
    return default if value is None or not value.strip() else int(value)


def instant_text(kp, element, tag):
    value = element_text(element, tag)
    if not value:
        return None
    instant = kp._decode_time(value)
    return (
        instant.astimezone(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def uuid_text(element, tag):
    return decode_uuid(element_text(element, tag))


def canonical_times(kp, element):
    times = element.find("Times")
    if times is None:
        return None
    return {
        "creationTime": instant_text(kp, times, "CreationTime"),
        "lastAccessTime": instant_text(kp, times, "LastAccessTime"),
        "lastModificationTime": instant_text(kp, times, "LastModificationTime"),
        "locationChanged": instant_text(kp, times, "LocationChanged"),
        "expiryTime": instant_text(kp, times, "ExpiryTime"),
        "expires": bool_text(times, "Expires"),
        "usageCount": int_text(times, "UsageCount"),
    }


def canonical_custom_data(kp, element):
    container = element.find("CustomData")
    if container is None:
        return []
    result = []
    for item in container.findall("Item"):
        key = element_text(item, "Key")
        value = element_text(item, "Value")
        if key is not None and value is not None:
            result.append(
                {
                    "key": key,
                    "value": value,
                    "lastModified": instant_text(kp, item, "LastModificationTime"),
                }
            )
    return result


def split_qname(name):
    if name.startswith("{"):
        namespace, local_name = name[1:].split("}", 1)
        return local_name, namespace
    return name, ""


def is_protected(element):
    return any(
        element.attrib.get(name, "False").lower() == "true"
        for name in ("Protected", "ProtectInMemory")
    )


def canonical_extension(element):
    local_name, namespace = split_qname(element.tag)
    attributes = []
    for raw_name, value in element.attrib.items():
        attribute_name, attribute_namespace = split_qname(raw_name)
        if attribute_namespace == "" and attribute_name in ("Protected", "ProtectInMemory"):
            continue
        attributes.append(
            {
                "name": {"localName": attribute_name, "namespaceUri": attribute_namespace},
                "value": value,
            }
        )

    content = []
    protected = is_protected(element)
    if element.text is not None:
        content.append(
            {
                "kind": "text",
                "value": element.text,
                "protected": protected,
            }
        )
    for item in element:
        if isinstance(item, etree._Comment):
            content.append({"kind": "comment", "value": item.text or ""})
        elif isinstance(item, etree._ProcessingInstruction):
            content.append(
                {
                    "kind": "processingInstruction",
                    "target": item.target,
                    "data": item.text or "",
                }
            )
        else:
            content.append({"kind": "element", "value": canonical_extension(item)})
        if item.tail is not None:
            content.append(
                {
                    "kind": "text",
                    "value": item.tail,
                    "protected": False,
                }
            )
    return {
        "name": {"localName": local_name, "namespaceUri": namespace},
        "attributes": attributes,
        "content": content,
    }


def canonical_extensions(element):
    return [
        canonical_extension(item)
        for item in element
        if isinstance(item.tag, str) and split_qname(item.tag)[1] == EXTENSION_NAMESPACE
    ]


def canonical_auto_type(element):
    auto_type = element.find("AutoType")
    if auto_type is None:
        return None
    associations = []
    for item in auto_type.findall("Association"):
        window = element_text(item, "Window")
        sequence = element_text(item, "KeystrokeSequence")
        if window is not None and sequence is not None:
            associations.append({"window": window, "sequence": sequence})
    return {
        "enabled": bool_text(auto_type, "Enabled"),
        "obfuscation": int_text(auto_type, "DataTransferObfuscation"),
        "defaultSequence": element_text(auto_type, "DefaultSequence"),
        "associations": associations,
    }


def canonical_fields(element):
    result = []
    for item in element.findall("String"):
        key = element_text(item, "Key")
        value_element = item.find("Value")
        if key is None or value_element is None:
            continue
        result.append(
            {
                "key": key,
                "value": value_element.text or "",
                "protected": is_protected(value_element),
            }
        )
    return result


def canonical_binary_references(element):
    result = []
    for item in element.findall("Binary"):
        key = element_text(item, "Key")
        value = item.find("Value")
        if key is None or value is None or value.attrib.get("Ref") is None:
            continue
        result.append({"name": key, "binaryIndex": int(value.attrib["Ref"])})
    return result


def canonical_tags(element):
    value = element_text(element, "Tags")
    if not value:
        return []
    return [part for part in re.split(r"\s*[;,:]\s*", value) if part]


def canonical_entry(kp, element):
    history = element.find("History")
    return {
        "uuid": uuid_text(element, "UUID"),
        "icon": int_text(element, "IconID"),
        "customIconUuid": uuid_text(element, "CustomIconUUID"),
        "foregroundColor": element_text(element, "ForegroundColor"),
        "backgroundColor": element_text(element, "BackgroundColor"),
        "overrideUrl": element_text(element, "OverrideURL", ""),
        "times": canonical_times(kp, element),
        "autoType": canonical_auto_type(element),
        "fields": canonical_fields(element),
        "tags": canonical_tags(element),
        "binaries": canonical_binary_references(element),
        "history": [] if history is None else [canonical_entry(kp, item) for item in history.findall("Entry")],
        "customData": canonical_custom_data(kp, element),
        "previousParentGroup": uuid_text(element, "PreviousParentGroup"),
        "qualityCheck": bool_text(element, "QualityCheck", default=True),
        "extensions": canonical_extensions(element),
    }


def group_override(value):
    if value is None or value.strip().lower() == "null":
        return "Inherit"
    if value.strip().lower() == "true":
        return "Enabled"
    if value.strip().lower() == "false":
        return "Disabled"
    raise ValueError(f"Unsupported group override {value!r}")


def canonical_group(kp, element):
    return {
        "uuid": uuid_text(element, "UUID"),
        "name": element_text(element, "Name", ""),
        "notes": element_text(element, "Notes", ""),
        "icon": int_text(element, "IconID", 48),
        "customIconUuid": uuid_text(element, "CustomIconUUID"),
        "times": canonical_times(kp, element),
        "expanded": bool_text(element, "IsExpanded", default=True),
        "defaultAutoTypeSequence": element_text(element, "DefaultAutoTypeSequence"),
        "enableAutoType": group_override(element_text(element, "EnableAutoType")),
        "enableSearching": group_override(element_text(element, "EnableSearching")),
        "lastTopVisibleEntry": uuid_text(element, "LastTopVisibleEntry"),
        "previousParentGroup": uuid_text(element, "PreviousParentGroup"),
        "tags": canonical_tags(element),
        "customData": canonical_custom_data(kp, element),
        "groups": [canonical_group(kp, item) for item in element.findall("Group")],
        "entries": [canonical_entry(kp, item) for item in element.findall("Entry")],
        "extensions": canonical_extensions(element),
    }


def canonical_custom_icons(kp, meta):
    container = meta.find("CustomIcons")
    if container is None:
        return []
    result = []
    for item in container.findall("Icon"):
        data = element_text(item, "Data")
        icon_uuid = uuid_text(item, "UUID")
        if data is None or icon_uuid is None:
            continue
        result.append(
            {
                "uuid": icon_uuid,
                "dataBase64": base64.b64encode(base64.b64decode(data)).decode("ascii"),
                "name": element_text(item, "Name"),
                "lastModified": instant_text(kp, item, "LastModificationTime"),
            }
        )
    return result


def canonical_meta(kp, meta):
    memory = meta.find("MemoryProtection")
    memory_protection = []
    if memory is not None:
        for name, tag in (
            ("Title", "ProtectTitle"),
            ("UserName", "ProtectUserName"),
            ("Password", "ProtectPassword"),
            ("Url", "ProtectURL"),
            ("Notes", "ProtectNotes"),
        ):
            if bool_text(memory, tag):
                memory_protection.append(name)
    return {
        "generator": element_text(meta, "Generator", "KeePass"),
        "settingsChanged": instant_text(kp, meta, "SettingsChanged"),
        "name": element_text(meta, "DatabaseName", ""),
        "nameChanged": instant_text(kp, meta, "DatabaseNameChanged"),
        "description": element_text(meta, "DatabaseDescription", ""),
        "descriptionChanged": instant_text(kp, meta, "DatabaseDescriptionChanged"),
        "defaultUser": element_text(meta, "DefaultUserName", ""),
        "defaultUserChanged": instant_text(kp, meta, "DefaultUserNameChanged"),
        "maintenanceHistoryDays": int_text(meta, "MaintenanceHistoryDays"),
        "color": element_text(meta, "Color"),
        "masterKeyChanged": instant_text(kp, meta, "MasterKeyChanged"),
        "masterKeyChangeRec": int_text(meta, "MasterKeyChangeRec", -1),
        "masterKeyChangeForce": int_text(meta, "MasterKeyChangeForce", -1),
        "recycleBinEnabled": bool_text(meta, "RecycleBinEnabled"),
        "recycleBinUuid": uuid_text(meta, "RecycleBinUUID"),
        "recycleBinChanged": instant_text(kp, meta, "RecycleBinChanged"),
        "entryTemplatesGroup": uuid_text(meta, "EntryTemplatesGroup"),
        "entryTemplatesGroupChanged": instant_text(kp, meta, "EntryTemplatesGroupChanged"),
        "historyMaxItems": int_text(meta, "HistoryMaxItems", -1),
        "historyMaxSize": int_text(meta, "HistoryMaxSize", -1),
        "lastSelectedGroup": uuid_text(meta, "LastSelectedGroup"),
        "lastTopVisibleGroup": uuid_text(meta, "LastTopVisibleGroup"),
        "memoryProtection": memory_protection,
        "customIcons": canonical_custom_icons(kp, meta),
        "customData": canonical_custom_data(kp, meta),
        "extensions": canonical_extensions(meta),
    }


def canonical_binaries(kp):
    if kp.version >= (4, 0):
        result = []
        for item in kp.payload.inner_header.binary:
            raw = bytes(item.data)
            result.append(
                {
                    "contentBase64": base64.b64encode(raw[1:]).decode("ascii"),
                    "storage": "plain",
                    "protected": bool(raw[0]),
                }
            )
        return result

    meta = kp._xpath("/KeePassFile/Meta", first=True)
    container = meta.find("Binaries")
    nodes = [] if container is None else sorted(
        container.findall("Binary"), key=lambda item: int(item.attrib["ID"])
    )
    return [
        {
            "contentBase64": base64.b64encode(kp.binaries[index]).decode("ascii"),
            "storage": "compressed" if node.attrib.get("Compressed", "False").lower() == "true" else "plain",
            "protected": False,
        }
        for index, node in enumerate(nodes)
    ]


def canonical_database(kp):
    document = kp.tree.getroot()
    meta = kp._xpath("/KeePassFile/Meta", first=True)
    root = kp._xpath("/KeePassFile/Root", first=True)
    group = root.find("Group")
    deleted_container = root.find("DeletedObjects")
    deleted_objects = []
    if deleted_container is not None:
        for item in deleted_container.findall("DeletedObject"):
            item_uuid = uuid_text(item, "UUID")
            deleted_at = instant_text(kp, item, "DeletionTime")
            if item_uuid is not None and deleted_at is not None:
                deleted_objects.append({"uuid": item_uuid, "deletionTime": deleted_at})

    compression = kp.kdbx.header.value.dynamic_header.compression_flags.data.compression
    return {
        "schemaVersion": 1,
        "format": {
            "version": f"{kp.version[0]}.{kp.version[1]}",
            "cipher": kp.encryption_algorithm,
            "kdf": "argon2d" if kp.kdf_algorithm == "argon2" else kp.kdf_algorithm,
            "compression": "gzip" if compression else "none",
        },
        "meta": canonical_meta(kp, meta),
        "root": canonical_group(kp, group),
        "deletedObjects": deleted_objects,
        "binaries": canonical_binaries(kp),
        "documentExtensions": canonical_extensions(document),
        "rootExtensions": canonical_extensions(root),
    }


def all_groups(group):
    yield group
    for child_group in group["groups"]:
        yield from all_groups(child_group)


def validate_corpus(manifest):
    groups = list(all_groups(manifest["root"]))
    entries = [entry for group in groups for entry in group["entries"]]
    main = next(entry for entry in entries if entry["uuid"] == str(MAIN_ENTRY_UUID))
    protected_fields = {item["key"] for item in main["fields"] if item["protected"]}
    if len(groups) != 6:
        raise ValueError(f"Expected 6 groups, found {len(groups)}")
    if len(entries) != 3:
        raise ValueError(f"Expected 3 current entries, found {len(entries)}")
    if len(manifest["binaries"]) != 2 or len(main["binaries"]) != 2:
        raise ValueError("Expected two global binaries and two main-entry attachments")
    if len(main["history"]) != 1:
        raise ValueError("Expected one history entry")
    if not {"Password", "Custom Protected"}.issubset(protected_fields):
        raise ValueError(f"Protected fields are incomplete: {sorted(protected_fields)}")
    if not manifest["documentExtensions"] or not main["extensions"]:
        raise ValueError("Expected document and entry XML extensions")
    document_extension = manifest["documentExtensions"][0]
    protected_extension_values = []
    for item in document_extension["content"]:
        if item["kind"] == "element":
            protected_extension_values.extend(
                content["value"]
                for content in item["value"]["content"]
                if content["kind"] == "text" and content["protected"]
            )
    if protected_extension_values != ["document-secret"]:
        raise ValueError("Protected document extension did not survive pykeepass save/reopen")


def write_manifest(path, manifest):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def manifest_diff(expected, actual, expected_label, actual_label):
    expected_json = json.dumps(expected, indent=2, ensure_ascii=False, sort_keys=True).splitlines()
    actual_json = json.dumps(actual, indent=2, ensure_ascii=False, sort_keys=True).splitlines()
    return "\n".join(
        difflib.unified_diff(
            expected_json,
            actual_json,
            fromfile=expected_label,
            tofile=actual_label,
            lineterm="",
        )
    )


def command_doctor(_args):
    load_pykeepass()
    actual_version = package_version("pykeepass")
    if actual_version != EXPECTED_PYKEEPASS_VERSION:
        raise RuntimeError(
            f"Expected pykeepass {EXPECTED_PYKEEPASS_VERSION}, found {actual_version}"
        )
    print(f"pykeepass {actual_version} is ready")


def command_generate(args):
    PyKeePass = load_pykeepass()
    args.database.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.seed, args.database)
    kp = PyKeePass(str(args.database), password=SEED_PASSWORD)
    build_corpus(kp)

    keyfile = None
    if args.keyfile is not None:
        args.keyfile.parent.mkdir(parents=True, exist_ok=True)
        args.keyfile.write_bytes(KEYFILE_BYTES)
        keyfile = str(args.keyfile)
    kp.password = args.password
    kp.keyfile = keyfile
    kp.credchange_date = BASE_TIME + timedelta(seconds=4)
    kp.save()

    reopened = PyKeePass(str(args.database), password=args.password, keyfile=keyfile)
    manifest = canonical_database(reopened)
    validate_corpus(manifest)
    write_manifest(args.manifest, manifest)
    print(
        f"Generated {args.database} as {manifest['format']['version']} "
        f"{manifest['format']['cipher']}/{manifest['format']['kdf']}"
    )


def command_verify(args):
    PyKeePass = load_pykeepass()
    keyfile = None if args.keyfile is None else str(args.keyfile)
    kp = PyKeePass(str(args.database), password=args.password, keyfile=keyfile)
    actual = canonical_database(kp)
    expected = json.loads(args.manifest.read_text(encoding="utf-8"))
    if actual != expected:
        raise AssertionError(
            "KDBX semantic manifest mismatch:\n"
            + manifest_diff(expected, actual, str(args.manifest), str(args.database))
        )
    print(f"Verified {args.database} against {args.manifest}")


def build_parser():
    parser = argparse.ArgumentParser(description="KDBX interoperability oracle for Keyguard")
    commands = parser.add_subparsers(dest="command", required=True)

    doctor = commands.add_parser("doctor", help="Validate the pinned pykeepass runtime")
    doctor.set_defaults(handler=command_doctor)

    generate = commands.add_parser("generate", help="Generate a deterministic KDBX corpus")
    generate.add_argument("--seed", type=pathlib.Path, required=True)
    generate.add_argument("--database", type=pathlib.Path, required=True)
    generate.add_argument("--manifest", type=pathlib.Path, required=True)
    generate.add_argument("--password", required=True)
    generate.add_argument("--keyfile", type=pathlib.Path)
    generate.set_defaults(handler=command_generate)

    verify = commands.add_parser("verify", help="Verify a KDBX database against a manifest")
    verify.add_argument("--database", type=pathlib.Path, required=True)
    verify.add_argument("--manifest", type=pathlib.Path, required=True)
    verify.add_argument("--password", required=True)
    verify.add_argument("--keyfile", type=pathlib.Path)
    verify.set_defaults(handler=command_verify)
    return parser


def main():
    args = build_parser().parse_args()
    args.handler(args)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as error:
        print(f"{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
