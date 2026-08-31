#!/usr/bin/env python3
"""Minimal authoritative DNS server for exercising the tunnel's --dns option.

Answers A queries for the names it was given and NXDOMAIN for everything else, logging every
question so the harness can prove resolution really went here rather than to the platform
resolver. UDP and TCP both, because dnsjava's SimpleResolver may use either.

Names use the reserved .invalid TLD, which cannot resolve anywhere else -- so a request that
reaches its target proves the custom resolver was consulted, and no assertion depends on what
the machine's real DNS happens to say.

    dns_server.py PORT NAME=IP [NAME=IP ...]
"""
import socket
import socketserver
import struct
import sys

RECORDS = {}

TYPE_A = 1
CLASS_IN = 1
FLAG_RESPONSE = 0x8000
FLAG_AUTHORITATIVE = 0x0400
RCODE_NXDOMAIN = 3


def log(message):
    sys.stderr.write("[dns] " + message + "\n")
    sys.stderr.flush()


def parse_question(query):
    """Returns (name, qtype, offset just past the question)."""
    offset = 12
    labels = []
    while True:
        length = query[offset]
        offset += 1
        if length == 0:
            break
        labels.append(query[offset:offset + length].decode("ascii", "replace"))
        offset += length
    qtype, _qclass = struct.unpack("!HH", query[offset:offset + 4])
    return ".".join(labels), qtype, offset + 4


def build_response(query):
    if len(query) < 13:
        return None
    (request_id,) = struct.unpack("!H", query[:2])
    try:
        name, qtype, question_end = parse_question(query)
    except IndexError:
        return None

    question = query[12:question_end]
    address = RECORDS.get(name.lower()) if qtype == TYPE_A else None
    log("query %s type=%d -> %s" % (name, qtype, address or "NXDOMAIN"))

    if address is None:
        header = struct.pack("!HHHHHH", request_id,
                             FLAG_RESPONSE | FLAG_AUTHORITATIVE | RCODE_NXDOMAIN,
                             1, 0, 0, 0)
        return header + question

    header = struct.pack("!HHHHHH", request_id,
                         FLAG_RESPONSE | FLAG_AUTHORITATIVE, 1, 1, 0, 0)
    answer = (
        b"\xc0\x0c"                                  # pointer back to the question's name
        + struct.pack("!HHIH", TYPE_A, CLASS_IN, 60, 4)
        + socket.inet_aton(address)
    )
    return header + question + answer


class UdpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        query, sock = self.request
        response = build_response(query)
        if response:
            sock.sendto(response, self.client_address)


class TcpHandler(socketserver.BaseRequestHandler):
    def handle(self):
        # DNS over TCP prefixes each message with its length.
        prefix = self.request.recv(2)
        if len(prefix) < 2:
            return
        (length,) = struct.unpack("!H", prefix)
        query = b""
        while len(query) < length:
            chunk = self.request.recv(length - len(query))
            if not chunk:
                return
            query += chunk
        response = build_response(query)
        if response:
            self.request.sendall(struct.pack("!H", len(response)) + response)


class UdpServer(socketserver.ThreadingUDPServer):
    allow_reuse_address = True
    daemon_threads = True


class TcpServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    port = int(sys.argv[1])
    for entry in sys.argv[2:]:
        name, _, ip = entry.partition("=")
        RECORDS[name.lower()] = ip
    log("serving %d name(s) on port %d" % (len(RECORDS), port))

    tcp = TcpServer(("127.0.0.1", port), TcpHandler)
    import threading
    threading.Thread(target=tcp.serve_forever, daemon=True).start()
    UdpServer(("127.0.0.1", port), UdpHandler).serve_forever()
