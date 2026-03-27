#!/usr/bin/env bash
set -euo pipefail

# Remote backend deploy entrypoint (local build + upload + strict remote restart).
# Usage:
#   bash deploy-backend.sh <server_ip> <ssh_user> [server_port] [agent_engine]
#
# Example:
#   bash deploy-backend.sh 1.2.3.4 root 8082 langchain4j

if [[ $# -lt 2 ]]; then
  echo "Usage: bash deploy-backend.sh <server_ip> <ssh_user> [server_port] [agent_engine]" >&2
  exit 1
fi

SERVER_IP="$1"
SSH_USER="$2"
SERVER_PORT="${3:-8082}"
AGENT_ENGINE="${4:-langchain4j}"
REMOTE_PROJECT_ROOT="${REMOTE_PROJECT_ROOT:-/root/chatAgent}"
APP_ENV_FILE="${APP_ENV_FILE:-$REMOTE_PROJECT_ROOT/backend/.env}"
SSH_PORT="${SSH_PORT:-22}"
HEALTH_CHECK_PATH="${HEALTH_CHECK_PATH:-/api/health}"
HEALTH_CHECK_TIMEOUT_SEC="${HEALTH_CHECK_TIMEOUT_SEC:-90}"
HEALTH_CHECK_INTERVAL_SEC="${HEALTH_CHECK_INTERVAL_SEC:-3}"
LOCAL_PROJECT_ROOT="${LOCAL_PROJECT_ROOT:-$(cd "$(dirname "$0")" && pwd)}"
LOCAL_BACKEND_DIR="${LOCAL_BACKEND_DIR:-$LOCAL_PROJECT_ROOT/backend}"
LOCAL_JAR_PATH="${LOCAL_JAR_PATH:-$LOCAL_BACKEND_DIR/target/chat-agent-backend-0.0.1-SNAPSHOT.jar}"
REMOTE_JAR_PATH="${REMOTE_JAR_PATH:-$REMOTE_PROJECT_ROOT/backend/target/chat-agent-backend-0.0.1-SNAPSHOT.jar}"
MVN_BIN="${MVN_BIN:-mvn}"

echo "[1/4] Build backend jar locally..."
cd "$LOCAL_BACKEND_DIR"
"$MVN_BIN" -DskipTests clean package
if [[ ! -f "$LOCAL_JAR_PATH" ]]; then
  echo "ERROR: local jar not found: $LOCAL_JAR_PATH" >&2
  exit 1
fi
LOCAL_JAR_SIZE_BYTES="$(wc -c < "$LOCAL_JAR_PATH" | tr -d ' ')"
if [[ -z "$LOCAL_JAR_SIZE_BYTES" || "$LOCAL_JAR_SIZE_BYTES" -le 0 ]]; then
  echo "ERROR: invalid local jar size: $LOCAL_JAR_SIZE_BYTES" >&2
  exit 1
fi
echo "Local jar size: ${LOCAL_JAR_SIZE_BYTES} bytes"

echo "[2/4] Upload jar to remote host..."
ssh -p "$SSH_PORT" "${SSH_USER}@${SERVER_IP}" "mkdir -p '$REMOTE_PROJECT_ROOT/backend/target'"
scp -P "$SSH_PORT" "$LOCAL_JAR_PATH" "${SSH_USER}@${SERVER_IP}:$REMOTE_JAR_PATH"
REMOTE_JAR_SIZE_BYTES="$(
  ssh -p "$SSH_PORT" "${SSH_USER}@${SERVER_IP}" "wc -c < '$REMOTE_JAR_PATH' | tr -d ' '"
)"
if [[ "$REMOTE_JAR_SIZE_BYTES" != "$LOCAL_JAR_SIZE_BYTES" ]]; then
  echo "ERROR: jar size mismatch after upload" >&2
  echo "Local : $LOCAL_JAR_SIZE_BYTES bytes" >&2
  echo "Remote: $REMOTE_JAR_SIZE_BYTES bytes" >&2
  exit 1
fi
echo "Remote jar size matches local."

echo "[3/4] Restart backend service remotely..."
ssh -p "$SSH_PORT" "${SSH_USER}@${SERVER_IP}" "
  set -euo pipefail
  cd '$REMOTE_PROJECT_ROOT'
  if [[ -f 'deploy/deploy_backend.sh' ]]; then
    echo 'Using remote deploy/deploy_backend.sh'
    chmod +x deploy/deploy_backend.sh
    sudo APP_ENV_FILE='$APP_ENV_FILE' \
         SERVER_PORT='$SERVER_PORT' \
         APP_AGENT_ENGINE='$AGENT_ENGINE' \
         SKIP_BUILD='true' \
         JAR_PATH_OVERRIDE='$REMOTE_JAR_PATH' \
         ./deploy/deploy_backend.sh
  else
    echo 'Remote deploy/deploy_backend.sh not found, using inline restart flow.'
    sudo mkdir -p '$REMOTE_PROJECT_ROOT/logs' '$REMOTE_PROJECT_ROOT/data' '$REMOTE_PROJECT_ROOT/backend'
    sudo tee /etc/systemd/system/chatagent-backend.service >/dev/null <<EOF
[Unit]
Description=ChatAgent Backend Service
After=network.target

[Service]
Type=simple
WorkingDirectory=$REMOTE_PROJECT_ROOT/backend
EnvironmentFile=-$APP_ENV_FILE
Environment=SERVER_PORT=$SERVER_PORT
Environment=APP_AGENT_ENGINE=$AGENT_ENGINE
ExecStart=/usr/bin/env java -jar $REMOTE_JAR_PATH
Restart=always
RestartSec=3
StandardOutput=append:$REMOTE_PROJECT_ROOT/logs/backend.out.log
StandardError=append:$REMOTE_PROJECT_ROOT/logs/backend.err.log
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF
    sudo systemctl daemon-reload
    sudo systemctl enable chatagent-backend
    sudo systemctl restart chatagent-backend
  fi
  sudo systemctl --no-pager --full status chatagent-backend | sed -n '1,30p'
"

echo "[4/4] Health check..."
DEADLINE=$((SECONDS + HEALTH_CHECK_TIMEOUT_SEC))
while true; do
  if curl -fsS "http://${SERVER_IP}:${SERVER_PORT}${HEALTH_CHECK_PATH}" >/dev/null 2>&1; then
    echo "Health check passed: http://${SERVER_IP}:${SERVER_PORT}${HEALTH_CHECK_PATH}"
    break
  fi
  if (( SECONDS >= DEADLINE )); then
    echo "ERROR: health check timeout after ${HEALTH_CHECK_TIMEOUT_SEC}s" >&2
    exit 1
  fi
  sleep "$HEALTH_CHECK_INTERVAL_SEC"
done

echo "Done."
echo "Backend endpoint: http://${SERVER_IP}:${SERVER_PORT}"

