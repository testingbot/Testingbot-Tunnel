#!/usr/bin/env python3
"""Minimal upstream SOCKS5 proxy for exercising the tunnel's --proxy socks5:// option.

Implements RFC 1928 CONNECT with the RFC 1929 username/password sub-negotiation --
enough to prove the tunnel chains through it on every egress path: the SSH control
connection (JSch ProxySOCKS5), plain HTTP (jetty-client Socks5Proxy) and HTTPS
CONNECT (Socks5HandshakeConnection).

Each tunnel request is logged as "CONNECT host:port" so the harness can assert the
traffic really went this way, in the same shape upstream_proxy.py logs it.

With a second argument of user:password it demands authentication and refuses the
no-auth method. That is what makes the credential path testable end to end.

    socks5_proxy.py PORT [user:password]
"""
import select
import socket
import socketserver
import struct
import sys

REQUIRED_CREDENTIALS = None

VERSION = 5
AUTH_NONE = 0x00
AUTH_USERPASS = 0x02
AUTH_UNACCEPTABLE = 0xFF
CMD_CONNECT = 0x01
ATYP_IPV4 = 0x01
ATYP_DOMAIN = 0x03
ATYP_IPV6 = 0x04


def log(fmt, *args):
    sys.stderr.write("[socks5] " + (fmt % args if args else fmt) + "\n")
    sys.stderr.flush()


def read_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError("client closed during handshake")
        buf += chunk
    return buf


def pump(a, b):
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 30)
            if not r:
                return
            for s in r:
                data = s.recv(65536)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    except OSError:
        pass


class Socks5(socketserver.BaseRequestHandler):

    def handle(self):
        try:
            if not self.negotiate_method():
                return
            self.tunnel()
        except OSError as e:
            log("connection failed: %s", e)

    def negotiate_method(self):
        """Greeting and, when credentials are required, the sub-negotiation."""
        version, count = struct.unpack("!BB", read_exactly(self.request, 2))
        if version != VERSION:
            log("AUTH-REJECTED bad version %d", version)
            return False
        offered = set(read_exactly(self.request, count))

        if REQUIRED_CREDENTIALS is None:
            self.request.sendall(struct.pack("!BB", VERSION, AUTH_NONE))
            return True

        if AUTH_USERPASS not in offered:
            # Refusing here is the point: a client that only offered "no auth" must not get
            # through, so the harness can tell an authenticated run from an unauthenticated one.
            log("AUTH-REJECTED client offered no username/password method")
            self.request.sendall(struct.pack("!BB", VERSION, AUTH_UNACCEPTABLE))
            return False

        self.request.sendall(struct.pack("!BB", VERSION, AUTH_USERPASS))
        (subversion,) = struct.unpack("!B", read_exactly(self.request, 1))
        (ulen,) = struct.unpack("!B", read_exactly(self.request, 1))
        user = read_exactly(self.request, ulen).decode("utf-8", "replace")
        (plen,) = struct.unpack("!B", read_exactly(self.request, 1))
        password = read_exactly(self.request, plen).decode("utf-8", "replace")

        if subversion != 0x01 or f"{user}:{password}" != REQUIRED_CREDENTIALS:
            log("AUTH-REJECTED user=%r", user)
            self.request.sendall(struct.pack("!BB", 0x01, 0x01))
            return False

        log("AUTH-OK user=%s", user)
        self.request.sendall(struct.pack("!BB", 0x01, 0x00))
        return True

    def tunnel(self):
        version, command, _, atyp = struct.unpack("!BBBB", read_exactly(self.request, 4))
        if version != VERSION:
            return
        if atyp == ATYP_IPV4:
            host = socket.inet_ntoa(read_exactly(self.request, 4))
        elif atyp == ATYP_IPV6:
            host = socket.inet_ntop(socket.AF_INET6, read_exactly(self.request, 16))
        elif atyp == ATYP_DOMAIN:
            (length,) = struct.unpack("!B", read_exactly(self.request, 1))
            host = read_exactly(self.request, length).decode("ascii")
        else:
            self.reply(0x08)
            return
        (port,) = struct.unpack("!H", read_exactly(self.request, 2))

        # Same wording as upstream_proxy.py, so the harness asserts on one shape.
        log("CONNECT %s:%d", host, port)

        if command != CMD_CONNECT:
            self.reply(0x07)
            return
        try:
            upstream = socket.create_connection((host, port), timeout=15)
        except OSError as e:
            log("upstream connect failed: %s", e)
            self.reply(0x05)
            return

        self.reply(0x00, upstream.getsockname())
        pump(self.request, upstream)
        upstream.close()

    def reply(self, code, bound=None):
        host, port = (bound[0], bound[1]) if bound else ("0.0.0.0", 0)
        try:
            packed = socket.inet_aton(host)
        except OSError:
            packed = socket.inet_aton("0.0.0.0")
        self.request.sendall(
            struct.pack("!BBBB", VERSION, code, 0x00, ATYP_IPV4) + packed
            + struct.pack("!H", port))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    listen_port = int(sys.argv[1]) if len(sys.argv) > 1 else 1080
    if len(sys.argv) > 2 and sys.argv[2]:
        REQUIRED_CREDENTIALS = sys.argv[2]
        log("requiring username/password authentication")
    Server(("127.0.0.1", listen_port), Socks5).serve_forever()
