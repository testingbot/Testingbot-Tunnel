#!/usr/bin/env python3
"""An upstream HTTP proxy that intercepts TLS, for exercising --cacert-file.

This is what a corporate TLS-inspecting proxy does: it terminates the CONNECT itself, presents
a certificate it minted for the requested host signed by its own authority, and opens a second
TLS connection onward. Clients that do not trust that authority cannot get through -- which is
the failure --cacert-file exists to fix, and the only way to reproduce it honestly.

Only the hosts named on the command line are intercepted; everything else is tunnelled opaquely,
so a test stays about the one connection it means to break.

Leaf certificates are minted on demand with openssl and cached for the process lifetime.

    mitm_proxy.py PORT CA_CERT CA_KEY HOST[,HOST...]
"""
import os
import select
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

CA_CERT = None
CA_KEY = None
INTERCEPT = set()
WORKDIR = None
_leaves = {}
_leaf_lock = threading.Lock()


def log(message):
    sys.stderr.write("[mitm] " + message + "\n")
    sys.stderr.flush()


def leaf_for(host):
    """A certificate for `host` signed by our CA, minted once and reused."""
    with _leaf_lock:
        if host in _leaves:
            return _leaves[host]
        base = os.path.join(WORKDIR, host.replace("/", "_"))
        key, csr, crt, ext = base + ".key", base + ".csr", base + ".crt", base + ".ext"
        with open(ext, "w") as handle:
            handle.write("subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\n"
                         "extendedKeyUsage=serverAuth\n" % host)
        quiet = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
        subprocess.run(["openssl", "genrsa", "-out", key, "2048"], check=True, **quiet)
        subprocess.run(["openssl", "req", "-new", "-key", key, "-out", csr,
                        "-subj", "/CN=%s" % host], check=True, **quiet)
        subprocess.run(["openssl", "x509", "-req", "-in", csr,
                        "-CA", CA_CERT, "-CAkey", CA_KEY, "-CAcreateserial",
                        "-out", crt, "-days", "2", "-extfile", ext], check=True, **quiet)
        _leaves[host] = (crt, key)
        return _leaves[host]


def pump(a, b):
    try:
        while True:
            ready, _, _ = select.select([a, b], [], [], 60)
            if not ready:
                return
            for source in ready:
                data = source.recv(65536)
                if not data:
                    return
                (b if source is a else a).sendall(data)
    except OSError:
        pass


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log(fmt % args)

    def do_CONNECT(self):
        # Whatever happens below, this socket stops being HTTP the moment we answer 200, so the
        # handler must not loop round and try to read another request line from it.
        self.close_connection = True
        host, _, port = self.path.partition(":")
        port = int(port or 443)

        if host not in INTERCEPT:
            self.log_message("TUNNEL %s:%d", host, port)
            try:
                upstream = socket.create_connection((host, port), timeout=20)
            except OSError as error:
                self.send_error(502, str(error))
                return
            self.send_response(200, "Connection Established")
            self.end_headers()
            self.connection.setblocking(True)
            pump(self.connection, upstream)
            upstream.close()
            return

        self.log_message("MITM %s:%d", host, port)
        try:
            crt, key = leaf_for(host)
        except subprocess.CalledProcessError as error:
            self.send_error(502, "could not mint a certificate: %s" % error)
            return

        self.send_response(200, "Connection Established")
        self.end_headers()
        try:
            self.connection.setblocking(True)
            # Our side of the interception: the client now speaks TLS to us, believing we are
            # the origin. It will only get this far if it trusts our CA.
            server_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_ctx.load_cert_chain(certfile=crt, keyfile=key)
            client_tls = server_ctx.wrap_socket(self.connection, server_side=True)
        except (ssl.SSLError, OSError) as error:
            # Exactly what a client without the CA produces. Logged so the harness can tell a
            # rejected handshake from the proxy never being asked.
            self.log_message("HANDSHAKE-REJECTED %s: %s", host, error)
            return

        try:
            upstream_ctx = ssl.create_default_context()
            upstream = upstream_ctx.wrap_socket(
                socket.create_connection((host, port), timeout=20), server_hostname=host)
        except OSError as error:
            self.log_message("UPSTREAM-FAILED %s: %s", host, error)
            client_tls.close()
            return

        pump(client_tls, upstream)
        upstream.close()
        client_tls.close()

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def do_HEAD(self):
        self.forward()

    def forward(self):
        """Plain HTTP, relayed unchanged.

        Interception is about TLS, but with --proxy set every plain request goes through here
        too -- including the tunnel's own traffic to the local origin. Refusing them made this
        proxy look like a broken tunnel rather than an intercepting one.
        """
        self.close_connection = True
        parts = urlsplit(self.path)
        if not parts.hostname:
            self.send_error(400, "absolute URI required")
            return
        self.log_message("%s %s", self.command, self.path)
        try:
            upstream = socket.create_connection(
                (parts.hostname, parts.port or 80), timeout=20)
        except OSError as error:
            self.send_error(502, str(error))
            return
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        body = b""
        length = self.headers.get("Content-Length")
        if length:
            body = self.rfile.read(int(length))
        request = ["%s %s HTTP/1.0" % (self.command, path),
                   "Host: %s" % parts.netloc]
        for name, value in self.headers.items():
            if name.lower() in ("host", "proxy-connection", "connection", "content-length"):
                continue
            request.append("%s: %s" % (name, value))
        if body:
            request.append("Content-Length: %d" % len(body))
        request.append("Connection: close")
        upstream.sendall(("\r\n".join(request) + "\r\n\r\n").encode() + body)
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            self.connection.sendall(chunk)
        upstream.close()


if __name__ == "__main__":
    listen_port = int(sys.argv[1])
    CA_CERT, CA_KEY = sys.argv[2], sys.argv[3]
    INTERCEPT = {h.strip() for h in sys.argv[4].split(",") if h.strip()}
    WORKDIR = tempfile.mkdtemp(prefix="mitm-")
    log("intercepting %s, tunnelling everything else" % ", ".join(sorted(INTERCEPT)))
    ThreadingHTTPServer(("127.0.0.1", listen_port), Proxy).serve_forever()
