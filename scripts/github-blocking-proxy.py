#!/usr/bin/env python3
"""QA-only forward proxy. Blocks GitHub before TLS; never disables or intercepts certificates."""
import argparse
import collections
import http.server
import json
import select
import socket
import socketserver
import threading
import urllib.parse
import urllib.request

counts = collections.Counter()
lock = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    timeout = 15
    def log_message(self, *args):
        pass  # Never log a URI, query, header or cookie.

    def blocked(self, host):
        blocked = host == 'github.com' or host.endswith('.github.com') or host == 'raw.githubusercontent.com'
        with lock:
            counts['blocked' if blocked else 'forwarded'] += 1
        if blocked:
            self.send_error(503, 'GitHub blocked for offline configuration QA')
        return blocked

    def do_CONNECT(self):
        host, _, port = self.path.rpartition(':')
        if self.blocked(host):
            return
        try:
            with socket.create_connection((host, int(port or '443')), timeout=12) as upstream:
                self.send_response(200, 'Connection Established'); self.end_headers()
                peers = [self.connection, upstream]
                while True:
                    readable, _, _ = select.select(peers, [], [], 30)
                    if not readable:
                        return
                    for peer in readable:
                        data = peer.recv(65536)
                        if not data:
                            return
                        (upstream if peer is self.connection else self.connection).sendall(data)
        except (OSError, ValueError):
            return

    def do_GET(self):
        if self.path == '/qa-stats':
            with lock:
                data = json.dumps(dict(counts)).encode()
            self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
            return
        host = urllib.parse.urlsplit(self.path).hostname or ''
        if self.blocked(host):
            return
        try:
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('proxy-connection', 'connection', 'host')}
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(urllib.request.Request(self.path, headers=headers), timeout=12) as response:
                self.send_response(response.status)
                for key, value in response.headers.items():
                    if key.lower() not in ('transfer-encoding', 'connection'):
                        self.send_header(key, value)
                self.end_headers()
                while True:
                    data = response.read(65536)
                    if not data:
                        break
                    self.wfile.write(data)
        except Exception:
            self.send_error(502)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18888)
    args = parser.parse_args()
    Server(('127.0.0.1', args.port), Handler).serve_forever()
