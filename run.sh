#!/bin/zsh
# Build Play Home and run it as the Home app on its own Android TV emulator. Never touches the TV,
# or the shared emulator other launchers are tested on: it only ever talks to emulator-$PORT.
# Usage: ./run.sh            (AVD=name PORT=n to use a different emulator)
set -e
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
AVD=${AVD:-PlayHome_TV}
PORT=${PORT:-5620}
export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}

cd "${0:A:h}"
export ANDROID_SERIAL=emulator-$PORT
running=$($ADB emu avd name 2>/dev/null | head -1 | tr -d '\r')
# Other sessions run their own emulators; never install over one that isn't ours.
if [ -n "$running" ] && [ "$running" != "$AVD" ]; then
  echo "$ANDROID_SERIAL is $running, not $AVD; pick a free port with PORT=..." >&2
  exit 1
fi
if [ -z "$running" ]; then
  echo "booting $AVD on $ANDROID_SERIAL..."
  $SDK/emulator/emulator -avd "$AVD" -port $PORT -no-snapshot-save >/dev/null 2>&1 &
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
fi

./gradlew assembleDebug --console=plain -q
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
# Home is a role on Android 10+, and the stock launcher outranks it until it's disabled (this emulator only).
$ADB shell cmd role add-role-holder android.app.role.HOME com.matthew.playhome 0
$ADB shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null 2>&1 || true
$ADB shell am start -n com.matthew.playhome/.HomeActivity >/dev/null
echo "Play Home is running on $ANDROID_SERIAL ($AVD)"
