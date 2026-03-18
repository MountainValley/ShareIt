#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

detect_local_ip() {
  local default_if
  local ip

  default_if="$(route get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
  if [ -n "${default_if:-}" ]; then
    ip="$(ipconfig getifaddr "$default_if" 2>/dev/null || true)"
    if [ -n "${ip:-}" ]; then
      printf '%s\n' "$ip"
      return 0
    fi
  fi

  for iface in en0 en1 en2 en3; do
    ip="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
    if [ -n "${ip:-}" ]; then
      printf '%s\n' "$ip"
      return 0
    fi
  done

  if command -v ifconfig >/dev/null 2>&1; then
    ip="$(ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}')"
    if [ -n "${ip:-}" ]; then
      printf '%s\n' "$ip"
      return 0
    fi
  fi

  printf '%s\n' "<your-lan-ip>"
}

LOCAL_IP="$(detect_local_ip)"

cleanup() {
  jobs -p | xargs -r kill >/dev/null 2>&1 || true
}

wait_for_backend() {
  for _ in {1..90}; do
    if curl -sSf "http://127.0.0.1:7777/api/file/workspace" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_frontend() {
  for _ in {1..60}; do
    if curl -sSf "http://127.0.0.1:5173" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

open_browser() {
  local url="$1"
  local os_name
  os_name="$(uname -s)"

  if [ "$os_name" = "Darwin" ]; then
    open "$url" >/dev/null 2>&1 || true
  elif [ "$os_name" = "Linux" ]; then
    xdg-open "$url" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT INT TERM

cd "$ROOT_DIR"
"$ROOT_DIR/mvnw" -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments="--app.browser.open.enabled=false" &

echo "Waiting for backend: http://localhost:7777"
if ! wait_for_backend; then
  echo "Backend did not become ready on http://localhost:7777"
  exit 1
fi

cd "$ROOT_DIR/frontend"
if [ ! -d node_modules ]; then
  npm install
fi
npm run dev &

echo "Frontend: http://localhost:5173"
echo "Frontend (LAN): http://$LOCAL_IP:5173"
echo "Backend API: http://localhost:7777"
echo "Backend API (LAN): http://$LOCAL_IP:7777"
echo "In dev mode, open the frontend URL above. For phone or another computer, use the LAN URL."

if wait_for_frontend; then
  open_browser "http://$LOCAL_IP:5173"
fi

wait
