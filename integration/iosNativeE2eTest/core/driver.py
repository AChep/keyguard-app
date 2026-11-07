#!/usr/bin/env python3
"""Loopback broker and CLI for the XCTest native driver.

`e2e driver serve` runs the broker that the NativeE2e XCTest runner polls.
Any other action sends one command to the runner and prints its result.
"""

import argparse
import base64
import json
import queue
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from lib import session
from lib.config import PORTS
from lib.journey import ADDRESS, send
commands = queue.Queue()
pending = {}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def reply(self, value):
        data = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/next":
            while True:
                try:
                    command = commands.get(timeout=20)
                except queue.Empty:
                    self.reply({})
                    return
                with lock:
                    active = command["id"] in pending
                if active:
                    self.reply(command)
                    return
        else:
            self.reply({"pending": len(pending)})

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/result":
            with lock:
                result_queue = pending.get(data["id"])
            if result_queue:
                result_queue.put(data)
            self.reply({"ok": True})
            return
        command_id = str(uuid.uuid4())
        data["id"] = command_id
        result_queue = queue.Queue()
        with lock:
            pending[command_id] = result_queue
        commands.put(data)
        try:
            result = result_queue.get(timeout=90)
        except queue.Empty:
            result = {"ok": False, "error": "No XCTest response within 90s", "id": command_id}
        finally:
            with lock:
                pending.pop(command_id, None)
        # The evidence journal deliberately excludes typed text and screenshot data.
        record = {"time": time.time(), "command": {k: v for k, v in data.items() if k != "text"},
                  "result": {k: v for k, v in result.items() if k != "png"}}
        session.append_jsonl(self.server.evidence / "actions.jsonl", record)
        self.reply(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", help="serve, tree, screenshot, launch, tap, type, swipe, ...")
    parser.add_argument("--evidence", type=Path, help="Folder for actions.jsonl (default: the session folder)")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--label")
    parser.add_argument("--identifier")
    parser.add_argument("--placeholder")
    parser.add_argument("--type")
    parser.add_argument("--index", type=int)
    parser.add_argument("--contains", action="store_true", default=None)
    parser.add_argument("--text")
    parser.add_argument("--direction")
    parser.add_argument("--bundle")
    parser.add_argument("--x", type=float)
    parser.add_argument("--y", type=float)
    parser.add_argument("--toX", type=float)
    parser.add_argument("--toY", type=float)
    parser.add_argument("--scale", type=float)
    parser.add_argument("--velocity", type=float)
    parser.add_argument("--duration", type=float)
    parser.add_argument("--endHold", type=float)
    parser.add_argument("--position", type=float)
    parser.add_argument("--orientation", choices=("portrait", "portraitUpsideDown", "landscapeLeft", "landscapeRight"))
    parser.add_argument("--productId")
    parser.add_argument("--transactionId", type=int)
    for option in ("disableDialogs", "askToBuy", "failPurchase", "failRestore"):
        parser.add_argument("--" + option, action=argparse.BooleanOptionalAction, default=None)
    args = parser.parse_args()
    if args.action == "serve":
        server = ThreadingHTTPServer(("127.0.0.1", PORTS["driver"]), Handler)
        server.evidence = args.evidence or session.directory()
        print(f"Broker ready at {ADDRESS}", flush=True)
        server.serve_forever()
        return
    command = {k: v for k, v in vars(args).items()
               if v is not None and k not in ("evidence", "output")}
    result = send(command)
    if "png" in result:
        if not args.output:
            parser.error("screenshot requires --output")
        args.output.write_bytes(base64.b64decode(result.pop("png")))
    if args.output and "tree" in result:
        args.output.write_text(result["tree"])
    print(result.get("tree", json.dumps(result)))
    if not result.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
