#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIST="$ROOT_DIR/frontend/dist"
BACKEND_STATIC="$ROOT_DIR/backend/src/main/resources/static"

cd "$ROOT_DIR/frontend"
npm install
npm run build

mkdir -p "$BACKEND_STATIC"
find "$BACKEND_STATIC" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -R "$FRONTEND_DIST"/. "$BACKEND_STATIC"/

cd "$ROOT_DIR"
"$ROOT_DIR/mvnw" -f backend/pom.xml clean package
