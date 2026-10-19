#!/usr/bin/env bash
# Build the Nuvio-based phone app from this tree and put it on the attached
# phone.
#
#   ./shell-mobile/install-mobile.sh              apply overrides, build, install
#   ./shell-mobile/install-mobile.sh --no-build   install the last build as it stands
#   ./shell-mobile/install-mobile.sh --device 52070e9f
#   ./shell-mobile/install-mobile.sh --fast       minified, shrunk, same package (faster on a slow phone)
#   ./shell-mobile/install-mobile.sh --release    build the release variant too
#
# A phone install is not a folder mirror like the window's: it is one APK, and
# adb either takes it or says why. What this script adds over a bare `adb
# install` is the discipline the desktop script has — the overrides are laid
# over the upstream tree first (so the build is this repository's, never a
# stale one), the tree's commit and the APK's digest are printed (so a build
# can be traced to what produced it), and the version the *phone* reports
# afterwards is read back, because "Success" is the installer's word and not
# the app's.
#
# It builds the **debug** variant by default: same code, `com.nuviodebug.com`
# as its application id, the debug keystore signing it. `--fast` builds the
# `fast` type instead — the release pipeline (R8, resource shrinking, no debug
# overhead) signed with the debug key and installed under the same package, so
# it upgrades in place and keeps every preference. On a low-end phone that is
# the difference between usable and laggy. The real release variant needs the
# upstream release keystore from local.properties, which this checkout does not
# have — see NOTICE.md for what that means for shipping.
#
# Requires JDK 17 (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`, or the path
# this repo's android/local.properties already records) and adb (on PATH, or in
# `$ANDROID_HOME/platform-tools`).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"        # hanime-app/shell-mobile
repo="$(cd "$here/.." && pwd)"               # hanime-app
tree="$here/.upstream"
variant="full"
build_type="debug"

build=1
device=""
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --device) shift_next=device ;;
    --fast) build_type="fast" ;;
    --release) build_type="release" ;;
    -h | --help)
      sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      if [ "${shift_next:-}" = "device" ]; then device="$arg"; shift_next=""
      else echo "install-mobile.sh: unknown option: $arg" >&2; exit 2
      fi
      ;;
  esac
done

if [ ! -d "$tree/.git" ]; then
  echo "install-mobile.sh: no build tree at $tree — run ./shell-mobile/sync.sh first" >&2
  exit 1
fi

# Toolchain
if [ -z "${JAVA_HOME:-}" ]; then
  echo "install-mobile.sh: JAVA_HOME is not set — JDK 17 is what upstream builds with" >&2
  exit 1
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$tree/local.properties" ]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' "$tree/local.properties" | head -1 | sed 's/\\\\/\\/g; s|\\:|:|g')"
fi
if [ -z "$sdk" ] && [ -f "$repo/android/local.properties" ]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' "$repo/android/local.properties" | head -1)"
fi
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  echo "install-mobile.sh: no Android SDK found — set ANDROID_HOME" >&2
  exit 1
fi
export ANDROID_HOME="$sdk"

adb="$(command -v adb || true)"
if [ -z "$adb" ] && [ -x "$sdk/platform-tools/adb" ]; then adb="$sdk/platform-tools/adb"; fi
if [ -z "$adb" ] && [ -x "$sdk/platform-tools/adb.exe" ]; then adb="$sdk/platform-tools/adb.exe"; fi
if [ -z "$adb" ]; then
  echo "install-mobile.sh: adb not found (path or \$ANDROID_HOME/platform-tools/adb)" >&2
  exit 1
fi

# This tree's sources, laid over upstream — the same thing sync.sh does, without
# the fetch, so a script run never depends on the network being up. A fork with
# no overrides yet is upstream exactly, which is a build worth being able to
# make (it is how the toolchain is proven).
#
# The tracked files are reset to the pin before the copy, because overrides are
# *files laid over it*: a file deleted from overrides/ would otherwise stay in
# the tree and keep being compiled, which is exactly how a removed Kotlin
# override went on failing the build after it was gone from this repository.
# Untracked things — the APK, `local.properties`, `build/` — are left alone, so
# this costs seconds and never a rebuild of something already built.
if [ -d "$tree/.git" ]; then
  ( cd "$tree" && git checkout -q -- . 2>/dev/null ) || true
fi
if [ -d "$here/overrides" ]; then
  echo "install-mobile.sh: applying overrides"
  cp -R "$here/overrides/." "$tree/"
else
  echo "install-mobile.sh: no overrides/ yet — building upstream unmodified"
fi

apk_dir="$tree/androidApp/build/outputs/apk/$variant/$build_type"
if [ "$build" = 1 ]; then
  echo "install-mobile.sh: building :androidApp:assemble${variant^}${build_type^}"
  # The wrapper *script* in this tree has CRLF line endings (upstream is
  # Windows-checked-out), and the SDK's .bat wrappers break on a path with a
  # space in it — so the wrapper is invoked as a Java class, which is what the
  # script would have done anyway.
  ( cd "$tree" \
    && "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar \
         org.gradle.wrapper.GradleWrapperMain \
         ":androidApp:assemble${variant^}${build_type^}" --no-daemon --console=plain )
fi

apk="$(ls -t "$apk_dir"/*.apk 2>/dev/null | head -1 || true)"
if [ -z "$apk" ]; then
  echo "install-mobile.sh: no APK under $apk_dir — build it first" >&2
  exit 1
fi

choose_device() {
  local list
  list="$("$adb" devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [ -n "$device" ]; then
    if ! printf '%s\n' "$list" | grep -qx "$device"; then
      echo "install-mobile.sh: device $device is not attached (attached: $(printf '%s' "$list" | tr '\n' ' '))" >&2
      exit 1
    fi
    return
  fi
  local count
  count="$(printf '%s\n' "$list" | grep -c . || true)"
  if [ "$count" -eq 0 ]; then
    echo "install-mobile.sh: no device attached — plug the phone in (adb devices)" >&2
    exit 1
  fi
  if [ "$count" -gt 1 ]; then
    echo "install-mobile.sh: more than one device attached — pass --device <serial>" >&2
    printf '  %s\n' $list >&2
    exit 1
  fi
  device="$list"
}
choose_device

app_id="com.nuviodebug.com"
[ "$build_type" = "release" ] && app_id="com.nuvio.app"

# adb.exe on Windows cannot read a MSYS/Git-Bash path (`/f/…`): it asks the
# kernel for it, gets "No such file or directory", and the error names the path
# it was given — which reads like the APK is missing. Hand it a native path.
apk_arg="$apk"
if command -v cygpath >/dev/null 2>&1; then
  apk_arg="$(cygpath -w "$apk")"
fi

echo "install-mobile.sh: installing $(basename "$apk") on $device"
# -r replaces, -d tolerates a lower versionCode (a debug build can be younger
# than whatever release is already installed).
#
# adb's exit status is not the verdict: on a refusal it prints "Failure [...]"
# and still returns 0, which used to carry the script straight into the
# "installed" summary and print a version for a package the phone never took.
# So the output is captured and judged, and the refusal a locked-down MIUI/HyperOS
# phone gives is named with the switch that fixes it (nothing here can flip it:
# its Developer options refuse input injection too).
install_log="$("$adb" -s "$device" install -r -d "$apk_arg" 2>&1)" || true
printf '%s\n' "$install_log" | sed '/^ *$/d; s/^/  /' | tail -6
case "$install_log" in
  *"INSTALL_FAILED_USER_RESTRICTED"*)
    {
      echo "install-mobile.sh: the phone refused the package — MIUI/HyperOS gates adb" >&2
      echo "install-mobile.sh: installs behind Developer options → \"Install via USB\"." >&2
      echo "install-mobile.sh: enable it (and unlock the screen) and re-run." >&2
    }
    exit 1
    ;;
esac
if ! printf '%s\n' "$install_log" | grep -q 'Success'; then
  echo "install-mobile.sh: adb did not report Success — the install did not happen" >&2
  exit 1
fi

echo
echo "installed  : $(basename "$apk")"
echo "  apk sha256: $(sha256sum "$apk" | cut -d' ' -f1)"
# Both commits are read from *inside* the trees rather than with `git -C`: a
# caller with MSYS_NO_PATHCONV=1 (which this script's own adb path needs) hands
# git a `/f/…` path it cannot resolve, and the summary then loses the pin it
# exists to print.
pin="$( cd "$tree" && git rev-parse --short HEAD 2>/dev/null || echo '?' )"
ours="$( cd "$repo" && git rev-parse --short HEAD 2>/dev/null || echo 'this tree' )"
echo "  built from: $pin (NuvioMobile pin) + overrides/ from $ours"
reported="$("$adb" -s "$device" shell dumpsys package "$app_id" 2>/dev/null | grep -m1 -E "versionName=" | tr -d '\r' | sed 's/^ *//')"
if [ -n "$reported" ]; then
  echo "  on the phone: $app_id $reported"
else
  echo "  on the phone: $app_id — the package did not report a version; check adb devices"
fi
