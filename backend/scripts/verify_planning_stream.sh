#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   TOKEN=... SESSION_ID=... ./backend/scripts/verify_planning_stream.sh "请比较上海和成都天气，并给出结论"
#
# Optional:
#   BASE_URL=http://localhost:8082

BASE_URL="${BASE_URL:-http://localhost:8082}"
TOKEN="${TOKEN:-}"
SESSION_ID="${SESSION_ID:-}"
PROMPT="${1:-请比较上海和成都天气、再计算(123+456)*7，并说明依据来源。}"

if [[ -z "$TOKEN" || -z "$SESSION_ID" ]]; then
  echo "Missing TOKEN or SESSION_ID"
  echo "Example:"
  echo "  TOKEN=xxx SESSION_ID=yyy ./backend/scripts/verify_planning_stream.sh"
  exit 1
fi

echo "Sending stream request to ${BASE_URL}/api/agent/chat/stream ..."

RAW_STREAM="$(
  curl --silent --show-error --no-buffer \
    -X POST "${BASE_URL}/api/agent/chat/stream" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"content\":\"${PROMPT}\"}"
)"

python3 - "$RAW_STREAM" <<'PY'
import sys

raw = sys.argv[1]
events = []
for block in raw.split("\n\n"):
    lines = [ln.strip() for ln in block.split("\n") if ln.strip()]
    if not lines:
        continue
    ev = "message"
    for line in lines:
        if line.startswith("event:"):
            ev = line[len("event:"):].strip()
    events.append(ev)

from collections import Counter
c = Counter(events)

print("Event counts:")
for k in sorted(c.keys()):
    print(f"  {k}: {c[k]}")

plan_ok = c.get("plan_start", 0) >= 1 and c.get("plan_step", 0) >= 1 and c.get("plan_done", 0) >= 1
tool_ok = c.get("tool_start", 0) >= 2 and c.get("tool_end", 0) >= 2

if plan_ok and tool_ok:
    print("\nPASS: observed planning events and >=2 tool calls.")
    sys.exit(0)

print("\nFAIL:")
if not plan_ok:
    print("- missing required planning events (plan_start/plan_step/plan_done)")
if not tool_ok:
    print("- fewer than 2 tool_start/tool_end events")
sys.exit(2)
PY

