#!/usr/bin/env bash
set -euo pipefail

# Remote frontend deploy entrypoint (local build + upload + remote nginx reload).
# Usage:
#   bash deploy-frontend.sh <server_ip> <ssh_user> [domain_or_ip] [listen_port]
#
# Example:
#   bash deploy-frontend.sh 1.2.3.4 root 1.2.3.4 5173

if [[ $# -lt 2 ]]; then
  echo "Usage: bash deploy-frontend.sh <server_ip> <ssh_user> [domain_or_ip] [listen_port]" >&2
  exit 1
fi

SERVER_IP="$1"
SSH_USER="$2"
DOMAIN_OR_IP="${3:-$SERVER_IP}"
LISTEN_PORT="${4:-5173}"
REMOTE_PROJECT_ROOT="${REMOTE_PROJECT_ROOT:-/root/chatAgent}"
API_UPSTREAM="${API_UPSTREAM:-http://127.0.0.1:8082}"
SSH_PORT="${SSH_PORT:-22}"
LOCAL_PROJECT_ROOT="${LOCAL_PROJECT_ROOT:-$(cd "$(dirname "$0")" && pwd)}"
LOCAL_FRONTEND_DIR="${LOCAL_FRONTEND_DIR:-$LOCAL_PROJECT_ROOT/frontend}"
LOCAL_DIST_DIR="${LOCAL_DIST_DIR:-$LOCAL_FRONTEND_DIR/dist}"
REMOTE_DIST_DIR="${REMOTE_DIST_DIR:-$REMOTE_PROJECT_ROOT/frontend/dist}"
NPM_BIN="${NPM_BIN:-npm}"

echo "[1/4] Build frontend locally..."
cd "$LOCAL_FRONTEND_DIR"
"$NPM_BIN" install
"$NPM_BIN" run build
if [[ ! -d "$LOCAL_DIST_DIR" ]]; then
  echo "ERROR: local dist dir not found: $LOCAL_DIST_DIR" >&2
  exit 1
fi

echo "[2/4] Upload dist to remote host..."
ssh -p "$SSH_PORT" "${SSH_USER}@${SERVER_IP}" "mkdir -p '$REMOTE_DIST_DIR'"
scp -P "$SSH_PORT" -r "$LOCAL_DIST_DIR"/. "${SSH_USER}@${SERVER_IP}:$REMOTE_DIST_DIR/"

echo "[3/4] Reload nginx frontend on remote host..."
ssh -p "$SSH_PORT" "${SSH_USER}@${SERVER_IP}" "
  set -euo pipefail
  cd '$REMOTE_PROJECT_ROOT'
  if [[ -f 'deploy/deploy_frontend.sh' ]]; then
    chmod +x deploy/deploy_frontend.sh
    sudo DEPLOY_MODE=nginx \
         SKIP_BUILD=true \
         DOMAIN_OR_IP='$DOMAIN_OR_IP' \
         API_UPSTREAM='$API_UPSTREAM' \
         LISTEN_PORT='$LISTEN_PORT' \
         ./deploy/deploy_frontend.sh
  else
    echo 'WARN: deploy/deploy_frontend.sh not found, using inline fallback.'
    sudo mkdir -p /var/www/chatagent '$REMOTE_PROJECT_ROOT/frontend/dist'
    sudo cp -rf '$REMOTE_DIST_DIR'/. /var/www/chatagent/
    if sudo test -x /www/server/nginx/sbin/nginx && sudo test -d /www/server/panel/vhost/nginx; then
      NGINX_TARGET_CONF=/www/server/panel/vhost/nginx/chatagent.conf
      NGINX_BIN=/www/server/nginx/sbin/nginx
      NGINX_MAIN_CONF=/www/server/nginx/conf/nginx.conf
      sudo mkdir -p /www/server/panel/vhost/nginx
    elif sudo test -d /etc/nginx/conf.d; then
      NGINX_TARGET_CONF=/etc/nginx/conf.d/chatagent.conf
      NGINX_BIN=nginx
      NGINX_MAIN_CONF=/etc/nginx/nginx.conf
      sudo mkdir -p /etc/nginx/conf.d
    else
      NGINX_TARGET_CONF=/etc/nginx/sites-available/chatagent.conf
      NGINX_BIN=nginx
      NGINX_MAIN_CONF=/etc/nginx/nginx.conf
      sudo mkdir -p /etc/nginx/sites-available /etc/nginx/sites-enabled
    fi
    sudo tee \"\$NGINX_TARGET_CONF\" >/dev/null <<EOF
server {
    listen $LISTEN_PORT;
    server_name $DOMAIN_OR_IP;
    client_max_body_size 2m;

    root /var/www/chatagent;
    index index.html;

    location / {
        try_files \\\$uri \\\$uri/ /index.html;
    }

    location /api/ {
        proxy_pass $API_UPSTREAM;
        proxy_http_version 1.1;
        proxy_set_header Host \\\$host;
        proxy_set_header X-Real-IP \\\$remote_addr;
        proxy_set_header X-Forwarded-For \\\$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \\\$scheme;
        proxy_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }
}
EOF
    if [[ \"\$NGINX_TARGET_CONF\" == /etc/nginx/sites-available/* ]]; then
      sudo ln -sf \"\$NGINX_TARGET_CONF\" /etc/nginx/sites-enabled/chatagent.conf
    fi
    sudo \"\$NGINX_BIN\" -t -c \"\$NGINX_MAIN_CONF\"
    # Prefer reload/start via selected nginx binary to avoid conflicting systemd units.
    if sudo \"\$NGINX_BIN\" -s reload; then
      echo 'nginx reloaded'
    elif sudo rm -f /www/server/nginx/logs/nginx.pid /var/run/nginx.pid && sudo \"\$NGINX_BIN\" -c \"\$NGINX_MAIN_CONF\"; then
      echo 'nginx started'
    else
      echo 'ERROR: failed to reload/restart nginx. Diagnostics:' >&2
      sudo systemctl --no-pager --full status nginx || true
      sudo journalctl -u nginx -n 80 --no-pager || true
      exit 1
    fi
  fi
"

echo "[4/4] Done. Check: http://${SERVER_IP}:${LISTEN_PORT}"

