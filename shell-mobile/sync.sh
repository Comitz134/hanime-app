#!/usr/bin/env bash
# Fetches NuvioMobile at the pinned commit into shell-mobile/.upstream and lays
# this repository's changes over it. Idempotent: run it again after editing
# anything in overrides/ and it re-fetches, re-checks out and re-applies.
#
#   ./shell-mobile/sync.sh
#   cd shell-mobile/.upstream && JAVA_HOME=/path/to/jdk17 ./gradlew :androidApp:assembleFullDebug
#
# The phone app this builds is *Nuvio's own UI and player* (Kotlin + Compose
# Multiplatform), rewired to serve this project's catalogue — the same shape as
# shell/ for the desktop window, and for the same reason: reimplementing that
# UI here would be maintaining a pale copy of it. What differs is where the
# catalogue comes from. The window starts this repo's Node server as a child
# process; a phone cannot, so the fork carries the catalogue in-process (see
# overrides/ and NOTICE.md).
set -euo pipefail

UPSTREAM="https://github.com/NuvioMedia/NuvioMobile.git"
PIN="229c083cf3db7bab6fda284484f87439eb4e0eb0"
BRANCH="cmp-rewrite"

here="$(cd "$(dirname "$0")" && pwd)"        # hanime-app/shell-mobile
repo="$(cd "$here/.." && pwd)"               # hanime-app
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
# The player's native parts are Maven artifacts (io.github.abdallahmehiz:
# mpv-android-lib) and the prebuilt engine AARs are committed in the tree, so a
# submodule fetch is not what makes a build work here. MPVKit/libass-android are
# iOS-side; upstream's own .gitmodules has no URL for the second one, which is
# why this ignores a failure rather than failing.
git -C "$dest" submodule update --init --recursive --depth 1 2>/dev/null || true

if [ -d "$here/overrides" ]; then
  echo "sync.sh: applying overrides"
  cp -R "$here/overrides/." "$dest/"
else
  echo "sync.sh: no overrides/ yet — the tree is upstream unmodified"
fi

# Gradle declares local.properties as an input and the Android plugin needs an
# SDK path in it; nothing about it is source, so it is generated here and left
# alone afterwards. The GitHub release wrapper the CI uses is not needed: the
# keystore beside android/ signs the *release* build, and a debug build (which
# is what sync.sh + install-mobile.sh build) uses the debug keystore instead.
if [ ! -f "$dest/local.properties" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -d "$repo/android" ] && [ -f "$repo/android/local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$repo/android/local.properties" | head -1)"
  fi
  if [ -n "$sdk" ]; then
    # Forward slashes on purpose: a .properties file treats a backslash as an
    # escape, so `F:\atc` arrives at the plugin mangled unless it is doubled —
    # and a doubled path is one shell quoting mistake away from a build that
    # fails with "the filename, directory name, or volume label syntax is
    # incorrect". Windows accepts both separators, so this writes the one that
    # needs no escaping.
    sdk_unix="$(printf '%s' "$sdk" | tr '\\' '/')"
    printf 'sdk.dir=%s\n' "$sdk_unix" > "$dest/local.properties"
    echo "sync.sh: wrote $dest/local.properties (sdk.dir=$sdk_unix)"
  else
    echo "sync.sh: no SDK found — set ANDROID_HOME, or write sdk.dir= into $dest/local.properties" >&2
  fi
fi

echo
echo "upstream : NuvioMobile @ $PIN (GPL-3.0 — see NOTICE.md)"
echo "tree     : $dest"
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  echo "warning  : no java on PATH and JAVA_HOME is unset — install JDK 17 first" >&2
fi
echo
echo "build it : (cd \"$dest\" && JAVA_HOME=/path/to/jdk17 ./gradlew :androidApp:assembleFullDebug)"
echo "install  : ./shell-mobile/install-mobile.sh      # build + adb install on the attached phone"
