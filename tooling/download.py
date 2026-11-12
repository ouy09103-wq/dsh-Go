#!/usr/bin/env python3
"""Minimal streaming downloader: download.py URL DEST [TOKEN].

A fallback for hosts without curl (the phones this repo is developed on).
Exits non-zero when the server answers with an error status.
"""
import sys
import urllib.error
import urllib.request


def main() -> int:
    url, dest = sys.argv[1], sys.argv[2]
    token = sys.argv[3] if len(sys.argv) > 3 else ""
    request = urllib.request.Request(url, headers={"User-Agent": "dsh-go-build"})
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=120) as src, open(dest, "wb") as out:
            while True:
                chunk = src.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
    except urllib.error.HTTPError as error:
        print(f"download: {url} -> {error.code}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
