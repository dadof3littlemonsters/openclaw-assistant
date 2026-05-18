#!/usr/bin/env python3
"""agentvoice-pair / hermes-pair — print one QR for Agent Voice setup.

The helper is intentionally host-side and mostly automatic:

1. Detect whether Hermes, OpenClaw, and Tailscale are installed locally.
2. Read common env vars, .env files, and config JSON files before asking.
3. Include detected Hermes/OpenClaw/Tailscale candidates automatically.
4. Print one Agent Voice setup JSON QR. Scanning that single QR in Agent Voice
   can configure both Hermes Agent and OpenClaw. A deep-link fallback is also
   printed for external camera apps.

It keeps the older Hermes-only CLI options for scripted use.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import urllib.parse
from pathlib import Path
from typing import Any, Iterable, List, Optional


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


def is_port_open(host: str, port: int, timeout: float = 0.25) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def first_lan_ip() -> Optional[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except Exception:
        return None


def tailscale_cmd() -> Optional[List[str]]:
    cli = shutil.which("tailscale")
    if cli:
        return [cli]
    mac_app_cli = Path("/Applications/Tailscale.app/Contents/MacOS/Tailscale")
    if mac_app_cli.exists() and os.access(mac_app_cli, os.X_OK):
        return [str(mac_app_cli)]
    return None


def tailscale_ip() -> Optional[str]:
    cmd = tailscale_cmd()
    if not cmd:
        return None
    out = run([*cmd, "ip", "-4"])
    if not out:
        return None
    for line in out.splitlines():
        candidate = line.strip()
        try:
            ipaddress.ip_address(candidate)
        except ValueError:
            continue
        return candidate
    return None


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}


def unquote_env_value(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def read_env_file(path: Path) -> dict:
    try:
        lines = path.read_text().splitlines()
    except Exception:
        return {}
    result = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        if stripped.startswith("export "):
            stripped = stripped[len("export "):].lstrip()
        key, value = stripped.split("=", 1)
        key = key.strip()
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
            result[key] = unquote_env_value(value.split(" #", 1)[0])
    return result


def iter_config_values(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key), child
            yield from iter_config_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_config_values(child)


def unique_append(values: List[str], value: Optional[str]) -> None:
    if value and value not in values:
        values.append(value)


def readable_existing(paths: Iterable[Path]) -> List[Path]:
    result = []
    for path in paths:
        try:
            if path.expanduser().is_file():
                result.append(path.expanduser())
        except Exception:
            pass
    return result


HERMES_JSON_PATHS = (
    Path.home() / ".config" / "hermes" / "config.json",
    Path.home() / ".config" / "hermes" / "settings.json",
    Path.home() / ".hermes" / "config.json",
    Path.home() / ".hermes" / "settings.json",
    Path.home() / "Library" / "Application Support" / "Hermes" / "config.json",
    Path.cwd() / "hermes.json",
)

HERMES_ENV_PATHS = (
    Path.home() / ".config" / "hermes" / ".env",
    Path.home() / ".hermes" / ".env",
    Path.home() / ".hermes" / "env",
    Path.cwd() / ".env",
    Path.cwd() / ".env.local",
    Path.cwd() / ".env.hermes",
)

OPENCLAW_JSON_PATHS = (
    Path.home() / ".config" / "openclaw" / "config.json",
    Path.home() / ".config" / "openclaw" / "settings.json",
    Path.home() / ".openclaw" / "openclaw.json",
    Path.home() / ".openclaw" / "config.json",
    Path.home() / ".openclaw" / "settings.json",
    Path.home() / ".openclaw" / "gateway.json",
    Path.cwd() / "openclaw.json",
)

OPENCLAW_ENV_PATHS = (
    Path.home() / ".config" / "openclaw" / ".env",
    Path.home() / ".openclaw" / ".env",
    Path.cwd() / ".env",
    Path.cwd() / ".env.local",
    Path.cwd() / ".env.openclaw",
)


def env_lookup(keys: Iterable[str], envs: Iterable[dict] = ()) -> Optional[str]:
    for key in keys:
        if os.environ.get(key):
            return os.environ[key].strip()
    for env in envs:
        for key in keys:
            value = str(env.get(key, "")).strip()
            if value:
                return value
    return None


def json_lookup(paths: Iterable[Path], keys: set[str]) -> Optional[str]:
    lowered = {key.lower() for key in keys}
    for path in readable_existing(paths):
        cfg = read_json(path)
        for key, value in iter_config_values(cfg):
            if key.lower() in lowered:
                text = str(value).strip()
                if text:
                    return text
    return None


def discover_hermes_url() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(
        ("HERMES_API_SERVER_URL", "HERMES_URL", "AGENT_VOICE_HERMES_URL", "HERMES_BASE_URL"),
        envs,
    )
    if value:
        return value
    value = json_lookup(
        HERMES_JSON_PATHS,
        {"apiServerUrl", "api_server_url", "url", "baseUrl", "base_url", "serverUrl", "server_url"},
    )
    if value and value.startswith(("http://", "https://")):
        return value
    return None


def discover_hermes_key() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    return env_lookup(
        ("HERMES_API_KEY", "HERMES_API_SERVER_KEY", "API_SERVER_KEY", "AGENT_VOICE_HERMES_KEY"),
        envs,
    ) or json_lookup(
        HERMES_JSON_PATHS,
        {"apiKey", "api_key", "apiServerKey", "api_server_key", "key", "token", "bearerToken"},
    )


def discover_hermes_model() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    return env_lookup(("HERMES_MODEL", "HERMES_MODEL_NAME", "AGENT_VOICE_HERMES_MODEL"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"model", "modelName", "model_name", "defaultModel", "default_model"},
    )


def discover_hermes_runs_api() -> Optional[bool]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(("HERMES_USE_RUNS_API", "HERMES_RUNS_API", "AGENT_VOICE_HERMES_RUNS"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"useRunsApi", "use_runs_api", "runsApi", "runs_api"},
    )
    if value is None:
        return None
    return truthy(value)


def discover_hermes_streaming() -> Optional[bool]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(("HERMES_STREAMING", "HERMES_USE_STREAMING", "AGENT_VOICE_HERMES_STREAMING"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"streaming", "useStreaming", "use_streaming"},
    )
    if value is None:
        return None
    return truthy(value)


def discover_openclaw_setup_code() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(OPENCLAW_ENV_PATHS)]
    value = env_lookup(("OPENCLAW_SETUP_CODE", "AGENT_VOICE_OPENCLAW_SETUP_CODE"), envs) or json_lookup(
        OPENCLAW_JSON_PATHS,
        {"setupCode", "setup_code", "gatewaySetupCode", "gateway_setup_code", "pairingCode", "pairing_code"},
    )
    return value.strip() if value else None


def normalize_url(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        return raw
    if not raw.startswith(("http://", "https://")):
        raw = "http://" + raw
    return raw.rstrip("/")


def is_loopback_url(raw: str) -> bool:
    parsed = urllib.parse.urlparse(normalize_url(raw))
    host = (parsed.hostname or "").lower()
    return host in {"localhost", "::1"} or host.startswith("127.")


def append_host_variant(urls: List[str], host: Optional[str], port: int) -> None:
    if not host:
        return
    candidate = f"http://{host}:{port}"
    if candidate not in urls:
        urls.append(candidate)


def ordered_android_urls(candidates: Iterable[str]) -> List[str]:
    unique = comma_urls(candidates)
    remote = [url for url in unique if not is_loopback_url(url)]
    local = [url for url in unique if is_loopback_url(url)]
    return remote + local


def gateway_url_from_openclaw_config(cfg: dict) -> Optional[str]:
    gateway = cfg.get("gateway") if isinstance(cfg.get("gateway"), dict) else {}
    for key in ("publicUrl", "public_url", "url"):
        value = str(gateway.get(key) or cfg.get(key) or "").strip()
        if value.startswith(("http://", "https://", "ws://", "wss://")):
            return value.replace("ws://", "http://").replace("wss://", "https://")
    remote = gateway.get("remote") if isinstance(gateway.get("remote"), dict) else {}
    value = str(remote.get("url") or "").strip()
    if value.startswith(("http://", "https://", "ws://", "wss://")):
        return value.replace("ws://", "http://").replace("wss://", "https://")
    control = gateway.get("controlUi") if isinstance(gateway.get("controlUi"), dict) else {}
    origins = control.get("allowedOrigins") if isinstance(control.get("allowedOrigins"), list) else []
    ts_origin = next((str(origin) for origin in origins if ".ts.net" in str(origin) and str(origin).startswith("https://")), None)
    if ts_origin:
        return ts_origin.rstrip("/")
    port = int(gateway.get("port") or 18789)
    ts_ip = tailscale_ip()
    if ts_ip:
        return f"http://{ts_ip}:{port}"
    lan = first_lan_ip()
    if lan:
        return f"http://{lan}:{port}"
    return f"http://127.0.0.1:{port}"


def encode_setup_code(payload: dict) -> str:
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def openclaw_setup_code_from_config() -> Optional[str]:
    for path in readable_existing(OPENCLAW_JSON_PATHS):
        cfg = read_json(path)
        gateway = cfg.get("gateway") if isinstance(cfg.get("gateway"), dict) else {}
        auth = gateway.get("auth") if isinstance(gateway.get("auth"), dict) else {}
        url = gateway_url_from_openclaw_config(cfg)
        if not url:
            continue
        payload: dict = {"url": url}
        mode = str(auth.get("mode") or "").lower()
        token = str(auth.get("token") or "").strip()
        password = str(auth.get("password") or "").strip()
        if mode == "password" and password:
            payload["password"] = password
        elif token:
            payload["token"] = token
        elif password:
            payload["password"] = password
        else:
            continue
        return encode_setup_code(payload)
    return None


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


def openclaw_setup_code_from_local_install() -> Optional[str]:
    # Prefer local config credentials when available. Current OpenClaw `qr`
    # emits a bootstrapToken for device pairing; Agent Voice also opens an
    # operator session, so include both the local password and bootstrapToken
    # when we can resolve them on the user's own machine.
    config_code = openclaw_setup_code_from_config()
    cli_code = openclaw_setup_code_from_cli()
    if not config_code:
        return cli_code
    if not cli_code:
        return config_code
    try:
        config_payload = json.loads(base64.urlsafe_b64decode(config_code + "=" * ((4 - len(config_code) % 4) % 4)).decode("utf-8"))
        cli_payload = json.loads(base64.urlsafe_b64decode(cli_code + "=" * ((4 - len(cli_code) % 4) % 4)).decode("utf-8"))
    except Exception:
        return config_code
    merged = dict(config_payload)
    if cli_payload.get("bootstrapToken"):
        merged["bootstrapToken"] = cli_payload["bootstrapToken"]
    return encode_setup_code(merged)


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


def build_pairing_json(
    hermes_urls: List[str],
    hermes_key: Optional[str],
    model: Optional[str],
    use_runs_api: bool,
    streaming: bool,
    display_name: Optional[str],
    openclaw_setup_code: Optional[str],
) -> str:
    payload: dict = {"type": "agent_voice_setup", "version": 1}
    if hermes_urls:
        hermes: dict = {
            "urls": hermes_urls,
            "model": model or "hermes-agent",
            "runs": use_runs_api,
            "streaming": streaming,
        }
        if hermes_key:
            hermes["key"] = hermes_key
        if display_name:
            hermes["name"] = display_name
        payload["hermes"] = hermes
    if openclaw_setup_code:
        payload["openclaw"] = {"setupCode": openclaw_setup_code}
    if len(payload) <= 2:
        raise SystemExit("Nothing to pair. Include Hermes, OpenClaw, or both.")
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


QR_ECC_CODEWORDS_PER_BLOCK_LOW = [
    -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28,
    30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30,
    30, 30, 30, 30, 30,
]
QR_NUM_ERROR_CORRECTION_BLOCKS_LOW = [
    -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8,
    9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24,
    25,
]


def qr_raw_codewords(version: int) -> int:
    result = (16 * version + 128) * version + 64
    if version >= 2:
        num_align = version // 7 + 2
        result -= (25 * num_align - 10) * num_align - 55
    if version >= 7:
        result -= 36
    return result // 8


def qr_alignment_positions(version: int) -> List[int]:
    if version == 1:
        return []
    num_align = version // 7 + 2
    step = 26 if version == 32 else ((version * 4 + num_align * 2 + 1) // (num_align * 2 - 2)) * 2
    result = [6]
    pos = version * 4 + 10
    for _ in range(num_align - 1):
        result.insert(1, pos)
        pos -= step
    return result


def qr_reed_solomon_multiply(x: int, y: int) -> int:
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def qr_reed_solomon_divisor(degree: int) -> List[int]:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = qr_reed_solomon_multiply(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = qr_reed_solomon_multiply(root, 0x02)
    return result


def qr_reed_solomon_remainder(data: List[int], divisor: List[int]) -> List[int]:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= qr_reed_solomon_multiply(coef, factor)
    return result


def qr_append_bits(bits: List[int], value: int, length: int) -> None:
    for i in range(length - 1, -1, -1):
        bits.append((value >> i) & 1)


def qr_encode_codewords(payload: str) -> tuple[int, List[int]]:
    data = payload.encode("utf-8")
    for version in range(1, 41):
        data_capacity_bits = (
            qr_raw_codewords(version)
            - QR_ECC_CODEWORDS_PER_BLOCK_LOW[version] * QR_NUM_ERROR_CORRECTION_BLOCKS_LOW[version]
        ) * 8
        length_bits = 8 if version <= 9 else 16
        bits: List[int] = []
        qr_append_bits(bits, 0x4, 4)
        qr_append_bits(bits, len(data), length_bits)
        for b in data:
            qr_append_bits(bits, b, 8)
        if len(bits) > data_capacity_bits:
            continue
        qr_append_bits(bits, 0, min(4, data_capacity_bits - len(bits)))
        while len(bits) % 8:
            bits.append(0)
        pad_byte = 0xEC
        while len(bits) < data_capacity_bits:
            qr_append_bits(bits, pad_byte, 8)
            pad_byte ^= 0xEC ^ 0x11
        data_codewords = [sum(bits[i + j] << (7 - j) for j in range(8)) for i in range(0, len(bits), 8)]
        return version, qr_add_ecc_and_interleave(version, data_codewords)
    raise SystemExit("Pairing payload is too large for one QR. Reduce endpoints or omit optional tokens.")


def qr_add_ecc_and_interleave(version: int, data: List[int]) -> List[int]:
    num_blocks = QR_NUM_ERROR_CORRECTION_BLOCKS_LOW[version]
    block_ecc_len = QR_ECC_CODEWORDS_PER_BLOCK_LOW[version]
    raw_codewords = qr_raw_codewords(version)
    num_short_blocks = num_blocks - raw_codewords % num_blocks
    short_block_data_len = raw_codewords // num_blocks - block_ecc_len
    divisor = qr_reed_solomon_divisor(block_ecc_len)
    blocks: List[tuple[List[int], List[int]]] = []
    offset = 0
    for i in range(num_blocks):
        data_len = short_block_data_len + (0 if i < num_short_blocks else 1)
        block_data = data[offset: offset + data_len]
        offset += data_len
        blocks.append((block_data, qr_reed_solomon_remainder(block_data, divisor)))
    result: List[int] = []
    max_data_len = max(len(block_data) for block_data, _ in blocks)
    for i in range(max_data_len):
        for block_data, _ in blocks:
            if i < len(block_data):
                result.append(block_data[i])
    for i in range(block_ecc_len):
        for _, block_ecc in blocks:
            result.append(block_ecc[i])
    return result


def qr_mask_bit(mask: int, x: int, y: int) -> bool:
    if mask == 0:
        return (x + y) % 2 == 0
    raise ValueError(mask)


def qr_draw_function_patterns(modules: List[List[Optional[bool]]], is_function: List[List[bool]], version: int) -> None:
    size = len(modules)

    def set_function(x: int, y: int, dark: bool) -> None:
        modules[y][x] = dark
        is_function[y][x] = True

    def draw_finder(cx: int, cy: int) -> None:
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x = cx + dx
                y = cy + dy
                if 0 <= x < size and 0 <= y < size:
                    dist = max(abs(dx), abs(dy))
                    set_function(x, y, dist != 2 and dist != 4)

    draw_finder(3, 3)
    draw_finder(size - 4, 3)
    draw_finder(3, size - 4)

    align = qr_alignment_positions(version)
    for cy in align:
        for cx in align:
            if is_function[cy][cx]:
                continue
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    set_function(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    for i in range(size):
        if not is_function[6][i]:
            set_function(i, 6, i % 2 == 0)
        if not is_function[i][6]:
            set_function(6, i, i % 2 == 0)

    set_function(8, size - 8, True)
    for i in range(9):
        if i != 6:
            set_function(8, i, False)
            set_function(i, 8, False)
    for i in range(8):
        set_function(size - 1 - i, 8, False)
        set_function(8, size - 1 - i, False)

    if version >= 7:
        rem = version
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = (version << 12) | rem
        for i in range(18):
            bit = ((bits >> i) & 1) != 0
            a = size - 11 + i % 3
            b = i // 3
            set_function(a, b, bit)
            set_function(b, a, bit)


def qr_draw_format_bits(modules: List[List[Optional[bool]]], is_function: List[List[bool]], mask: int) -> None:
    size = len(modules)
    data = (1 << 3) | mask  # Low error correction.
    rem = data
    for _ in range(10):
        rem = (rem << 1) ^ ((rem >> 9) * 0x537)
    bits = ((data << 10) | rem) ^ 0x5412

    def set_function(x: int, y: int, dark: bool) -> None:
        modules[y][x] = dark
        is_function[y][x] = True

    for i in range(6):
        set_function(8, i, ((bits >> i) & 1) != 0)
    set_function(8, 7, ((bits >> 6) & 1) != 0)
    set_function(8, 8, ((bits >> 7) & 1) != 0)
    set_function(7, 8, ((bits >> 8) & 1) != 0)
    for i in range(9, 15):
        set_function(14 - i, 8, ((bits >> i) & 1) != 0)
    for i in range(8):
        set_function(size - 1 - i, 8, ((bits >> i) & 1) != 0)
    for i in range(8, 15):
        set_function(8, size - 15 + i, ((bits >> i) & 1) != 0)
    set_function(8, size - 8, True)


def qr_make_matrix(payload: str) -> List[List[bool]]:
    version, codewords = qr_encode_codewords(payload)
    size = version * 4 + 17
    modules: List[List[Optional[bool]]] = [[None] * size for _ in range(size)]
    is_function = [[False] * size for _ in range(size)]
    qr_draw_function_patterns(modules, is_function, version)

    bit_index = 0
    upward = True
    x = size - 1
    while x >= 1:
        if x == 6:
            x -= 1
        for vert in range(size):
            y = size - 1 - vert if upward else vert
            for dx in range(2):
                xx = x - dx
                if is_function[y][xx]:
                    continue
                dark = False
                if bit_index < len(codewords) * 8:
                    dark = ((codewords[bit_index >> 3] >> (7 - (bit_index & 7))) & 1) != 0
                    bit_index += 1
                modules[y][xx] = dark ^ qr_mask_bit(0, xx, y)
        upward = not upward
        x -= 2

    qr_draw_format_bits(modules, is_function, 0)
    return [[cell is True for cell in row] for row in modules]


def render_qr_without_dependencies(payload: str, compact: bool = True) -> None:
    modules = qr_make_matrix(payload)
    quiet = 1 if compact else 2
    size = len(modules)
    if compact:
        for y in range(-quiet, size + quiet, 2):
            line = []
            for x in range(-quiet, size + quiet):
                top = 0 <= x < size and 0 <= y < size and modules[y][x]
                bottom_y = y + 1
                bottom = 0 <= x < size and 0 <= bottom_y < size and modules[bottom_y][x]
                line.append("█" if top and bottom else "▀" if top else "▄" if bottom else " ")
            print("".join(line))
        return
    for y in range(-quiet, size + quiet):
        line = []
        for x in range(-quiet, size + quiet):
            dark = 0 <= x < size and 0 <= y < size and modules[y][x]
            line.append("██" if dark else "  ")
        print("".join(line))


def render_qr(payload: str, compact: bool = True) -> bool:
    render_qr_without_dependencies(payload, compact = compact)
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
    p.add_argument("--model", help="Hermes model name. Defaults to detected config, then hermes-agent.")
    p.add_argument("--runs", action="store_true", help="Use Hermes Runs API mode.")
    p.add_argument("--chat", dest="runs", action="store_false", help="Use Hermes Chat Completions mode.")
    p.add_argument("--no-stream", dest="streaming", action="store_false", help="Disable Hermes streaming responses.")
    p.add_argument("--stream", dest="streaming", action="store_true", help="Enable Hermes streaming responses.")
    p.add_argument("--name", help="Hermes backend display name.")
    p.add_argument("--openclaw-setup-code", help="OpenClaw setup code from `openclaw qr --setup-code-only`.")
    p.add_argument("--hermes-only", action="store_true", help="Only include Hermes.")
    p.add_argument("--openclaw-only", action="store_true", help="Only include OpenClaw.")
    p.add_argument("--interactive", action="store_true", help="Ask before including detected candidates.")
    p.add_argument("--yes", action="store_true", help="Accept detected defaults without prompts. This is the normal behavior.")
    p.add_argument("--no-lan", action="store_true", help="Do not add LAN endpoint candidates automatically.")
    p.add_argument("--no-tailscale", action="store_true", help="Do not add Tailscale endpoint candidates automatically.")
    p.add_argument("--large-qr", action="store_true", help="Render a larger legacy ASCII QR instead of the compact terminal QR.")
    p.set_defaults(runs=None, streaming=None)
    args = p.parse_args(argv)

    hermes_installed = bool(shutil.which("hermes"))
    openclaw_installed = bool(shutil.which("openclaw"))
    tailscale_installed = bool(tailscale_cmd())
    hermes_port_open = is_port_open("127.0.0.1", 8642)
    openclaw_port_open = is_port_open("127.0.0.1", 18789)
    hermes_key = args.key or discover_hermes_key()
    hermes_model = args.model or discover_hermes_model() or "hermes-agent"
    hermes_runs = args.runs if args.runs is not None else (discover_hermes_runs_api() or False)
    discovered_streaming = discover_hermes_streaming()
    hermes_streaming = args.streaming if args.streaming is not None else (
        True if discovered_streaming is None else discovered_streaming
    )
    discovered_hermes_url = discover_hermes_url()
    discovered_openclaw_setup_code = args.openclaw_setup_code or discover_openclaw_setup_code() or openclaw_setup_code_from_local_install()

    print("Agent Voice pairing helper")
    print(f"  Hermes:   {'found' if hermes_installed else 'not found'}; API port {'open' if hermes_port_open else 'not detected'}")
    print(f"  OpenClaw: {'found' if openclaw_installed else 'not found'}; gateway port {'open' if openclaw_port_open else 'not detected'}")
    print(f"  Tailscale:{' found' if tailscale_installed else ' not found'}")
    if discovered_hermes_url:
        print(f"  Hermes URL: detected {normalize_url(discovered_hermes_url)}")
    if hermes_key:
        print("  Hermes key: detected")
    if discovered_openclaw_setup_code:
        print("  OpenClaw setup code: detected")
    print()

    auto_hermes = bool(args.url or discovered_hermes_url or hermes_key or hermes_installed or hermes_port_open)
    auto_openclaw = bool(discovered_openclaw_setup_code or openclaw_installed or openclaw_port_open)
    if args.hermes_only:
        include_hermes = True
        include_openclaw = False
    elif args.openclaw_only:
        include_hermes = False
        include_openclaw = True
    elif args.interactive:
        include_hermes = ask_yes_no("Include detected Hermes Agent settings in this QR?", auto_hermes)
        include_openclaw = ask_yes_no("Include detected OpenClaw settings in this QR?", auto_openclaw)
    else:
        include_hermes = auto_hermes
        include_openclaw = auto_openclaw
    include_tailscale = tailscale_installed and not args.no_tailscale and (not args.interactive or ask_yes_no("Include Tailscale/VPN endpoint candidates?", True))

    hermes_urls: List[str] = []
    if include_hermes:
        hermes_candidates: List[str] = []
        hermes_candidates.extend(comma_urls(args.url))
        if discovered_hermes_url:
            unique_append(hermes_candidates, normalize_url(discovered_hermes_url))
        if not hermes_candidates:
            if hermes_installed:
                unique_append(hermes_candidates, "http://127.0.0.1:8642")
            elif args.interactive:
                raw = input("Hermes API Server URL [http://127.0.0.1:8642]: ").strip() or "http://127.0.0.1:8642"
                unique_append(hermes_candidates, normalize_url(raw))
        if hermes_port_open:
            unique_append(hermes_candidates, "http://127.0.0.1:8642")
        if include_tailscale:
            append_host_variant(hermes_candidates, tailscale_ip(), 8642)
        lan = first_lan_ip()
        if lan and not args.no_lan and (not args.interactive or ask_yes_no(f"Also include LAN URL http://{lan}:8642?", True)):
            append_host_variant(hermes_candidates, lan, 8642)
        hermes_urls = ordered_android_urls(hermes_candidates)
        if hermes_key is None and args.interactive:
            hermes_key = getpass.getpass("Hermes API key (blank if none): ").strip() or None
        if not hermes_urls:
            print("Hermes was detected, but no URL could be resolved. Skipping Hermes; run with --interactive to enter it manually.", file=sys.stderr)
            include_hermes = False

    openclaw_setup_code: Optional[str] = None
    if include_openclaw:
        openclaw_setup_code = discovered_openclaw_setup_code or openclaw_setup_code_from_local_install()
        if not openclaw_setup_code and args.interactive:
            print("Could not read OpenClaw setup code automatically.")
            print("Run `openclaw qr --setup-code-only` and paste the setup code below.")
            openclaw_setup_code = input("OpenClaw setup code: ").strip() or None
        if not openclaw_setup_code:
            print("OpenClaw was detected, but no setup code could be resolved. Skipping OpenClaw.", file=sys.stderr)
            include_openclaw = False

    if not include_hermes and not include_openclaw:
        raise SystemExit(
            "No pairable backend was found automatically. "
            "Start Hermes/OpenClaw or run `agentvoice-pair --interactive` to enter values manually."
        )

    qr_payload = build_pairing_json(
        hermes_urls=hermes_urls,
        hermes_key=hermes_key,
        model=hermes_model,
        use_runs_api=hermes_runs,
        streaming=hermes_streaming,
        display_name=args.name,
        openclaw_setup_code=openclaw_setup_code,
    )
    deep_link = build_pairing_uri(
        hermes_urls=hermes_urls,
        hermes_key=hermes_key,
        model=hermes_model,
        use_runs_api=hermes_runs,
        streaming=hermes_streaming,
        display_name=args.name,
        openclaw_setup_code=openclaw_setup_code,
    )

    print("\nScan this one QR inside Agent Voice:\n")
    rendered = render_qr(qr_payload, compact = not args.large_qr)
    if not rendered:
        print("(QR rendering unavailable on this machine.)")
    print("\nQR payload for Agent Voice in-app scanner:")
    print(f"  {qr_payload}\n")
    print("External-camera fallback link:")
    print(f"  {deep_link}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
