#!/usr/bin/env bash
set -euo pipefail

# Deploy backend + frontend in sequence from local machine.
# Usage:
#   bash deploy-all.sh <server_ip> <ssh_user> [backend_port] [frontend_port] [agent_engine]
#
# Example:
#   bash deploy-all.sh 1.2.3.4 root 8082 5157 langchain4j

if [[ $# -lt 2 ]]; then
  echo "Usage: bash deploy-all.sh <server_ip> <ssh_user> [backend_port] [frontend_port] [agent_engine]" >&2
  exit 1
fi

SERVER_IP="$1"
SSH_USER="$2"
BACKEND_PORT="${3:-8082}"
FRONTEND_PORT="${4:-5157}"
AGENT_ENGINE="${5:-langchain4j}"

echo "===> Deploy backend"
bash "$(cd "$(dirname "$0")" && pwd)/deploy-backend.sh" "$SERVER_IP" "$SSH_USER" "$BACKEND_PORT" "$AGENT_ENGINE"

echo "===> Deploy frontend"
bash "$(cd "$(dirname "$0")" && pwd)/deploy-frontend.sh" "$SERVER_IP" "$SSH_USER" "$SERVER_IP" "$FRONTEND_PORT"

echo "All done."
echo "Frontend: http://${SERVER_IP}:${FRONTEND_PORT}"
echo "Backend : http://${SERVER_IP}:${BACKEND_PORT}"

