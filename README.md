# repro4201 — ANR → `didCrashOnPreviousExecution()`

End-to-end repro for [firebase-android-sdk #4201](https://github.com/firebase/firebase-android-sdk/issues/4201)
([PR #8250](https://github.com/firebase/firebase-android-sdk/pull/8250)): after a run ends with an
ANR, `FirebaseCrashlytics.didCrashOnPreviousExecution()` returns `true` on the next launch (API 30+).
Consumes `firebase-crashlytics:20.0.7` built from the PR branch via **mavenLocal**.

## Setup

1. Publish the SDK from the firebase-android-sdk checkout (re-run after SDK changes):
   ```bash
   ./gradlew -PprojectsToPublish="firebase-crashlytics" publishReleasingLibrariesToMavenLocal
   ./gradlew -PprojectsToPublish="firebase-sessions"    publishReleasingLibrariesToMavenLocal
   ```
   (crashlytics 20.0.7 needs the unreleased sessions 3.0.7; both must be in `~/.m2`.)
2. `app/google-services.json` is bundled (package `com.example.repro4201`) — builds out of the box.
3. Device/emulator on **API 30+** (`ApplicationExitInfo` requires Android R).

## Run

```bash
./gradlew :app:installDebug
adb logcat -s Repro4201:I
```

1. **Clean launch** → `didCrashOnPreviousExecution = false` (keep network on so ANR settings fetch).
2. **Tap "Trigger ANR"**, wait ~5 s, tap **Close app** on the dialog — the process must be *killed*
   for the ANR (a recovered freeze isn't recorded).
3. **Relaunch** → `didCrashOnPreviousExecution = true`.

## How it works

On relaunch, Crashlytics' existing background `finalizeSessions` →
`writeApplicationExitInfoEventIfRelevant` detects the previous session's ANR via
`didRelevantAnrOccur(...)` and sets `didPreviousExecutionEndWithAnr`, which
`didCrashOnPreviousExecution()` ORs in — off the main thread, no extra query.

## Result ✅

Verified on a Galaxy S25 Ultra (API 36): clean launch `false`; after a real ANR
(`dumpsys activity exit-info` shows `reason=6 (ANR)`), relaunch `true`. An ANR is neither a JVM nor a
native crash, so the `true` comes solely from this change.

## Notes

- Stays `false`? Tap **Close app** (not Wait), API ≥ 30, network on first launch. Check
  `adb shell dumpsys activity exit-info com.example.repro4201 | grep -i anr`.
- The bundled `res/values/crashlytics.xml` sets `com.crashlytics.RequireBuildId=false` (this app
  doesn't apply the Crashlytics Gradle plugin) — keep it.
