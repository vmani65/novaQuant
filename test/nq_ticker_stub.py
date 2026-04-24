#!/usr/bin/env python3
"""
Minimal nq-ticker stub for E2E testing.
Runs on port 9192, simulates the two callbacks novaQuant expects from nq-ticker:
  - GET /arm-buffer?openPrice=N  -> waits 2s, fires GET /api/execute-close?currentPrice=N
  - GET /realize-profits/*       -> not needed here (novaQuant calls this, nq-ticker doesn't)

Usage:
  python nq_ticker_stub.py
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
import urllib.request
import threading
import time

NOVAQUANT_BASE = "http://localhost:8080"


class StubHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if parsed.path == "/arm-buffer":
            open_price = params.get("openPrice", ["24500"])[0]
            print(f"[stub] /arm-buffer received openPrice={open_price} — will fire execute-close in 2s")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ARMED")

            def fire_callback():
                time.sleep(2)
                url = f"{NOVAQUANT_BASE}/api/execute-close?currentPrice={open_price}"
                print(f"[stub] firing -> {url}")
                try:
                    urllib.request.urlopen(url, timeout=10)
                    print(f"[stub] execute-close callback sent OK")
                except Exception as e:
                    print(f"[stub] execute-close callback failed: {e}")

            threading.Thread(target=fire_callback, daemon=True).start()

        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass  # suppress default request logs


if __name__ == "__main__":
    print("[nq-ticker stub] listening on port 9192")
    HTTPServer(("", 9192), StubHandler).serve_forever()
