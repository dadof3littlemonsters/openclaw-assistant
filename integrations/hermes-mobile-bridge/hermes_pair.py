#!/usr/bin/env python3
"""hermes-pair — print a QR code that adds this Hermes server to AgentVoice.

Mirrors the `hermes-pair` pattern from hermes-relay
(https://codename-11.github.io/hermes-relay/). Run on the PC that hosts your
Hermes API Server, point it at the URL + API key, and it prints a QR. Scan
that QR from your phone (the stock camera app on modern Android works) and
AgentVoice opens with the connection pre-filled.

Install:
    pip install qrcode

Usage:
    hermes-pair --url http://192.168.1.42:8642 --key sk-xxxx
    hermes-pair --url http://lan.local:8642 \\
                --url http://tailnet:8642 \\
                --url https://relay.example.com \\
                --key sk-xxxx --model hermes-agent --runs

`--url` may be repeated. AgentVoice's HermesEndpointRacer probes them in
parallel on every connect and uses whichever responds first, so the same
pairing works at home / Tailscale / public.
"""
from __future__ import annotations

import argparse
import sys
import urllib.parse
from typing import List, Optional


def build_pairing_uri(
    urls: List[str],
    key: Optional[str],
    model: Optional[str],
    use_runs_api: bool,
    streaming: bool,
    display_name: Optional[str],
) -> str:
    params: List[tuple[str, str]] = []
    for u in urls:
        if not (u.startswith("http://") or u.startswith("https://")):
            raise SystemExit(f"URL must start with http:// or https:// — got {u!r}")
        params.append(("u", u))
    if key:
        params.append(("k", key))
    if model:
        params.append(("m", model))
    params.append(("r", "1" if use_runs_api else "0"))
    params.append(("s", "1" if streaming else "0"))
    if display_name:
        params.append(("n", display_name))
    return "agentvoice://hermes/setup?" + urllib.parse.urlencode(params)


def render_qr(payload: str) -> None:
    try:
        import qrcode  # type: ignore
    except ImportError:
        print("error: the `qrcode` package is required — run `pip install qrcode`", file=sys.stderr)
        raise SystemExit(2)
    qr = qrcode.QRCode(border=1)
    qr.add_data(payload)
    qr.make(fit=True)
    qr.print_ascii(tty=sys.stdout.isatty())


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="hermes-pair", description=__doc__.splitlines()[0])
    p.add_argument("--url", action="append", required=True, help="Hermes API Server URL (repeat for LAN / Tailscale / public)")
    p.add_argument("--key", help="API key (sent as 'Authorization: Bearer …')")
    p.add_argument("--model", default="hermes-agent", help="Model name (default: hermes-agent)")
    p.add_argument("--runs", action="store_true", help="Default to Runs API mode instead of /v1/chat/completions")
    p.add_argument("--no-stream", dest="streaming", action="store_false", help="Disable streaming responses")
    p.add_argument("--name", help="Backend display name shown in AgentVoice (default: 'Hermes Agent')")
    p.set_defaults(streaming=True)
    args = p.parse_args(argv)

    payload = build_pairing_uri(
        urls=args.url,
        key=args.key,
        model=args.model,
        use_runs_api=args.runs,
        streaming=args.streaming,
        display_name=args.name,
    )

    print("Scan this QR with your phone (any QR scanner works):\n")
    render_qr(payload)
    print("\nOr open this link on the phone directly:")
    print(f"  {payload}\n")
    if not args.key:
        print("note: no --key was provided, so AgentVoice will store this Hermes backend without auth.\n", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
