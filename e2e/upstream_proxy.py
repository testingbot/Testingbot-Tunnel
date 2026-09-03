#!/usr/bin/env python3
"""Minimal upstream HTTP proxy for exercising the tunnel's --proxy option.

Supports absolute-URI GET forwarding, protocol upgrades, and CONNECT tunnelling --
enough to prove the tunnel chains through it. Logs each request so the harness can assert the
traffic really went this way.

With a second argument of user:password it demands Basic proxy authentication and
answers 407 without it. That is what makes the credential path testable end to
end: a corporate proxy the tunnel must authenticate to before anything works,
including the SSH control connection (TB-321).

    upstream_proxy.py PORT [user:password] [--freeze-file PATH]

With --freeze-file, the proxy stops relaying whenever PATH exists while keeping every socket
open, which is what a black-holing network looks like from either end.
"""
import base64
import os
import select
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


FREEZE_FILE = None


def frozen():
    """True while the freeze file exists: relay nothing, but keep the sockets open."""
    return FREEZE_FILE is not None and os.path.exists(FREEZE_FILE)


def pump(a, b):
    """Relay until one side closes, or sit silent while frozen.

    Freezing is how a half-open connection is reproduced. Killing this proxy closes its sockets
    and the tunnel sees a FIN, which is the easy case; a network that starts dropping packets
    sends nothing at all, and the connection is only discovered to be dead by a keepalive or a
    TCP retransmit timeout. Those are different code paths and only the first was ever tested.
    """
    try:
        while True:
            if frozen():
                time.sleep(0.5)
                continue
            r, _, _ = select.select([a, b], [], [], 30)
            if not r:
                return
            for s in r:
                if frozen():
                    break
                data = s.recv(65536)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    except OSError:
        pass


REQUIRED_CREDENTIALS = None


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def authorized(self):
        """True when no credentials are required, or the right ones were sent."""
        if REQUIRED_CREDENTIALS is None:
            return True
        header = self.headers.get("Proxy-Authorization", "")
        scheme, _, token = header.partition(" ")
        if scheme.lower() != "basic":
            # Log the scheme so a Negotiate attempt is visible in the harness output.
            self.log_message("AUTH-MISSING scheme=%r", scheme or "<none>")
            return False
        try:
            supplied = base64.b64decode(token).decode()
        except Exception:
            return False
        if supplied != REQUIRED_CREDENTIALS:
            self.log_message("AUTH-REJECTED")
            return False
        self.log_message("AUTH-OK")
        return True

    def demand_auth(self):
        self.send_response(407, "Proxy Authentication Required")
        self.send_header("Proxy-Authenticate", 'Basic realm="e2e"')
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def log_message(self, fmt, *args):
        sys.stderr.write("[upstream] " + (fmt % args) + "\n")
        sys.stderr.flush()

    def do_CONNECT(self):
        self.log_message("CONNECT %s", self.path)
        if not self.authorized():
            self.demand_auth()
            return
        host, _, port = self.path.partition(":")
        try:
            upstream = socket.create_connection((host, int(port or 443)), timeout=15)
        except OSError as e:
            self.send_error(502, str(e))
            return
        self.send_response(200, "Connection Established")
        self.end_headers()
        self.connection.setblocking(True)
        pump(self.connection, upstream)
        upstream.close()

    # Meant for us rather than the origin, so they are not forwarded. Everything else is,
    # which is what makes an upgrade possible: Upgrade, Connection and Sec-WebSocket-Key have
    # to arrive intact or the origin has no reason to answer 101.
    HOP_BY_HOP = {"proxy-authorization", "proxy-connection", "proxy-authenticate"}

    def forward_upgrade(self, parts, upstream):
        """Forward a protocol upgrade, then relay bytes in both directions.

        The plain-GET path below rewrites the request as HTTP/1.0 with Connection: close and
        keeps none of the client's headers, so an upgrade sent through it could never produce a
        101 -- the origin never saw that one was being asked for. That made the tunnel's
        ws://-through---proxy path untestable here: it looked like the tunnel had failed when
        this proxy was what could not carry it.
        """
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        lines = [f"GET {path} HTTP/1.1", f"Host: {parts.netloc}"]
        for name, value in self.headers.items():
            if name.lower() in self.HOP_BY_HOP or name.lower() == "host":
                continue
            lines.append(f"{name}: {value}")
        upstream.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())

        # Read just the response head, a byte at a time: anything after it is already frame
        # data and taking it here would drop it.
        head = b""
        while b"\r\n\r\n" not in head:
            byte = upstream.recv(1)
            if not byte:
                self.log_message("upgrade: origin closed before answering")
                self.send_error(502, "origin closed before answering the upgrade")
                return
            head += byte
        status = head.split(b"\r\n", 1)[0].decode("latin-1")
        self.log_message("upgrade -> %s", status)
        self.connection.sendall(head)
        if b" 101 " not in head.split(b"\r\n", 1)[0] + b" ":
            # Not an upgrade after all; the head has been passed on, so pass on the rest too.
            while True:
                chunk = upstream.recv(65536)
                if not chunk:
                    break
                self.connection.sendall(chunk)
            self.close_connection = True
            return
        self.connection.setblocking(True)
        pump(self.connection, upstream)
        self.close_connection = True

    def do_GET(self):
        self.log_message("GET %s", self.path)
        if not self.authorized():
            self.demand_auth()
            return
        parts = urlsplit(self.path)
        try:
            upstream = socket.create_connection(
                (parts.hostname, parts.port or 80), timeout=15)
        except OSError as e:
            self.send_error(502, str(e))
            return

        upgrade = self.headers.get("Upgrade")
        if upgrade:
            try:
                self.forward_upgrade(parts, upstream)
            finally:
                upstream.close()
            return

        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        req = f"GET {path} HTTP/1.0\r\nHost: {parts.netloc}\r\nConnection: close\r\n\r\n"
        upstream.sendall(req.encode())
        self.connection.sendall(b"")
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            self.connection.sendall(chunk)
        upstream.close()
        self.close_connection = True


if __name__ == "__main__":
    argv = list(sys.argv[1:])
    if "--freeze-file" in argv:
        at = argv.index("--freeze-file")
        FREEZE_FILE = argv[at + 1]
        del argv[at:at + 2]
        sys.stderr.write("[upstream] freeze file: %s\n" % FREEZE_FILE)
        sys.stderr.flush()
    sys.argv = [sys.argv[0]] + argv
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8891
    if len(sys.argv) > 2 and sys.argv[2]:
        REQUIRED_CREDENTIALS = sys.argv[2]
        sys.stderr.write("[upstream] requiring Basic proxy authentication\n")
        sys.stderr.flush()
    ThreadingHTTPServer(("127.0.0.1", port), Proxy).serve_forever()
