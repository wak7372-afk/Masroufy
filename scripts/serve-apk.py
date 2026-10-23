#!/usr/bin/env python3
"""Serve one fixed APK over a local network, without exposing project files."""

import argparse
import hashlib
import html
import ipaddress
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


APK_VERSION = "0.6.0"
APK_FILE = Path(__file__).resolve().parent.parent / "dist" / f"Masroofi-{APK_VERSION}-debug.apk"
APK_URL = f"/Masroofi-{APK_VERSION}.apk"
PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(network)
    for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
)


def local_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError("Use a numeric local IPv4 address.") from error
    if not any(address in network for network in PRIVATE_NETWORKS):
        raise argparse.ArgumentTypeError("Only RFC1918 or loopback IPv4 addresses are allowed.")
    return str(address)


def page(size, digest):
    return f"""<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>تحميل مصروفي</title><style>
body{{font-family:system-ui,sans-serif;background:#eff7f3;color:#163f32;margin:0;padding:24px;line-height:1.8}}
main{{max-width:520px;margin:40px auto;background:white;padding:28px;border-radius:20px}}
h1{{margin-top:0}}a{{display:block;background:#176c50;color:white;text-align:center;text-decoration:none;padding:16px;border-radius:12px;font-weight:700}}
small{{display:block;margin-top:22px;color:#48675b}}code{{overflow-wrap:anywhere;direction:ltr;display:block}}
</style></head><body><main><h1>مصروفي</h1>
<p>النسخة التجريبية {APK_VERSION} لأندرويد. تحتاج إلى أندرويد 8 أو أحدث.</p>
<p>افتح هذه الصفحة على هاتفك، مع اتصال الهاتف والكمبيوتر بشبكة Wi-Fi نفسها. أبقِ الكمبيوتر قيد التشغيل أثناء التحميل.</p>
<a href="{APK_URL}" download="Masroofi-{APK_VERSION}.apk">تحميل تطبيق مصروفي</a>
<p>انتظر اكتمال التحميل، ثم افتح الملف من تنزيلات الهاتف. إذا طلب الهاتف إذن التثبيت من المتصفح، اسمح به لتثبيت هذه النسخة.</p>
<small>الحجم الدقيق: <b dir="ltr">{size:,}</b> بايت.<br>بصمة الملف SHA-256:
<code>{html.escape(digest)}</code></small></main></body></html>""".encode("utf-8")


def make_handler(apk_file, size, digest):
    homepage = page(size, digest)
    etag = '"' + digest + '"'

    class DownloadHandler(BaseHTTPRequestHandler):
        server_version = "MasroofiDownload/1.0"
        sys_version = ""

        def _headers(self, status, content_type, length):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(length))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Cache-Control", "no-cache")

        def _serve(self, head=False):
            if self.path == "/":
                self._headers(200, "text/html; charset=utf-8", len(homepage))
                self.send_header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                self.end_headers()
                if not head:
                    self.wfile.write(homepage)
                return
            if self.path != APK_URL:
                self.send_error(404, "Not found")
                return
            if self.headers.get("If-None-Match") in (etag, "*"):
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                return
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            if_range = self.headers.get("If-Range")
            partial = bool(range_header and (not if_range or if_range == etag))
            if partial:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header)
                try:
                    if not match or not any(match.groups()):
                        raise ValueError("Invalid range")
                    first, last = match.groups()
                    if first:
                        start = int(first)
                        end = min(int(last), size - 1) if last else size - 1
                    else:
                        suffix = int(last)
                        if suffix <= 0:
                            raise ValueError("Invalid suffix")
                        start = max(0, size - suffix)
                    if start >= size or start > end:
                        raise ValueError("Unsatisfiable range")
                except ValueError:
                    self._headers(416, "text/plain; charset=utf-8", 0)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Accept-Ranges", "bytes")
                    self.end_headers()
                    return
            length = end - start + 1
            self._headers(206 if partial else 200, "application/vnd.android.package-archive", length)
            self.send_header("Content-Disposition", f'attachment; filename="Masroofi-{APK_VERSION}.apk"')
            self.send_header("ETag", etag)
            self.send_header("Accept-Ranges", "bytes")
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if head:
                return
            with apk_file.open("rb") as apk:
                apk.seek(start)
                remaining = length
                while remaining:
                    chunk = apk.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

        def do_GET(self):
            try:
                self._serve()
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_HEAD(self):
            self._serve(head=True)

        def log_message(self, format, *args):
            # Log only the known route and status, never request bodies or user data.
            if len(args) >= 2:
                route = "download" if self.path == APK_URL else "page" if self.path == "/" else "other"
                print(f"{self.client_address[0]} {route} {args[1]}", flush=True)

    return DownloadHandler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", type=local_ipv4, default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8768)
    arguments = parser.parse_args()
    if not 1 <= arguments.port <= 65535:
        parser.error("Port must be between 1 and 65535.")
    if not APK_FILE.is_file() or APK_FILE.stat().st_size == 0:
        parser.error(f"The APK is missing or empty: {APK_FILE}")
    size = APK_FILE.stat().st_size
    with APK_FILE.open("rb") as apk:
        digest = hashlib.file_digest(apk, "sha256").hexdigest()
    server = ThreadingHTTPServer((arguments.host, arguments.port), make_handler(APK_FILE, size, digest))
    print(f"Download page: http://{arguments.host}:{arguments.port}/", flush=True)
    print(f"APK: {size} bytes; SHA-256: {digest}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
