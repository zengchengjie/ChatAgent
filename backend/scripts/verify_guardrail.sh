#!/usr/bin/env bash
set -euo pipefail

# Verify guardrail event is emitted and request terminates (no infinite loop).
#
# Usage:
#   TOKEN=... SESSION_ID=... ./backend/scripts/verify_guardrail.sh
#
# Optional:
#   BASE_URL=http://localhost:8082

BASE_URL="${BASE_URL:-http://localhost:8082}"
TOKEN="${TOKEN:-}"
SESSION_ID="${SESSION_ID:-}"

if [[ -z "$TOKEN" || -z "$SESSION_ID" ]]; then
  echo "Missing TOKEN or SESSION_ID"
  exit 1
fi

# Intentionally ask for many tool calls in one reply to trigger maxToolCallsPerTurn or maxToolCallsTotal.
PROMPT="请严格执行：连续调用 calculator 工具 10 次（每次表达式为 (1+2)*3 ），不要提前停止。"

RAW_STREAM="$(
  curl --silent --show-error --no-buffer --max-time 30 \
    -X POST "${BASE_URL}/api/agent/chat/stream" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"content\":\"${PROMPT}\"}"
)"

python3 - "$RAW_STREAM" <<'PY'
import json, sys
from collections import Counter

raw = sys.argv[1]

events = []
guardrail_payloads = []
for block in raw.split("\n\n"):
    lines = [ln.strip() for ln in block.split("\n") if ln.strip()]
    if not lines:
        continue
    ev = "message"
    data_lines = []
    for ln in lines:
        if ln.startswith("event:"):
            ev = ln[len("event:"):].strip()
        elif ln.startswith("data:"):
            data_lines.append(ln[len("data:"):].strip())
    if not data_lines:
        continue
    payload = "\n".join(data_lines)
    try:
        data = json.loads(payload)
    except Exception:
        data = payload
    events.append(ev)
    if ev == "guardrail" and isinstance(data, dict):
        guardrail_payloads.append(data)

c = Counter(events)
print("Event counts:")
for k in sorted(c.keys()):
    print(f"  {k}: {c[k]}")

if c.get("guardrail", 0) < 1:
    print("\nFAIL: no guardrail event observed")
    sys.exit(2)

if c.get("done", 0) < 1 and c.get("error", 0) < 1:
    print("\nFAIL: stream did not terminate with done/error")
    sys.exit(2)

print("\nPASS: guardrail observed and stream terminated")
if guardrail_payloads:
    print("Sample guardrail:", guardrail_payloads[0])
sys.exit(0)
PY

