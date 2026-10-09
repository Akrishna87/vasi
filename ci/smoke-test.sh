#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the APK, downloads the Compact voices,
# opens a test PDF, reads it aloud (checking it carries on in the background), tries a
# second voice, and saves the PDF as an audio file.
# Usage: smoke-test.sh <apk> <pdf> <output dir>
set -euo pipefail

APK="$1"
PDF="$2"
OUT="$3"
PKG=io.github.akrishna87.vaasi
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() {
  echo "SMOKE TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o '\(text\|content-desc\)="[^"]\+"' "$OUT/failure.xml" | head -60 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  echo "Crashes and errors from the app:"
  grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80 || true
  grep -E "Vaasi|sherpa|AndroidRuntime" "$OUT/logcat.txt" | tail -40 || true
  exit 1
}
dump() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || { sleep 2; continue; }
    # The emulator's own apps sometimes freeze while it warms up; wave the popup away.
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/$1.xml" || return 0
    echo "(dismissing a system 'isn't responding' popup)"
    python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait" > /dev/null 2>&1 && adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait")
    sleep 3
  done
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
show() { echo "  on screen: $(tr '\n' ' ' < "$OUT/$1.xml" | grep -o '\(text\|content-desc\)="[^"]\+"' | sed 's/^[a-z-]*=//' | head -40 | tr '\n' ' ')"; }
tap() { # tap <dump name> <text> [first|last]
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2" "${3:-first}") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}
wait_for() { # wait_for <dump name> <grep -E pattern> <seconds> <what>
  local end=$((SECONDS + $3))
  while [ $SECONDS -lt $end ]; do
    dump "$1"
    grep -Eq "$2" "$OUT/$1.xml" && return 0
    sleep 3
  done
  fail "$4 (waited $3 s)"
}
session() { adb shell dumpsys media_session > "$OUT/session.txt"; }
state_is() { session; grep -Eq "\{state=($1)" "$OUT/session.txt"; }
wait_state() { # wait_state <state pattern> <seconds> <what>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do state_is "$1" && return 0; sleep 2; done
  fail "$3 (waited $2 s)"
}

adb wait-for-device
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell settings put global hide_error_dialogs 1 || true
adb logcat -c

echo "--- First launch"
adb shell am start -W -n "$PKG/.MainActivity"
wait_for welcome 'text="Choose a voice pack"' 30 "the welcome card isn't showing"
shot 1-welcome
echo "PASS: welcome"

echo "--- Downloading the Compact voices"
tap welcome "Choose a voice pack"
wait_for packs 'text="Voice packs"' 15 "the voice packs screen didn't open"
tap packs "Download · 99 MB"
sleep 10
dump packs-downloading
shot 2-downloading
show packs-downloading
wait_for packs-done 'text="In use"' 900 "the Compact voices didn't finish downloading and unpacking"
shot 3-installed
echo "PASS: voices downloaded and unpacked"
adb shell input keyevent KEYCODE_BACK
sleep 2

echo "--- Opening a PDF"
adb shell mkdir -p "/sdcard/Android/data/$PKG/files"
adb push "$PDF" "/sdcard/Android/data/$PKG/files/story.pdf"
adb shell am start -W -n "$PKG/.MainActivity" -a android.intent.action.VIEW \
  -d "file:///sdcard/Android/data/$PKG/files/story.pdf" -t application/pdf
wait_for book 'text="Chapter One"' 60 "the PDF didn't open"
shot 4-book
show book
grep -q 'The Lighthouse Keeper' "$OUT/book.xml" || fail "the book isn't titled from the PDF"
grep -q 'A Vaasi test story' "$OUT/book.xml" && fail "the running header wasn't removed"
grep -q 'towards the harbour' "$OUT/book.xml" || fail "the hyphenated 'har-bour' wasn't mended"
echo "PASS: the PDF's text is cleaned up"

echo "--- Reading aloud"
tap book "Play"
wait_state "PLAYING|3" 180 "the voice didn't start reading"
echo "PASS: reading aloud"
sleep 12
dump reading
shot 5-reading
show reading
state_is "PLAYING|3|BUFFERING|6" || fail "reading stopped by itself"

adb shell input keyevent KEYCODE_HOME
sleep 10
state_is "PLAYING|3|BUFFERING|6" || fail "reading stopped when the app went to the background"
echo "PASS: keeps reading in the background"
adb shell cmd statusbar expand-notifications
sleep 2
shot 6-notification
adb shell cmd statusbar collapse

adb shell am start -W -n "$PKG/.MainActivity"
wait_for back-in 'content-desc="Pause"' 30 "the player doesn't offer Pause while reading"
tap back-in "Pause"
wait_state "PAUSED|2" 15 "pausing didn't pause"
echo "PASS: pause"

echo "--- Choosing another voice"
dump paused
tap paused "Bella"
wait_for voices 'text="Narrator"' 15 "the voices screen didn't open"
# Voices near the top of the list, so no scrolling is needed.
tap voices "Hear Michael"
sleep 8
tap voices "Michael"
sleep 2
dump voices-michael
shot 7-voices
tap voices-michael "Dialogue"
sleep 2
dump dialogue
tap dialogue "Sarah"
sleep 1
shot 8-dialogue
adb shell input keyevent KEYCODE_BACK
sleep 2
dump book-voice
grep -q 'text="Michael"' "$OUT/book-voice.xml" || fail "the player doesn't show the new voice"
echo "PASS: voices"

echo "--- Saving as an audio file"
tap book-voice "More"
sleep 1
dump menu
tap menu "Save as audio file"
sleep 1
dump confirm
tap confirm "Save"
wait_for saved 'Saved to Music/Vaasi' 600 "saving the audio file didn't finish"
shot 9-saved
adb shell ls -l /sdcard/Music/Vaasi/ | tee "$OUT/music.txt"
adb pull "/sdcard/Music/Vaasi/The Lighthouse Keeper.m4a" "$OUT/sample.m4a" || fail "the audio file isn't in Music/Vaasi"
[ "$(stat -c %s "$OUT/sample.m4a")" -gt 50000 ] || fail "the audio file is suspiciously small"
echo "PASS: saved $(stat -c %s "$OUT/sample.m4a") bytes of audio"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
adb logcat -d | grep -E "VaasiTts|VaasiReader|VaasiExport" | tail -20 || true
echo "ALL SMOKE TESTS PASSED"
