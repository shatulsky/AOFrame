#!/usr/bin/env bash
# Deploys pi-video-gate/ (server.js, package.json, Dockerfile,
# docker-compose.yml) to the host's ~/pi-video-gate directory. A
# sha256-diff-and-restart pattern - only
# copies what changed, only rebuilds/restarts the container if something
# actually changed.
#
# Runs as a Docker container. node_modules stays in the
# bind-mounted ~/pi-video-gate tree rather than being COPYed into the
# image build (see Dockerfile/docker-compose.yml's header comments), so
# the npm-install step below matches a plain non-Docker deployment too.
#
# config.json is NEVER touched by this script (contains the Immich API
# key and other secrets) - create it once by hand from
# config.example.json (see INSTALL.md).
#
# Reads the deploy target from config.json's "deployHost" field (e.g.
# "pi@192.168.1.50") - copy config.example.json to config.json and fill
# in your own values first. Uses your default SSH key/agent unless
# config.json also sets "deploySshKey" to an explicit key path.
#
# Usage: ./deploy.sh   (run from anywhere - paths are relative to this file)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f config.json ]; then
	echo "config.json not found - copy config.example.json to config.json and fill in your own values first." >&2
	exit 1
fi

PI_HOST="$(node -e "const c=require('./config.json'); if(!c.deployHost) throw new Error('missing deployHost'); console.log(c.deployHost)")"
DEPLOY_SSH_KEY="$(node -e "console.log(require('./config.json').deploySshKey || '')")"
REMOTE_DIR="pi-video-gate"
SSH_OPTS=(-o StrictHostKeyChecking=accept-new)
[ -n "$DEPLOY_SSH_KEY" ] && SSH_OPTS=(-i "$DEPLOY_SSH_KEY" "${SSH_OPTS[@]}")

FILES=("server.js:server.js" "package.json:package.json" "Dockerfile:Dockerfile" "docker-compose.yml:docker-compose.yml" ".dockerignore:.dockerignore")

echo "Checking ${#FILES[@]} file(s) against the Pi..."
REMOTE_HASHES="$(ssh "${SSH_OPTS[@]}" "$PI_HOST" "
	cd $REMOTE_DIR 2>/dev/null || exit 0
	find server.js package.json Dockerfile docker-compose.yml .dockerignore -type f 2>/dev/null -exec sha256sum {} \;
	true
")"

changed=0
package_json_changed=0
for entry in "${FILES[@]}"; do
	local_path="${entry%%:*}"
	remote_path="${entry##*:}"
	local_hash="$(sha256sum "$local_path" | cut -d' ' -f1)"
	remote_hash="$(echo "$REMOTE_HASHES" | awk -v p="$remote_path" '$2==p{print $1}')"

	if [ "$local_hash" == "$remote_hash" ]; then
		continue
	fi

	echo "  changed: $local_path"
	ssh "${SSH_OPTS[@]}" "$PI_HOST" "mkdir -p $REMOTE_DIR/$(dirname "$remote_path")"
	scp "${SSH_OPTS[@]}" "$local_path" "$PI_HOST:$REMOTE_DIR/$remote_path" >/dev/null
	changed=1
	[ "$local_path" == "package.json" ] && package_json_changed=1
done

NODE_MODULES_EXISTS="$(ssh "${SSH_OPTS[@]}" "$PI_HOST" "[ -d $REMOTE_DIR/node_modules ] && echo yes || echo no")"
if [ "$package_json_changed" -eq 1 ] || [ "$NODE_MODULES_EXISTS" == "no" ]; then
	echo "Running npm install on the remote host..."
	ssh "${SSH_OPTS[@]}" "$PI_HOST" "cd $REMOTE_DIR && npm install --omit=dev" >/dev/null
	changed=1
fi

CONFIG_EXISTS="$(ssh "${SSH_OPTS[@]}" "$PI_HOST" "[ -f $REMOTE_DIR/config.json ] && echo yes || echo no")"
if [ "$CONFIG_EXISTS" == "no" ]; then
	echo "WARNING: $REMOTE_DIR/config.json doesn't exist on the Pi yet - service will fail to start."
	echo "Create it from config.example.json (see INSTALL.md) before/after this run."
fi

if [ "$changed" -eq 1 ]; then
	echo "Rebuilding and restarting the pi-video-gate container..."
	ssh "${SSH_OPTS[@]}" "$PI_HOST" "cd $REMOTE_DIR && docker compose up -d --build" >/dev/null
	echo "Done - container rebuilt/restarted."
else
	echo "Nothing changed - container not restarted."
fi
