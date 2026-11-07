#!/usr/bin/env python3
"""Add handwritten password-manager exports to test Files.

Covers 1Password, Dashlane, Keeper, LastPass, NordPass and Proton Pass CSV and
Enpass JSON, with archive/trash flags and malformed-row skips. Expected items in
`manager-import-fixtures.json` are written by hand. Existing Files documents are
never overwritten.
"""

import argparse
import csv
import hashlib
import io
import json

from fixtures.prepare_browser_imports import OTP, login
from lib.simulator import device_argument, files_directory
from lib import session


def note(name, text, **values):
    result = dict(name=name, type=2, favorite=False, reprompt=0, notes=text, fields=[])
    result.update(values)
    return result


def field(name, value, kind=0):
    return dict(name=name, value=value, type=kind)


def prepare(device):
    files = files_directory(device)
    cases = {}

    def csv_case(case, header, rows, items, folders=(), skips=None):
        stream = io.StringIO(newline="")
        writer = csv.writer(stream, lineterminator="\r\n")
        if header:
            writer.writerow(header.split(","))
        writer.writerows(rows)
        cases[case] = dict(data=stream.getvalue(), extension="csv", items=items,
                           folders=list(folders), skips=skips or {})

    mail = login("E2E 1Password Mail", "sam", "OnePassword-2026!", ["https://onepassword.example.test/"], "line one\nline two", OTP)
    mail.update(favorite=True, fields=[field("Tag", "work"), field("Tag", "personal, home")])
    old = login("E2E 1Password archived", "bob", "OnePassword-Old-2026!", ["https://onepassword-old.example.test"])
    old["archivedDate"] = True
    csv_case("onepassword-csv", "Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes", [
        [mail["name"], "https://onepassword.example.test/", "sam", "OnePassword-2026!", OTP, "true", "false", "work;personal, home", "line one\nline two"],
        [old["name"], "https://onepassword-old.example.test", "bob", "OnePassword-Old-2026!", "", "false", "true", "", ""],
    ], [mail, old])

    dash = login("E2E Dashlane Mail", "alice@dashlane.example.test", "Dashlane-2026!", ["https://dashlane.example.test"], "First\nSecond", OTP)
    dash["fields"] = [field("Username", "alice"), field("Tag", "Work"), field("Tag", "Shared")]
    csv_case("dashlane-csv", "username,username2,username3,title,password,note,url,category,otpUrl", [
        ["alice@dashlane.example.test", "alice", "", dash["name"], "Dashlane-2026!", "First\nSecond", "https://dashlane.example.test", "Work, Shared", OTP],
    ], [dash])

    keeper = login("E2E Keeper Build", "ci", "Keeper-2026!", ["https://keeper.example.test"], "Keeper note", OTP)
    keeper.update(folder="Team Vault/Projects", fields=[field("Environment", "prod"), field("pinCode", "1234", 1)])
    csv_case("keeper-csv", None, [
        ["Projects", keeper["name"], "ci", "Keeper-2026!", "https://keeper.example.test", "Keeper note", "Team Vault#edit#reshare", "$oneTimeCode", OTP, "$text:Environment", "prod", "$pinCode", "1234"],
        ["short", "row"],
    ], [keeper], ["Team Vault", "Team Vault/Projects"], {"Item": 1})

    last = login("E2E LastPass & Mail", "ana", "LastPass-2026!", ["https://lastpass.example.test/login"], "Recovery\ncodes", "JBSWY3DPEHPK3PXP")
    last.update(favorite=True, folder="E2E Personal/Email")
    card = dict(name="E2E LastPass Card", type=3, favorite=False, reprompt=0, notes="travel card\nbackup", fields=[],
                folder="E2E Finance", card=dict(cardholderName="Ana Lima", brand="Mastercard", number="5555555555554444", code="321", expMonth="11", expYear="2029"))
    csv_case("lastpass-csv", "url,username,password,totp,extra,name,grouping,fav", [
        ["https://lastpass.example.test/login", "ana", "LastPass-2026!", "JBSWY3DPEHPK3PXP", "Recovery\ncodes", "E2E LastPass &amp; Mail", "E2E Personal\\Email", "1"],
        ["http://sn", "", "", "", "NoteType:Credit Card\nLanguage:en-US\nName on Card:Ana Lima\nType:Mastercard\nNumber:5555555555554444\nSecurity Code:321\nExpiration Date:November,2029\nNotes:travel card\nbackup", card["name"], "E2E Finance", "0"],
    ], [last, card], ["E2E Personal", "E2E Personal/Email", "E2E Finance"])

    nord = login("E2E NordPass Bank", "ana", "NordPass-2026!", ["https://nordpass.example.test", "https://m.nordpass.example.test"], "Branch 12")
    nord.update(folder="E2E Nord Finance", fields=[field("Security answer", "blue", 1)])
    header = "name,url,additional_urls,username,password,note,cardholdername,cardnumber,cvc,pin,expirydate,zipcode,folder,full_name,phone_number,email,address1,address2,city,country,state,type,custom_fields"
    values = dict(name=nord["name"], url="https://nordpass.example.test", additional_urls=json.dumps(["https://m.nordpass.example.test"]), username="ana", password="NordPass-2026!", note="Branch 12", folder="E2E Nord Finance", type="password", custom_fields=json.dumps([dict(type="hidden", label="Security answer", value="blue")]))
    csv_case("nordpass-csv", header, [[values.get(key, "") for key in header.split(",")]], [nord], ["E2E Nord Finance"])

    proton = login("E2E Proton Mail", "alice", "Proton-2026!", ["https://proton.example.test", "https://www.proton.example.test"], "Proton note")
    proton.update(folder="E2E Proton Personal", fields=[field("Email", "alice@proton.example.test")])
    proton_note = note("E2E Proton Card note", '{"number":"4111"} card note', folder="E2E Proton Personal")
    csv_case("protonpass-csv", "type,name,url,autofillUrls,email,username,password,note,totp,createTime,modifyTime,vault", [
        ["login", proton["name"], "https://proton.example.test, https://www.proton.example.test", "", "alice@proton.example.test", "alice", "Proton-2026!", "Proton note", "", "1", "1", "E2E Proton Personal"],
        ["creditCard", proton_note["name"], "", "", "", "", "", proton_note["notes"], "", "1", "1", "E2E Proton Personal"],
    ], [proton, proton_note], ["E2E Proton Personal"])

    enpass = login("E2E Enpass Login", "ana", "Enpass-2026!", ["https://enpass.example.test"], "Enpass note", "JBSWY3DPEHPK3PXP")
    enpass.update(favorite=True, folder="E2E Enpass Work/Servers")
    archived = note("E2E Enpass archived", "Archived note", archivedDate=True)
    trashed = login("E2E Enpass trashed", "deleted-user", "Deleted-2026!", [])
    trashed["deletedDate"] = True
    def enpass_field(kind, label, value):
        return dict(deleted=0, label=label, sensitive=int(kind in ("password", "totp")), type=kind, value=value)
    source = dict(folders=[dict(parent_uuid="", title="E2E Enpass Work", uuid="work"), dict(parent_uuid="work", title="Servers", uuid="servers")], items=[
        dict(title=enpass["name"], uuid="login", category="login", favorite=1, archived=0, trashed=0, folders=["servers"], note="Enpass note", fields=[enpass_field("username", "Username", "ana"), enpass_field("password", "Password", "Enpass-2026!"), enpass_field("url", "Website", "https://enpass.example.test"), enpass_field("totp", "OTP", "jbsw y3dp ehpk 3pxp")]),
        dict(title=archived["name"], uuid="archived", category="note", favorite=0, archived=1, trashed=0, note="Archived note", fields=[]),
        dict(title=trashed["name"], uuid="trashed", category="login", favorite=0, archived=0, trashed=1, fields=[enpass_field("username", "Username", "deleted-user"), enpass_field("password", "Password", "Deleted-2026!")]),
    ])
    cases["enpass-json"] = dict(data=json.dumps(source), extension="json", items=[enpass, archived, trashed], folders=["E2E Enpass Work", "E2E Enpass Work/Servers"], skips={})

    manifest = {}
    for case, value in cases.items():
        data = value.pop("data").encode()
        name = "e2e-manager-" + case + "." + value.pop("extension")
        target = files / name
        assert not target.exists(), target
        target.write_bytes(data)
        manifest[case] = dict(value, file=name, sha256=hashlib.sha256(data).hexdigest(), provenance="Handwritten synthetic fixture using repository PasswordManagerImportersTest examples")
    session.write_json(session.path("manager-import-fixtures.json"), manifest)
    print("Prepared", len(cases), "synthetic password-manager files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    device_argument(parser)
    prepare(parser.parse_args().device)
