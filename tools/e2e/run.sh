#!/bin/bash
# Inside the emulator runner: installs the app, copies the test files into its storage, runs the
# end-to-end test and pulls the results to e2e-out/. usage: run.sh <best|fast>
set -x
q=${1:-best}
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant app.carnet android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell mkdir -p /data/local/tmp/e2e
adb push testdata/e2e/. /data/local/tmp/e2e/ > /dev/null
adb shell chmod -R a+r /data/local/tmp/e2e
adb shell "run-as app.carnet sh -c 'mkdir -p files/e2e/in && cp /data/local/tmp/e2e/* files/e2e/in/'"
adb logcat -c
adb shell am instrument -w -e class app.carnet.E2E -e quality "$q" app.carnet.test/androidx.test.runner.AndroidJUnitRunner | tail -20
adb logcat -d | grep -E "CarnetE2E|CarnetScan|CarnetCt2|AndroidRuntime|Carnet  " > e2e-logcat.txt
mkdir -p e2e-out
adb exec-out run-as app.carnet tar -C files/e2e/out -cf - . | tar -xf - -C e2e-out
ls -la e2e-out
