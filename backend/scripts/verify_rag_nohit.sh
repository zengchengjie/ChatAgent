#!/usr/bin/env bash
set -euo pipefail

# Verify that search_knowledge can return "no hit" (chunks=[]) without errors.
#
# Usage:
#   TOKEN=... SESSION_ID=... ./backend/scripts/verify_rag_nohit.sh
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

PROMPT="请务必调用 search_knowledge 工具，参数设置 docTitleFilter='__no_such_doc__' k=3 minScore=0.99，并把工具返回原样输出。"

RAW_STREAM="$(
  curl --silent --show-error --no-buffer \
    -X POST "${BASE_URL}/api/agent/chat/stream" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d "{\"sessionId\":\"${SESSION_ID}\",\"content\":\"${PROMPT}\"}"
)"

python3 - "$RAW_STREAM" <<'PY'
import json, sys

raw = sys.argv[1]

def blocks():
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
        yield ev, data

found = False
for ev, data in blocks():
    if ev != "tool_end":
        continue
    if not isinstance(data, dict):
        continue
    if data.get("name") != "search_knowledge":
        continue
    detail = data.get("detail")
    if not isinstance(detail, str):
        continue
    try:
        obj = json.loads(detail)
    except Exception:
        continue
    if isinstance(obj, dict) and obj.get("hit") is False and obj.get("chunks") == []:
        found = True
        break

if found:
    print("PASS: search_knowledge returned hit=false and chunks=[]")
    sys.exit(0)

print("FAIL: did not observe search_knowledge no-hit output in tool_end detail")
sys.exit(2)
PY

