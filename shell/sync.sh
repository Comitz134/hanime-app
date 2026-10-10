#!/usr/bin/env bash
# Fetches NuvioDesktop at the pinned commit into shell/.upstream and lays this
# repository's changes over it. Idempotent: run it again after editing anything
# in overrides/ and it re-fetches, re-checks out and re-applies.
#
#   ./shell/sync.sh
#   cd shell/.upstream && JAVA_HOME=/path/to/jdk17 ./gradlew :composeApp:run
set -euo pipefail

UPSTREAM="https://github.com/NuvioMedia/NuvioDesktop.git"
PIN="bc4566a5c474f5f90c6dc40062723f37f823c59e"
BRANCH="Dev"

here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/.upstream"

if ! command -v git >/dev/null 2>&1; then
  echo "sync.sh: git is required" >&2
  exit 1
fi

# A fresh, fetch-only clone: no --depth on the branch fallback path, because the
# pin is a commit and that is the point of the file.
if [ ! -d "$dest/.git" ]; then
  echo "sync.sh: creating $dest"
  mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin "$UPSTREAM" 2>/dev/null \
    || git -C "$dest" remote set-url origin "$UPSTREAM"
fi

echo "sync.sh: fetching $PIN"
if ! git -C "$dest" fetch --depth 1 origin "$PIN" 2>/dev/null; then
  # Some servers refuse a direct SHA fetch; the pin is on $BRANCH, so deepen.
  echo "sync.sh: direct fetch refused, falling back to $BRANCH"
  git -C "$dest" fetch --depth 1 origin "$BRANCH"
  git -C "$dest" fetch --depth 1 origin "$PIN"
fi

git -C "$dest" checkout -q --detach "$PIN"
git -C "$dest" submodule update --init --recursive --depth 1 2>/dev/null || true

echo "sync.sh: applying overrides"
cp -R "$here/overrides/." "$dest/"

echo
echo "upstream : NuvioDesktop @ $PIN (GPL-3.0 — see NOTICE.md)"
echo "tree     : $dest"
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo "warning  : no java on PATH and JAVA_HOME is unset — install JDK 17 first"
fi
if ! command -v node >/dev/null 2>&1; then
  echo "warning  : node is not on PATH — the window will not be able to start server/"
fi
echo
echo "run it   : (cd \"$dest\" && ./gradlew :composeApp:run)"
echo "package  : (cd \"$dest\" && ./gradlew :composeApp:packageDistributionForCurrentOS)"
