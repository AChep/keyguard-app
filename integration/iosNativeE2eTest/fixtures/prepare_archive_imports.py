#!/usr/bin/env python3
"""Add handwritten 1PUX, Dashlane and Proton Pass archive exports to test Files.

Six synthetic schema examples, not vendor-exporter captures: 1PUX with an
attachment, Dashlane ZIP and payment CSV, Proton Pass JSON and ZIP, and a Proton
encrypted-envelope marker. Expected items go to
`archive-manager-import-fixtures.json`. The lease
`archive-manager-fixture-state.json` records every owned filename and hash before
copying; existing Files documents are never overwritten.
"""

import argparse
import csv
import hashlib
import io
import json
import os
import zipfile

from fixtures.prepare_browser_imports import OTP, login
from lib.simulator import device_argument, files_directory
from fixtures.prepare_manager_imports import field, note
from lib import session


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
        for name, value in entries.items():
            target.writestr(name, value.encode() if isinstance(value, str) else value)
    data = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as source:
        assert source.testzip() is None
        assert set(source.namelist()) == set(entries)
        for name, value in entries.items():
            assert source.read(name) == (value.encode() if isinstance(value, str) else value)
    return data


def table(columns, rows):
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\r\n")
    writer.writerow(columns.split(","))
    for row in rows:
        assert len(row) == len(columns.split(","))
        writer.writerow(row)
    return output.getvalue()


def cases():
    result = {}
    attachment = "Archive import attachment\n日本語 🔐\n".encode()
    folder = "E2E Archive 1PUX"
    prefix = "E2E 1PUX "
    mail = login(prefix + "Login", "pux-user", "  Pux-Archive-2026!  ",
                 ["https://pux.example.test/login"], "First line\nPux 🔐", OTP)
    mail.update(folder=folder, favorite=True, fields=[field("Recovery", "hidden-answer", 1),
                                                    field("Accepted", "true", 2), field("Tag", "archive")])
    mail["login"]["uris"][0]["match"] = 1
    card = dict(name=prefix + "Card", type=3, favorite=False, reprompt=0, notes=None, fields=[],
                folder=folder, card=dict(cardholderName="Archive Cardholder", brand="Mastercard",
                                         number="5555555555554444", code="321", expMonth="12", expYear="2030"))
    identity = dict(name=prefix + "Identity", type=4, favorite=False, reprompt=0, notes=None,
                    fields=[], folder=folder, identity=dict(firstName="Ada", lastName="Archive",
                    email="pux-identity@keyguard.test", address1="42 Fixture Road", city="Test City",
                    state="Test State", postalCode="12345", country="Test Country"))
    document = note(prefix + "Document", "Document note\nSecond line", folder=folder,
                    attachments=[dict(fileName="archive-e2e.txt")])
    def section_field(ident, kind, value):
        return dict(id=ident, title=ident, value={kind: value})
    def item(category, title, details, **extra):
        return dict(categoryUuid=category, overview=dict(title=prefix + title), details=details, **extra)
    records = [
        item("001", "Login", dict(notesPlain=mail["notes"], loginFields=[
            dict(designation="username", value="pux-user", fieldType="T"),
            dict(designation="password", value="  Pux-Archive-2026!  ", fieldType="P"),
            dict(name="Recovery", value="hidden-answer", fieldType="P"),
            dict(name="Accepted", value="✓", fieldType="C"),
        ], sections=[dict(fields=[section_field("otp", "totp", OTP)])]), favIndex=1),
        item("002", "Card", dict(sections=[dict(fields=[
            section_field("cardholder", "string", "Archive Cardholder"),
            section_field("type", "string", "mc"), section_field("ccnum", "string", "5555555555554444"),
            section_field("cvv", "concealed", "321"), section_field("expiry", "monthYear", 203012),
        ])])),
        item("004", "Identity", dict(sections=[dict(fields=[
            section_field("firstname", "string", "Ada"), section_field("lastname", "string", "Archive"),
            section_field("email", "email", {"email_address": "pux-identity@keyguard.test"}),
            section_field("address", "address", dict(street="42 Fixture Road", city="Test City",
                          state="Test State", zip="12345", country="Test Country")),
        ])])),
        item("003", "Document", dict(notesPlain=document["notes"], documentAttributes=dict(
            documentId="archive-e2e-doc", fileName="archive-e2e.txt", decryptedSize=len(attachment)))),
    ]
    records[0]["overview"].update(url="https://pux.example.test/login", tags=["archive"],
                                 urls=[dict(url="https://pux.example.test/login", mode="host")])
    data = dict(accounts=[dict(vaults=[dict(attrs=dict(name=folder), items=records)])])
    result["onepassword-pux"] = dict(extension="1pux", format="1Password",
        payload=archive({"export.data": json.dumps(data, ensure_ascii=False),
                         "files/archive-e2e-doc__archive-e2e.txt": attachment}),
        items=[mail, card, identity, document], folders=[folder], skips={},
        attachment=dict(item=document["name"], filename="archive-e2e.txt", size=len(attachment),
                        sha256=hashlib.sha256(attachment).hexdigest(), content=attachment.decode()))

    prefix = "E2E Dash ZIP "
    dash_login = login(prefix + "Login", "dash-zip", "Dash-Zip-2026!",
                       ["https://dash-zip.example.test"], "Dash archive note", OTP)
    dash_note = note(prefix + "Note", "Dash multiline\n日本語", fields=[field("Tag", "archive")])
    dash_card = dict(name=prefix + "Card", type=3, favorite=False, reprompt=0, notes="Card note", fields=[],
                     card=dict(cardholderName="Dash Holder", number="4111111111111111", code="123",
                               expMonth="3", expYear="2031"))
    dash_bank = note(prefix + "Bank", "Bank note", fields=[field("Account holder", "Dash Holder"),
        field("Routing number", "110000000"), field("Account number", "000123456789", 1)])
    dash_id = note("Passport (E2E Dash ZIP ID)", None, fields=[field("type", "passport"),
        field("name", "E2E Dash ZIP ID"), field("number", "TEST-PASSPORT-123", 1), field("issue_date", "2020-01-02")])
    dash_person = note(prefix + "Person", None, fields=[field("type", "name"), field("item_name", prefix + "Person"),
        field("first_name", "Dash"), field("last_name", "Person"), field("date_of_birth", "1990-02-03")])
    dash_wifi = note(prefix + "Wifi", "Wifi note", fields=[field("SSID", "E2E-Archive-Wifi"),
        field("Password", "Wifi-Archive-2026!", 1), field("Hidden network", "false"), field("Security", "WPA2")])
    payments = "type,name,cc_number,account_number,account_name,account_holder,code,expiration_month,expiration_year,routing_number,note"
    card_row = ["payment_card", dash_card["name"], "4111111111111111", "", "Dash Holder", "", "123", "MARCH", "2031", "", "Card note"]
    entries = {
        "export/credentials.csv": table("username,title,password,url,note,otpUrl", [["dash-zip", dash_login["name"], "Dash-Zip-2026!", "https://dash-zip.example.test", "Dash archive note", OTP]]),
        "export/securenotes.csv": table("title,note,category", [[dash_note["name"], dash_note["notes"], "archive"]]),
        "export/payments.csv": table(payments, [card_row, ["bank", dash_bank["name"], "", "000123456789", "", "Dash Holder", "", "", "", "110000000", "Bank note"]]),
        "export/ids.csv": table("type,name,number,issue_date", [["passport", "E2E Dash ZIP ID", "TEST-PASSPORT-123", "2020-01-02"]]),
        "export/personalInfo.csv": table("type,item_name,first_name,last_name,date_of_birth", [["name", dash_person["name"], "Dash", "Person", "1990-02-03"]]),
        "export/wifi.csv": table("name,ssid,passphrase,hidden,encription_type,note", [[dash_wifi["name"], "E2E-Archive-Wifi", "Wifi-Archive-2026!", "false", "WPA2", "Wifi note"]]),
        "export/future.csv": table("unknown_schema,value", [["one", "two"]]),
        "__MACOSX/._credentials.csv": "Ignored archive metadata",
        "readme.txt": "Synthetic archive fixture",
    }
    result["dashlane-archive"] = dict(extension="zip", format="Dashlane", payload=archive(entries),
        items=[dash_login, dash_note, dash_card, dash_bank, dash_id, dash_person, dash_wifi], folders=[], skips={"File": 1})
    card_row[1] = "E2E Dash Payment CSV"
    result["dashlane-payment-csv"] = dict(extension="csv", format="Dashlane", payload=table(payments, [card_row]).encode(),
        items=[dict(dash_card, name=card_row[1])], folders=[], skips={})

    for suffix, extension in (("JSON", "json"), ("ZIP", "zip")):
        prefix = "E2E Proton " + suffix + " "
        folder = "E2E Archive Proton " + suffix
        mail = login(prefix + "Login", "proton-archive", "  Proton-Archive-2026!  ",
            ["https://proton-archive.example.test/login"], "Proton archive note", OTP)
        mail.update(folder=folder + "/Email", favorite=True,
                    fields=[field("Email", "proton-archive@keyguard.test"), field("Recovery", "proton-hidden", 1)])
        mail["login"]["uris"][0]["match"] = 3
        card = dict(name=prefix + "Card", type=3, favorite=False, reprompt=0, notes=None, fields=[], folder=folder,
                    card=dict(cardholderName="Proton Holder", brand="Visa", number="4111111111111111",
                              code="987", expMonth="12", expYear="2030"))
        identity = dict(name=prefix + "Identity", type=4, favorite=False, reprompt=0, notes=None, fields=[], folder=folder,
                        identity=dict(firstName="Proton", lastName="Archive", email="proton-id@keyguard.test", city="Test City"))
        wifi = note(prefix + "Wifi", "Proton wifi note", folder=folder, fields=[field("SSID", "E2E-Proton-Wifi"),
            field("Password", "Proton-Wifi-2026!", 1), field("Security", "WPA2")])
        document = note(prefix + "Note", "Proton note\nSecond line", folder=folder, fields=[field("Reference", "archive-ref")])
        def proton_item(kind, expected, content, **extra):
            return dict(data=dict(type=kind, metadata=dict(name=expected["name"], note=expected.get("notes")), content=content), **extra)
        records = [
            proton_item("login", mail, dict(itemUsername="proton-archive", itemEmail="proton-archive@keyguard.test",
                password="  Proton-Archive-2026!  ", totpUri=OTP,
                autofillUrls=[dict(url="https://proton-archive.example.test/login", mode=6)], passkeys=[{}]), folderId="email", pinned=True),
            proton_item("creditCard", card, dict(cardholderName="Proton Holder", cardType=2, number="4111111111111111", verificationNumber="987", expirationDate="2030-12")),
            proton_item("identity", identity, dict(firstName="Proton", lastName="Archive", email="proton-id@keyguard.test", city="Test City")),
            proton_item("wifi", wifi, dict(ssid="E2E-Proton-Wifi", password="Proton-Wifi-2026!", security=2)),
            proton_item("note", document, {}, files=[dict(name="unsupported-proton.txt")]),
        ]
        records[0]["data"]["extraFields"] = [dict(fieldName="Recovery", type="hidden", data=dict(content="proton-hidden"))]
        records[4]["data"]["extraFields"] = [dict(fieldName="Reference", type="text", data=dict(content="archive-ref"))]
        data = json.dumps(dict(vaults={"archive":dict(name=folder, folders=[dict(folderId="email", name="Email")], items=records)}), ensure_ascii=False)
        payload = data.encode() if extension == "json" else archive({"Proton Pass/data.json":data, "Proton Pass/files/unsupported-proton.txt":"Deliberately unsupported attachment"})
        result["protonpass-" + suffix.lower()] = dict(extension=extension, format="Proton Pass", payload=payload,
            items=[mail, card, identity, wifi, document], folders=[folder, folder + "/Email"], skips={"Passkey":1,"Attachment":1})
    result["protonpass-encrypted-envelope"] = dict(extension="zip", format="Proton Pass",
        payload=archive({"Proton Pass/data.pgp":"Synthetic unsupported-envelope marker; no decryption is attempted"}),
        items=[], folders=[], skips={}, expectedError="Keyguard does not support this file's encryption.")
    return result


def prepare(device):
    os.umask(0o077)
    state_path = session.path("archive-manager-fixture-state.json")
    manifest_path = session.path("archive-manager-import-fixtures.json")
    state = session.read_json(state_path) if state_path.exists() else {}
    if state.get("filePreparationPending") or state.get("fixturesPrepared") or manifest_path.exists():
        raise ValueError("Inspect the lease before retrying fixture preparation")
    files = files_directory(device)
    state["filesFixtureDirectory"] = str(files)
    directory = session.directory() / "archive-manager-fixtures"
    if directory.exists():
        raise ValueError("Fixture artifact directory already exists")
    values = cases()
    directory.mkdir(mode=0o700)
    manifest = {}
    for name, value in values.items():
        data = value.pop("payload")
        filename = "e2e-archive-import-" + name + "." + value.pop("extension")
        assert not (files / filename).exists()
        (directory / filename).write_bytes(data)
        manifest[name] = dict(value, file=filename, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
            provenance="Handwritten synthetic export, explicit independent expected values; not a vendor-exporter capture")
    state.update(filePreparationPending=True, ownedFixtureFiles=[dict(filename=x["file"], size=x["bytes"], sha256=x["sha256"]) for x in manifest.values()])
    session.write_json(state_path, state)
    for value in manifest.values():
        source = directory / value["file"]
        target = files / value["file"]
        with target.open("xb") as output:
            output.write(source.read_bytes())
        target.chmod(0o600)
        assert hashlib.sha256(target.read_bytes()).hexdigest() == value["sha256"]
    session.write_json(manifest_path, manifest)
    state.update(filePreparationPending=False, fixturesPrepared=True)
    session.write_json(state_path, state)
    print("Prepared", len(manifest), "private synthetic archive import fixtures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
