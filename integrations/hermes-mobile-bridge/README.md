# hermes-pair (PC helper)

The fastest way to add a Hermes backend to AgentVoice on your phone:

```bash
pip install qrcode
python integrations/hermes-mobile-bridge/hermes_pair.py \
    --url http://192.168.1.42:8642 \
    --key sk-xxxx
```

It prints an ASCII QR encoding `agentvoice://hermes/setup?…`. Point your
phone's stock camera at it — AgentVoice opens with the connection
pre-filled, you tap **Add**, done. Multi-network: pass `--url` more than
once (LAN + Tailscale + public) and AgentVoice will race them on every
connect.

---

# hermes-mobile-bridge

Python helper for calling the **Agent Voice for Android** Mobile Bridge
from a Hermes Agent toolchain.

The bridge itself is documented at
[`docs/hermes-mobile-bridge.md`](../../docs/hermes-mobile-bridge.md).
This package gives Hermes (or any Python tool) a clean SDK over its
`/health`, `/manifest`, `/execute`, and `/cancel/{id}` endpoints.

## Install

Drop `mobile_bridge.py` into your Hermes tool directory, or
`pip install requests` and import it directly.

## Configure

The helper reads two environment variables:

| Variable                    | Description                                |
|-----------------------------|--------------------------------------------|
| `AGENT_VOICE_BRIDGE_URL`    | e.g. `http://192.168.1.42:8787`            |
| `AGENT_VOICE_BRIDGE_TOKEN`  | The token shown in Agent Voice Settings   |

Never commit the token; it grants execute access to opt-in capabilities.

## Examples

```python
from mobile_bridge import MobileBridge

bridge = MobileBridge()  # picks up env vars
print(bridge.health())
print(bridge.get_manifest())
print(bridge.execute("device.info"))
```

```python
# Launch an app — assumes the user has approved apps.launch in
# Agent Voice and is on hand to confirm the medium-risk prompt.
bridge.execute("apps.launch", {"packageName": "com.android.settings"})
```

## Error handling

Every helper raises `MobileBridgeError` on transport failures or
non-2xx responses. `execute` additionally raises if the bridge
returned `status: "failed"`, surfacing the original `code` and
`message` so the LLM can see exactly what went wrong (for example
`unsupported_capability` or `approval_required`).
