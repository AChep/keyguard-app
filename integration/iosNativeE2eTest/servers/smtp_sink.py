#!/usr/bin/env python3
"""Capture mail from the mail server over local SMTP, with a read-only HTTP API.

Binds only to loopback. A sink: it never relays, forwards or opens an outbound
connection. Accepts only keyguard.test recipients. Raw messages and parsed
bodies stay in the data folder; the message list omits bodies.
"""

import argparse
import asyncio
import email.policy
import json
import threading
import uuid
from datetime import datetime, timezone
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from lib import session
from lib.config import PORTS

MAX_MESSAGE_BYTES = 4 * 1024 * 1024


class MailSink:
    def __init__(self, directory):
        self.directory = directory
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.messages = {}
        self.journal = directory / "messages.jsonl"
        if self.journal.exists():
            for line in self.journal.read_text().splitlines():
                message = json.loads(line)
                self.messages[message["id"]] = message

    def capture(self, sender, recipients, raw):
        parsed = BytesParser(policy=email.policy.default).parsebytes(raw)

        def body(kind):
            part = parsed.get_body(preferencelist=(kind,))
            return part.get_content() if part is not None else ""

        identifier = str(uuid.uuid4())
        message = {
            "id": identifier,
            "receivedAt": datetime.now(timezone.utc).isoformat(),
            "sender": sender,
            "recipients": list(recipients),
            "subject": str(parsed.get("Subject", "")),
            "text": body("plain"),
            "html": body("html"),
            "bytes": len(raw),
        }
        with self.lock:
            (self.directory / (identifier + ".eml")).write_bytes(raw)
            session.append_jsonl(self.journal, message)
            self.messages[identifier] = message
        print(f"Captured {identifier} for {len(recipients)} synthetic recipients", flush=True)

    async def smtp(self, reader, writer):
        async def reply(text):
            writer.write((text + "\r\n").encode())
            await writer.drain()

        async def read_line():
            return await asyncio.wait_for(reader.readline(), timeout=30)

        sender = None
        recipients = []
        try:
            await reply("220 keyguard.test E2E capture only")
            while line := await read_line():
                command = line.decode("utf-8", errors="replace").strip()
                verb = command.split(" ", 1)[0].upper()
                if verb in ("EHLO", "HELO"):
                    await reply("250-keyguard.test\r\n250-SIZE 4194304\r\n250-8BITMIME\r\n250 SMTPUTF8")
                elif command.upper().startswith("MAIL FROM:"):
                    sender = command.partition(":")[2].strip().split(" ", 1)[0].strip("<>")
                    recipients = []
                    await reply("250 Sender accepted")
                elif command.upper().startswith("RCPT TO:"):
                    recipient = command.partition(":")[2].strip().split(" ", 1)[0].strip("<>")
                    if sender is None:
                        await reply("503 MAIL required")
                    elif recipient.rsplit("@", 1)[-1].lower() != "keyguard.test" or "@" not in recipient:
                        await reply("550 Only synthetic keyguard.test recipients are accepted")
                    else:
                        recipients.append(recipient)
                        await reply("250 Synthetic recipient accepted")
                elif verb == "DATA":
                    if not recipients:
                        await reply("503 Accepted recipient required")
                        continue
                    await reply("354 End with a single dot")
                    parts = []
                    size = 0
                    while True:
                        data = await read_line()
                        if not data:
                            return
                        if data in (b".\r\n", b".\n"):
                            break
                        if data.startswith(b".."):
                            data = data[1:]
                        size += len(data)
                        if size > MAX_MESSAGE_BYTES:
                            await reply("552 Message too large")
                            return
                        parts.append(data)
                    self.capture(sender, recipients, b"".join(parts))
                    sender, recipients = None, []
                    await reply("250 Captured locally; never relayed")
                elif verb == "RSET":
                    sender, recipients = None, []
                    await reply("250 Reset")
                elif verb == "NOOP":
                    await reply("250 OK")
                elif verb == "QUIT":
                    await reply("221 Bye")
                    return
                else:
                    await reply("502 Unsupported command")
        except (TimeoutError, ConnectionError, ValueError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()


class CaptureAPI(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        with self.server.sink.lock:
            if path == "/health":
                value = {"ok": True, "sinkOnly": True, "messages": len(self.server.sink.messages)}
            elif path == "/api/messages":
                value = [{k: v for k, v in message.items() if k not in ("text", "html")}
                         for message in self.server.sink.messages.values()]
            elif path.startswith("/api/messages/"):
                value = self.server.sink.messages.get(path.removeprefix("/api/messages/"))
            else:
                value = None
        data = json.dumps(value if value is not None else {"error": "Not found"}).encode()
        self.send_response(200 if value is not None else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--smtp-port", type=int, default=PORTS["smtp"])
    parser.add_argument("--api-port", type=int, default=PORTS["smtp-api"])
    parser.add_argument("--data-dir", type=Path, help="Mail folder (default: <session>/smtp-sink)")
    args = parser.parse_args()
    sink = MailSink(args.data_dir or session.path("smtp-sink"))
    api = ThreadingHTTPServer(("127.0.0.1", args.api_port), CaptureAPI)
    api.sink = sink
    threading.Thread(target=api.serve_forever, daemon=True).start()
    server = await asyncio.start_server(sink.smtp, "127.0.0.1", args.smtp_port)
    print(f"Synthetic SMTP sink 127.0.0.1:{args.smtp_port}; API 127.0.0.1:{args.api_port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
