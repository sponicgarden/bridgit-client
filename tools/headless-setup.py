#!/usr/bin/env python3
"""Owner's USB console. Enrollment requires the owner to click a local browser button.

Never POST to the enrollment page on the owner's behalf. Do not print adb's result:
its one-time credential is captured here and saved only in the private output file.
"""
import argparse
import base64
import html
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import urllib.parse


def adb(args, *command):
    if command and command[0] == "shell":
        command = ("shell", shlex.join(command[1:]))
    result = subprocess.run(["adb", "-P", str(args.adb_port), "-s", args.serial, *command],
                            capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError("USB command failed; confirm the selected device is connected and authorized")
    return result.stdout


def console(args, command, *extras):
    raw = adb(args, "shell", "am", "broadcast", "--user", "0", "-n",
              args.package + "/cc.bridgit.app.headless.HeadlessAdminReceiver",
              "--es", "command", command, *extras)
    match = re.search(r'result=1, data="([A-Za-z0-9+/=]+)"', raw)
    if not match:
        raise RuntimeError("Owner console refused the request; verify the APK supports headless mode and the agent name is unique")
    return json.loads(base64.b64decode(match[1]))


def enroll(args, super_powers):
    path = args.output.expanduser()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Reserve/open before creating authority. Refuse symlinks and existing files atomically.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    body = None
    enrollment_dispatched = False
    try:
        with os.fdopen(fd, "w") as output:
            output.write(json.dumps({"setup": "pending", "serial": args.serial, "agent_name": args.name}) + " " * 4096)
            output.flush()
            os.fsync(output.fileno())
            console(args, "configure", "--es", "mode", "headless")
            enrollment_dispatched = True
            body = console(args, "enroll", "--es", "name", args.name,
                           "--ez", "super_powers", str(super_powers).lower())
            body.update(serial=args.serial, adb_port=args.adb_port, package=args.package)
            output.seek(0)
            json.dump(body, output, indent=2)
            output.write("\n")
            output.truncate()
            output.flush()
            os.fsync(output.fileno())
        return body
    except Exception:
        if enrollment_dispatched and body is None:
            raise RuntimeError("Enrollment outcome is unknown; the private pending marker is preserved. Inspect device status before any retry") from None
        if body is not None:
            # Only the fresh enrollment from this invocation is rolled back.
            # If USB disconnected, leave the private file for recovery; never hide uncertainty.
            try:
                console(args, "revoke", "--es", "agent_id", body["agent_id"])
            except Exception:
                raise RuntimeError("Credential save failed and enrollment revocation could not be confirmed") from None
        path.unlink(missing_ok=True)
        raise


def owner_post_allowed(path, nonce, origin, expected_origin, form, csrf):
    # Embedded browsers may omit Origin or use a sandboxed opaque origin. A second,
    # independent secret is carried only in the same-origin form, never in the URL.
    return (path == "/" + nonce and origin in (None, "null", expected_origin)
            and secrets.compare_digest(form.get("csrf", [""])[0], csrf))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--package", default="cc.bridgit.app")
    parser.add_argument("--name", default="Codex")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--status", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]+", args.package):
        parser.error("Invalid serial or package")
    if not re.fullmatch(r"[A-Za-z0-9 _-]{1,24}", args.name):
        parser.error("Agent name must be 1–24 letters, numbers, spaces, - or _")
    if args.status:
        print(json.dumps(console(args, "status"), indent=2))
        return
    args.output = (args.output or Path.home() / ".config/bridgit/devices" / (args.serial + ".json")).expanduser()
    if args.output.exists() or args.output.is_symlink():
        parser.error("Credential file already exists; use it instead of enrolling again")
    nonce = secrets.token_urlsafe(32)
    csrf = secrets.token_urlsafe(32)
    completed = False

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Request paths contain the one-time owner-console token.

        def page(self, body, status=200):
            encoded = body.encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self):
            if self.path != "/" + nonce:
                self.page("Not found", 404)
                return
            self.page("<!doctype html><meta name=viewport content='width=device-width'><title>Connect Bridgit</title>"
                      "<style>body{font:18px system-ui;max-width:550px;margin:10vh auto;padding:24px;background:#111c22;color:#fff}"
                      "button{font:inherit;padding:14px 24px;background:#bce6b7;border:0;border-radius:12px}label{display:block;margin:22px 0}</style>"
                      "<h1>Connect " + html.escape(args.name) + "</h1><p>Device: " + html.escape(args.serial) + "</p>"
                      "<p>Grant this control agent app settings, supported capabilities, and microphone access until revoked. Android permissions remain in force.</p>"
                      "<form method=post><input type=hidden name=csrf value='" + csrf + "'><label><input type=checkbox name=super value=yes checked> Agent Super Powers</label>"
                      "<button>Connect control agent</button></form>")

        def do_POST(self):
            nonlocal completed
            expected_origin = f"http://127.0.0.1:{self.server.server_port}"
            if completed or self.path != "/" + nonce:
                self.page("Request refused", 403)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 <= length <= 256:
                    raise ValueError()
                form = urllib.parse.parse_qs(self.rfile.read(length).decode())
                if not owner_post_allowed(self.path, nonce, self.headers.get("Origin"), expected_origin, form, csrf):
                    self.page("Request refused", 403)
                    return
                body = enroll(args, form.get("super") == ["yes"])
                completed = True
                setup_pending = not body.get("grants_applied", False)
                for command in [("shell", "pm", "grant", args.package, "android.permission.RECORD_AUDIO"),
                                ("shell", "am", "start", "-n", args.package + "/cc.bridgit.app.ui.MainActivity")]:
                    try:
                        adb(args, *command)
                    except RuntimeError:
                        setup_pending = True
                self.page("<title>Connected</title><h1>Enrolled</h1><p>"
                          + ("Some device setup remains. " if setup_pending else "")
                          + "You can return to your chat.</p>")
                print(f"Enrollment complete. Credentials saved privately: {args.output}", flush=True)
                if setup_pending:
                    print("Enrollment is saved; device permissions/startup or grants still need verification.", flush=True)
            except Exception:
                if args.output.exists():
                    completed = True  # A saved key or unknown enrollment must never be retried by this page.
                # Do not disclose command output or credential values in a browser or terminal.
                self.page("Setup did not finish. Check the private credential file before retrying; a completed enrollment is never repeated automatically.", 500)

    server = HTTPServer(("127.0.0.1", 0), Handler)
    server.timeout = 1
    print(f"Owner enrollment: http://127.0.0.1:{server.server_port}/{nonce}", flush=True)
    try:
        while not completed:
            server.handle_request()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
