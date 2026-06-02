# repro4201 — ANR → `didCrashOnPreviousExecution()`

Manual end-to-end repro for [firebase-android-sdk#4201]: after the previous run ends with an
ANR, `FirebaseCrashlytics.didCrashOnPreviousExecution()` returns `true` on the next launch
(API 30+). It consumes `firebase-crashlytics:20.0.7` built from branch
`feature/jrc--Issue4201-2.API.to.check.previous.ANR` via **mavenLocal**.

## One-time setup

1. **Publish the branch SDK to mavenLocal** (already done once; re-run after any SDK code change):
   ```bash
   cd ..            # firebase-android-sdk root
   ./gradlew -PprojectsToPublish="firebase-crashlytics" publishReleasingLibrariesToMavenLocal
   ./gradlew -PprojectsToPublish="firebase-sessions"    publishReleasingLibrariesToMavenLocal
   ```
   (crashlytics 20.0.7 requires the unreleased sessions 3.0.7; both must be in `~/.m2`.)

2. **Create the Firebase Android app** in your project with package name **`com.example.repro4201`**
   (no SHA-1 needed for Crashlytics). Download `google-services.json` and put it at:
   ```
   repro4201/app/google-services.json
   ```

3. **Device/emulator on API 30+** (ApplicationExitInfo requires Android R). `adb devices` must list one.

## Build & install

```bash
cd repro4201
./gradlew :app:installDebug
adb shell am start -n com.example.repro4201/.MainActivity
adb logcat -s Repro4201:I
```

## Test procedure

1. **Clean launch.** Screen shows `didCrashOnPreviousExecution = false`. (This first launch also lets
   Crashlytics fetch settings that enable ANR collection — keep network on.)
2. **Tap "Trigger ANR".** The main thread blocks. After ~5 s the system shows an ANR dialog.
   Tap **"Close app"** so the OS records `REASON_ANR` in `ApplicationExitInfo`.
   (A freeze that recovers is *not* recorded — the process must be killed for the ANR.)
3. **Relaunch.** Screen should now show `didCrashOnPreviousExecution = true`, and logcat prints
   `didCrashOnPreviousExecution=true`.

## How it maps to the change

On the relaunch, Crashlytics background init runs `finalizeSessions` →
`writeApplicationExitInfoEventIfRelevant(previousSession)`, which now records the
`didPreviousExecutionEndWithAnr` flag via `didRelevantAnrOccur(...)`. `didCrashOnPreviousExecution()`
ORs that flag in — no main-thread blocking, no separate query. The app reads the value on a
background thread and polls briefly because the flag is set asynchronously during init.

## Troubleshooting

- **Stays `false` after an ANR:** confirm you tapped **Close app** (not "Wait"); confirm API ≥ 30;
  confirm the first launch had network so ANR collection settings were fetched. Verify the OS saw
  the ANR: `adb shell dumpsys activity exit-info | grep -i anr`.
- **`google-services.json is missing`:** file not placed at `app/google-services.json`, or the
  package name in it isn't `com.example.repro4201`.
- **`Could not resolve firebase-crashlytics:20.0.7` / `firebase-sessions:3.0.7`:** re-run the
  publish commands in step 1.
- **Startup crash with a "missing build ID" banner:** this app does not apply the
  `firebase-crashlytics` Gradle plugin, so no mapping/build ID is injected. The bundled
  `app/src/main/res/values/crashlytics.xml` sets `<bool name="com.crashlytics.RequireBuildId">false</bool>`
  to disable that requirement — keep it.

---

# Test Results (real device)

**Date:** 2026-06-02
**Device:** Samsung Galaxy S25 Ultra (SM-S938B), Android 16 / **API 36**
**SDK under test:** `firebase-crashlytics:20.0.7` built from branch
`feature/jrc--Issue4201-2.API.to.check.previous.ANR` (mavenLocal), with `firebase-sessions:3.0.7`.

## Result: ✅ PASS

`FirebaseCrashlytics.didCrashOnPreviousExecution()` returns **true** on the launch following an
ANR, and **false** otherwise.

### Step 1 — clean launch
```
Repro4201: didCrashOnPreviousExecution=false (attempt 1..10)
FirebaseCrashlytics: Initializing Firebase Crashlytics 20.0.7 for com.example.repro4201
```

### Step 2 — induced a real ANR
Tapped "Trigger ANR" (blocks main thread), system raised the ANR dialog
("repro4201 isn't responding"), tapped **Close app**. The OS recorded it:
```
dumpsys activity exit-info com.example.repro4201:
  process=com.example.repro4201 reason=6 (ANR) subreason=0
  description=user request after error: Input dispatching timed out
              (... is not responding. Waited 10000ms for MotionEvent)
```

### Step 3 — relaunch
```
Repro4201: didCrashOnPreviousExecution=true (attempt 1)
```

## Why this proves the change (confound check)

- Vanilla Crashlytics returns `true` from `didCrashOnPreviousExecution()` only for JVM/native
  crashes. An ANR is neither — so a `true` here is attributable solely to the new ANR-folding code.
- An unrelated `reason=4 (APP CRASH)` from the first (build-id) launch is **not** the cause:
  the clean launch reported `false` 10/10, so that crash set no crash marker. The only new
  exit event between the `false` run and the `true` run is the `reason=6 (ANR)`.
- Mechanism exercised: on relaunch, background init `finalizeSessions` →
  `writeApplicationExitInfoEventIfRelevant(previousSession)` → `didRelevantAnrOccur(...)` set
  `didPreviousExecutionEndWithAnr`, which `didCrashOnPreviousExecution()` ORs in — off the main
  thread, no extra system query. (`true` appeared on poll attempt 1, i.e. the flag was already set.)
