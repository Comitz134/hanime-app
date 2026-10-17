#!/usr/bin/env bash
# Refresh the SEKAI installed on this machine from this tree.
#
# The installed app is a jpackage *app-image*: a folder holding the launcher, a
# bundled JRE and the app's jars (the main one, and the bundled Node server
# inside it). Refreshing it is therefore a build and a folder sync, not an
# installer run — which is what this script is, in one command, so the app a
# reader opens from the Start menu is never older than the code in this
# checkout. Run it after every change that reaches the window.
#
#   ./shell/install-desktop.sh              apply overrides, build, install
#   ./shell/install-desktop.sh --no-build   install the last build as it stands
#   ./shell/install-desktop.sh --stop       stop a running SEKAI first
#   SEKAI_INSTALL_DIR=… ./shell/install-desktop.sh
#
# Where it installs: `%LOCALAPPDATA%\Programs\SEKAI` on Windows (the path the
# Start-menu shortcut launches and the portable build documents), `~/Applications/SEKAI.app`
# on macOS, `~/.local/opt/SEKAI` on Linux. `SEKAI_INSTALL_DIR` overrides it, but
# the folder still has to be named `SEKAI` / `SEKAI.app`: the mirror step deletes
# what the build does not produce, so a mistyped path must not be a data-loss
# foot-gun.
#
# Requires JDK 17 (`JAVA_HOME`) for the build, and Node is *not* needed here —
# the server is bundled into the app's own jar by `:composeApp:syncHanimeServer`.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"        # hanime-app/shell
repo="$(cd "$here/.." && pwd)"               # hanime-app
tree="$here/.upstream"                       # the build tree the overrides land in
app_name="SEKAI"

build=1
stop=0
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --stop) stop=1 ;;
    -h | --help)
      sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "install-desktop.sh: unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) on_windows=1 ;;
  *) on_windows=0 ;;
esac

if [ ! -d "$tree/.git" ]; then
  echo "install-desktop.sh: no build tree at $tree — run ./shell/sync.sh first" >&2
  exit 1
fi

# The server bundle carries `server/public/index.html`, the *built* web client —
# and nothing in the Gradle build regenerates it (`cd server && npm run
# build:client` does, and it also rewrites the phone's assets). A window whose
# bundled web pages are older than the sources they come from is worth one line
# of warning rather than a silent ship. `server/test/web-client.test.mjs` fails
# on exactly this drift, so the suite is the guard; this is the reminder.
client_src="$repo/server/client"
if [ -f "$repo/server/public/index.html" ] && [ -d "$client_src" ]; then
  newest_src="$(find "$client_src/src" "$client_src/styles" -type f -newer "$repo/server/public/index.html" -print -quit 2>/dev/null || true)"
  if [ -n "$newest_src" ]; then
    echo "install-desktop.sh: WARNING — server/public/index.html is older than $newest_src" >&2
    echo "install-desktop.sh: WARNING — run (cd server && npm run build:client) so the bundled web client matches" >&2
  fi
fi

# --- 1. the slice of upstream this fork owns ---------------------------------
# sync.sh does the fetching; all this has to do is lay the current overrides
# over the tree, which is what makes "edit, install" one command.
echo "install-desktop.sh: applying overrides to $tree"
cp -R "$here/overrides/." "$tree/"

# --- 2. the build ------------------------------------------------------------
if [ "$build" = 1 ]; then
  echo "install-desktop.sh: :composeApp:createDistributable (this takes a few minutes)"
  # No configuration cache: the version is read while Gradle configures, and a
  # cached configuration stamps the *previous* release's number into the app.
  # An app that calls itself 1.0.0 while the release is 1.0.1 offers itself that
  # same update on every launch — a download loop, discovered the hard way.
  (cd "$tree" && ./gradlew :composeApp:createDistributable --console=plain --no-configuration-cache)

  # And say so out loud, because the failure is silent otherwise.
  declared="$(sed -n 's/^VERSION_NAME=//p' "$here/overrides/composeApp/Configuration/DesktopVersion.properties" | tr -d '\r' | head -1)"
  compiled="$(sed -n 's/.*DESKTOP_VERSION_NAME = "\(.*\)"/\1/p' \
    "$tree/composeApp/build/generated/runtime-config/kotlin/com/nuvio/app/core/build/AppVersionConfig.kt" 2>/dev/null | head -1)"
  if [ -n "$declared" ] && [ -n "$compiled" ] && [ "$declared" != "$compiled" ]; then
    echo "install-desktop.sh: the build calls itself $compiled but this tree declares $declared" >&2
    exit 1
  fi
  [ -n "$compiled" ] && echo "install-desktop.sh: the build calls itself $compiled, as the tree declares"
fi

binaries="$tree/composeApp/build/compose/binaries/main/app"
src="$(find "$binaries" -maxdepth 1 -mindepth 1 \( -name "$app_name" -o -name "$app_name.app" \) 2>/dev/null | head -1)"
if [ -z "$src" ]; then
  echo "install-desktop.sh: no app-image under $binaries — the build did not reach createDistributable" >&2
  exit 1
fi

# --- 3. where it goes --------------------------------------------------------
if [ -n "${SEKAI_INSTALL_DIR:-}" ]; then
  dest="$SEKAI_INSTALL_DIR"
elif [ "$on_windows" = 1 ]; then
  local_appdata="${LOCALAPPDATA:-${USERPROFILE:-$HOME}/AppData/Local}"
  dest="$local_appdata/Programs/$app_name"
elif [ "$(uname -s)" = "Darwin" ]; then
  dest="$HOME/Applications/$app_name.app"
else
  dest="$HOME/.local/opt/$app_name"
fi

# Everything below reads $dest through bash, and coreutils escapes a path that
# holds backslashes (prefixing its output with one) — so keep it POSIX here and
# hand robocopy the native form.
dest="${dest//\\//}"

case "$(basename "$dest")" in
  "$app_name" | "$app_name.app") ;;
  *)
    echo "install-desktop.sh: refusing to mirror into '$dest' — the folder must be named $app_name (it is deleted and rewritten)" >&2
    exit 1
    ;;
esac

# --- 4. nobody may be holding the files we are about to replace ---------------
running=0
if [ "$on_windows" = 1 ]; then
  # MSYS2_ARG_CONV_EXCL again: Git Bash rewrites `/FI` into `C:/Program Files/Git/FI`,
  # tasklist then fails on stderr (which the grep below would have swallowed) and
  # every window would look closed while it held its jars open.
  if MSYS2_ARG_CONV_EXCL='*' tasklist /FI "IMAGENAME eq $app_name.exe" 2>/dev/null \
    | grep -qi "$app_name.exe"; then
    running=1
  fi
elif pgrep -x "$app_name" >/dev/null 2>&1; then
  running=1
fi

if [ "$running" = 1 ]; then
  if [ "$stop" = 1 ]; then
    echo "install-desktop.sh: stopping the running $app_name"
    if [ "$on_windows" = 1 ]; then
      MSYS2_ARG_CONV_EXCL='*' taskkill /IM "$app_name.exe" /F >/dev/null 2>&1 || true
    else
      pkill -x "$app_name" || true
    fi
    sleep 1
  else
    echo "install-desktop.sh: $app_name is running, and its jars are locked — close it or pass --stop" >&2
    exit 1
  fi
fi

# --- 5. the mirror -----------------------------------------------------------
mkdir -p "$dest"
echo "install-desktop.sh: mirroring $src -> $dest"

if [ "$on_windows" = 1 ]; then
  # robocopy /MIR: copy what changed, delete what the build no longer produces
  # (the main jar and the skiko runtime carry their content hash in their name,
  # so they change name every build and the old ones must go). 0-7 are success.
  src_native="$(cygpath -w "$src" 2>/dev/null || printf '%s' "$src")"
  dest_native="$(cygpath -w "$dest" 2>/dev/null || printf '%s' "$dest")"
  # MSYS2_ARG_CONV_EXCL: without it Git Bash rewrites `/MIR` into a path of its
  # own (`C:/Program Files/Git/MIR`) and robocopy refuses the whole call.
  #
  # `node` is excluded on purpose: it is the runtime a published *release* puts
  # beside the launcher (publish-desktop.mjs), not something Gradle produces. An
  # app that has updated itself once therefore has one and this build does not —
  # and /MIR would delete it, leaving that install dependent on whatever `node`
  # happens to be on the machine's PATH, which is exactly what the release is
  # shaped to avoid.
  rc=0
  MSYS2_ARG_CONV_EXCL='*' robocopy "$src_native" "$dest_native" /MIR /XD "$dest_native\node" /NFL /NDL /NJH /NJS /NP /R:1 /W:1 || rc=$?
  if [ "$rc" -ge 8 ]; then
    echo "install-desktop.sh: robocopy failed (exit $rc)" >&2
    exit 1
  fi
elif command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude '/node' "$src/" "$dest/"
else
  rm -rf "$dest"
  cp -R "$src" "$dest"
fi

# --- 6. what actually landed, and proof it is what was just built ------------
if command -v diff >/dev/null 2>&1; then
  # Same exclusion as the mirror: a bundled `node` belongs to the install, not
  # to the build, so its presence is not a mismatch.
  if ! diff -rq -x node "$src" "$dest"; then
    echo "install-desktop.sh: the installed copy does not match the build above" >&2
    exit 1
  fi
  echo "install-desktop.sh: installed copy matches the build, file for file"
else
  echo "install-desktop.sh: note — no diff(1) here, so the mirror was not verified"
fi

version="$(sed -n 's:.*<app-version>\(.*\)</app-version>.*:\1:p' "$dest/app/.jpackage.xml" 2>/dev/null | head -1)"
jar="$(find "$dest/app" -maxdepth 1 -name "composeApp-desktop-*.jar" 2>/dev/null | head -1)"
echo
echo "installed : $dest"
echo "version   : ${version:-unknown}$( [ -n "${version:-}" ] || echo ' (no app/.jpackage.xml)' )"
if [ -n "$jar" ]; then
  sum="$(sha256sum "$jar" 2>/dev/null | cut -c1-16 || true)"
  echo "app jar   : $(basename "$jar")"
  echo "jar sha256: ${sum:-unavailable}… (first 16, from $(date -r "$jar" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '?'))"
fi
echo "built from: $(git -C "$tree" rev-parse --short HEAD 2>/dev/null || echo '?') + $(cd "$here/overrides" && find . -type f | wc -l | tr -d ' ') overlay files"
