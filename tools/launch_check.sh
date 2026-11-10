#!/bin/bash
# Installs an APK, opens it, waits, and fails if the app process died. usage: launch_check.sh <apk>
apk=$1
adb logcat -c
adb install -r "$apk" 2>&1 | tee launch-install.txt
adb shell pm grant app.carnet android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell am start -W -n app.carnet/.MainActivity | tee launch-start.txt
sleep 20
adb exec-out screencap -p > launch-screen1.png
adb shell input tap 540 900; sleep 5
adb exec-out screencap -p > launch-screen2.png
adb logcat -d > launch-logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|Fatal signal|DEBUG   :" -A 40 launch-logcat.txt | head -200 | tee launch-crash.txt
pid=$(adb shell pidof app.carnet)
echo "pid=$pid"
if [ -z "$pid" ] || grep -q "FATAL EXCEPTION\|Fatal signal" launch-logcat.txt; then echo "APP CRASHED"; exit 1; fi
echo "APP RUNNING"
