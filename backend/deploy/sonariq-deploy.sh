#!/usr/bin/env bash
# Pulls the sonariq branch and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy that branch.
set -euo pipefail
SRC=/opt/sonariq-src
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch sonariq https://github.com/sonariq-source/Sonariq.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin sonariq
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/sonariq-domain)" bash "$SRC/backend/deploy/setup.sh"
