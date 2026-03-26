#!/bin/sh
BASE_URL=http://localhost:8082

TOKEN=$(
  curl -s -X POST "$BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])'
)

SESSION_ID=$(
  curl -s -X POST "$BASE_URL/api/sessions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'
)

echo "TOKEN=${TOKEN:0:16}..."
echo "SESSION_ID=$SESSION_ID"

export BASE_URL=http://localhost:8082
export TOKEN
export SESSION_ID
source ~/.zshrc
./backend/scripts/verify_planning_stream.sh
./backend/scripts/verify_rag_nohit.sh
./backend/scripts/verify_guardrail.sh
