#!/bin/bash
# run-emulator.sh — launch a hardware-accelerated headless emulator and wait for boot.
# Part of the Spraak derivative. GPL-3.0-or-later.
#
# Usage:
#   android/run-emulator.sh [avd-name] [apk-to-install]
#
# Hard-won notes (Windows; see android/PORTING_NOTES.md "Emulator" section):
# - ALWAYS use hardware acceleration (-accel on). "-accel off"/TCG hits a
#   reproducible pre-vCPU crash in emulator 36.x (netsim chip bring-up), and when
#   crashpad mishandles it the emulator becomes a zombie that never boots.
# - "VirtualizationFirmwareEnabled=False" on a Windows guest does NOT mean WHPX is
#   unavailable — if a hypervisor is already running it owns VT-x and masks the
#   flag. Trust `emulator-check accel`, not WMI.
# - On Windows the qemu child needs the emulator's DLL dirs on PATH, and stale
#   AVD *.lock files after a kill make the launcher abort.
set -euo pipefail

AVD="${1:-spraak}"
APK="${2:-}"

# Locate the SDK.
for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /c/Android/sdk "$HOME/Android/Sdk"; do
  [ -n "$cand" ] && [ -d "$cand/emulator" ] && SDK="$cand" && break
done
[ -n "${SDK:-}" ] || { echo "Android SDK with emulator/ not found; set ANDROID_SDK_ROOT" >&2; exit 1; }
ADB="$SDK/platform-tools/adb"

# Windows: the qemu child resolves DLLs from these dirs via PATH.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*)
  export PATH="$SDK/emulator:$SDK/emulator/lib64:$SDK/emulator/lib64/qt/lib:$PATH" ;;
esac

# Clean slate: leftover emulators and stale AVD locks abort the launcher.
pkill -f qemu-system 2>/dev/null || true
pkill -f 'emulator(\.exe)?($| )' 2>/dev/null || true
sleep 1
AVD_DIR="$HOME/.android/avd/$AVD.avd"
[ -d "$AVD_DIR" ] && rm -rf "$AVD_DIR"/*.lock 2>/dev/null || true

"$ADB" start-server >/dev/null 2>&1 || true

echo "==> launching AVD '$AVD' (WHPX/accelerated, headless) — log: $AVD_DIR/run.log"
"$SDK/emulator/emulator" -avd "$AVD" -accel on \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 3072 -cores 4 \
  > "$AVD_DIR/run.log" 2>&1 &

echo "==> waiting for boot"
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
echo "==> booted: Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"

if [ -n "$APK" ]; then
  echo "==> installing $APK"
  "$ADB" install -r "$APK"
fi
echo "Done. (Screenshots: adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png —"
echo " do NOT use 'adb exec-out ... >' redirection from PowerShell; it corrupts binaries.)"
