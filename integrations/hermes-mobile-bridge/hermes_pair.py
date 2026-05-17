#!/usr/bin/env python3
"""agentvoice-pair / hermes-pair — print one QR for Agent Voice setup.

The helper is intentionally host-side and interactive:

1. Detect whether Hermes, OpenClaw, and Tailscale are installed locally.
2. Ask which backends to include in this pairing QR.
3. Optionally add Tailscale/VPN endpoint candidates for use away from home.
4. Print one `agentvoice://setup?...` QR. Scanning that single QR in Agent
   Voice can configure both Hermes Agent and OpenClaw.

It keeps the older Hermes-only CLI options for scripted use.
"""
from __future__ import annotations

import argparse
import getpass
import json
import os
import shutil
import socket
import subprocess
import sys
import urllib.parse
from pathlib import Path
from typing import Iterable, List, Optional


def truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def ask_yes_no(prompt: str, default: bool) -> bool:
    suffix = "Y/n" if default else "y/N"
    raw = input(f"{prompt} [{suffix}] ").strip()
    if not raw:
        return default
    return truthy(raw)


def run(cmd: List[str], timeout: int = 8) -> Optional[str]:
    try:
        out = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=timeout)
        return out.stdout.strip()
    except Exception:
        return None


def first_lan_ip() -> Optional[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except Exception:
        return None


def tailscale_ip() -> Optional[str]:
    if not shutil.which("tailscale"):
        return None
    out = run(["tailscale", "ip", "-4"])
    return out.splitlines()[0].strip() if out else None


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}


def discover_hermes_url() -> Optional[str]:
    for key in ("HERMES_API_SERVER_URL", "HERMES_URL", "AGENT_VOICE_HERMES_URL"):
        if os.environ.get(key):
            return os.environ[key].strip()
    for path in (
        Path.home() / ".config" / "hermes" / "config.json",
        Path.home() / ".hermes" / "config.json",
        Path.cwd() / "hermes.json",
    ):
        cfg = read_json(path)
        for key in ("apiServerUrl", "api_server_url", "url", "baseUrl", "base_url"):
            value = str(cfg.get(key, "")).strip()
            if value.startswith(("http://", "https://")):
                return value
    return None


def discover_hermes_key() -> Optional[str]:
    for key in ("HERMES_API_KEY", "HERMES_API_SERVER_KEY", "API_SERVER_KEY", "AGENT_VOICE_HERMES_KEY"):
        if os.environ.get(key):
            return os.environ[key].strip()
    for path in (
        Path.home() / ".config" / "hermes" / "config.json",
        Path.home() / ".hermes" / "config.json",
        Path.cwd() / "hermes.json",
    ):
        cfg = read_json(path)
        for key in ("apiKey", "api_key", "apiServerKey", "api_server_key", "key", "token"):
            value = str(cfg.get(key, "")).strip()
            if value:
                return value
    return None


def normalize_url(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        return raw
    if not raw.startswith(("http://", "https://")):
        raw = "http://" + raw
    return raw.rstrip("/")


def append_host_variant(urls: List[str], host: Optional[str], port: int) -> None:
    if not host:
        return
    candidate = f"http://{host}:{port}"
    if candidate not in urls:
        urls.append(candidate)


def openclaw_setup_code_from_cli() -> Optional[str]:
    if not shutil.which("openclaw"):
        return None
    for cmd in (["openclaw", "qr", "--setup-code-only"], ["openclaw", "qr", "--json"]):
        out = run(cmd)
        if not out:
            continue
        try:
            obj = json.loads(out)
            code = str(obj.get("setupCode", "")).strip()
            if code:
                return code
        except Exception:
            pass
        # `--setup-code-only` is expected to print the raw base64url payload.
        first = out.splitlines()[0].strip()
        if first and " " not in first and "{" not in first:
            return first
    return None


def build_pairing_uri(
    hermes_urls: List[str],
    hermes_key: Optional[str],
    model: Optional[str],
    use_runs_api: bool,
    streaming: bool,
    display_name: Optional[str],
    openclaw_setup_code: Optional[str],
) -> str:
    params: List[tuple[str, str]] = []
    for url in hermes_urls:
        if not url.startswith(("http://", "https://")):
            raise SystemExit(f"Hermes URL must start with http:// or https://: {url!r}")
        params.append(("hu", url))
    if hermes_key:
        params.append(("hk", hermes_key))
    if model:
        params.append(("hm", model))
    if hermes_urls:
        params.append(("hr", "1" if use_runs_api else "0"))
        params.append(("hs", "1" if streaming else "0"))
    if display_name:
        params.append(("hn", display_name))
    if openclaw_setup_code:
        params.append(("oc", openclaw_setup_code))
    if not params:
        raise SystemExit("Nothing to pair. Include Hermes, OpenClaw, or both.")
    return "agentvoice://setup?" + urllib.parse.urlencode(params)


def render_qr(payload: str) -> bool:
    try:
        import qrcode  # type: ignore
    except ImportError:
        print("warning: QR support was not installed. Re-run:", file=sys.stderr)
        print("  curl -fsSL https://raw.githubusercontent.com/Codename-11/hermes-relay/main/install.sh | bash", file=sys.stderr)
        return False
    qr = qrcode.QRCode(border=1)
    qr.add_data(payload)
    qr.make(fit=True)
    qr.print_ascii(tty=sys.stdout.isatty())
    return True


def comma_urls(values: Iterable[str]) -> List[str]:
    urls: List[str] = []
    for value in values:
        for part in value.split(","):
            url = normalize_url(part)
            if url and url not in urls:
                urls.append(url)
    return urls


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="agentvoice-pair", description=__doc__.splitlines()[0])
    p.add_argument("--url", action="append", default=[], help="Hermes URL. Repeat or comma-separate for LAN/Tailscale/public.")
    p.add_argument("--key", help="Hermes API key. Defaults to common env/config values, then prompts.")
    p.add_argument("--model", default="hermes-agent", help="Hermes model name.")
    p.add_argument("--runs", action="store_true", help="Use Hermes Runs API mode.")
    p.add_argument("--no-stream", dest="streaming", action="store_false", help="Disable Hermes streaming responses.")
    p.add_argument("--name", help="Hermes backend display name.")
    p.add_argument("--openclaw-setup-code", help="OpenClaw setup code from `openclaw qr --setup-code-only`.")
    p.add_argument("--hermes-only", action="store_true", help="Only include Hermes.")
    p.add_argument("--openclaw-only", action="store_true", help="Only include OpenClaw.")
    p.add_argument("--yes", action="store_true", help="Accept defaults for prompts.")
    p.set_defaults(streaming=True)
    args = p.parse_args(argv)

    hermes_installed = bool(shutil.which("hermes"))
    openclaw_installed = bool(shutil.which("openclaw"))
    tailscale_installed = bool(shutil.which("tailscale"))

    print("Agent Voice pairing helper")
    print(f"  Hermes:   {'found' if hermes_installed else 'not found'}")
    print(f"  OpenClaw: {'found' if openclaw_installed else 'not found'}")
    print(f"  Tailscale:{' found' if tailscale_installed else ' not found'}")
    print()

    include_hermes = not args.openclaw_only and (args.hermes_only or args.yes or ask_yes_no("Include Hermes Agent in this QR?", hermes_installed or bool(args.url)))
    include_openclaw = not args.hermes_only and (args.openclaw_only or args.yes or ask_yes_no("Include OpenClaw in this QR?", openclaw_installed))
    include_tailscale = tailscale_installed and (args.yes or ask_yes_no("Include Tailscale/VPN endpoint candidates?", True))

    hermes_urls: List[str] = []
    hermes_key: Optional[str] = None
    if include_hermes:
        hermes_urls = comma_urls(args.url)
        discovered_url = discover_hermes_url()
        if discovered_url and discovered_url not in hermes_urls:
            hermes_urls.append(normalize_url(discovered_url))
        if not hermes_urls:
            raw = input("Hermes API Server URL [http://127.0.0.1:8642]: ").strip() or "http://127.0.0.1:8642"
            hermes_urls.append(normalize_url(raw))
        if include_tailscale:
            append_host_variant(hermes_urls, tailscale_ip(), 8642)
        lan = first_lan_ip()
        if lan and (args.yes or ask_yes_no(f"Also include LAN URL http://{lan}:8642?", True)):
            append_host_variant(hermes_urls, lan, 8642)
        hermes_key = args.key or discover_hermes_key()
        if hermes_key is None and not args.yes:
            hermes_key = getpass.getpass("Hermes API key (blank if none): ").strip() or None

    openclaw_setup_code: Optional[str] = None
    if include_openclaw:
        openclaw_setup_code = args.openclaw_setup_code or openclaw_setup_code_from_cli()
        if not openclaw_setup_code and not args.yes:
            print("Could not read OpenClaw setup code automatically.")
            print("Run `openclaw qr --setup-code-only` and paste the setup code below.")
            openclaw_setup_code = input("OpenClaw setup code: ").strip() or None

    payload = build_pairing_uri(
        hermes_urls=hermes_urls,
        hermes_key=hermes_key,
        model=args.model,
        use_runs_api=args.runs,
        streaming=args.streaming,
        display_name=args.name,
        openclaw_setup_code=openclaw_setup_code,
    )

    print("\nScan this one QR inside Agent Voice:\n")
    rendered = render_qr(payload)
    if not rendered:
        print("(QR rendering unavailable on this machine.)")
    print("\nOr open this link on the phone directly:")
    print(f"  {payload}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
